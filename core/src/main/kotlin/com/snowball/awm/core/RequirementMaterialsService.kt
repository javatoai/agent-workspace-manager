package com.snowball.awm.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.DosFileAttributes
import java.time.Duration
import java.util.Locale

/** The outcome of provisioning a requirement's local materials directory. */
sealed interface RequirementMaterialsResult {
    val requirementId: String?

    data class Ready(
        override val requirementId: String,
        val requirementPath: Path,
        val writeRoot: Path,
        val status: Status,
    ) : RequirementMaterialsResult {
        enum class Status { CREATED, REUSED }
    }

    data class Failed(
        override val requirementId: String?,
        val reason: String,
        val existingPaths: List<Path> = emptyList(),
    ) : RequirementMaterialsResult
}

/** Inputs kept together so callers can retry with exactly the same values. */
data class RequirementMaterialsRequest(
    val requirementInput: String,
    val folderName: String,
    val materialsRoot: String?,
    val subdirectoryName: String?,
    val projects: List<MeegleProjectConfig>,
)

/**
 * A validated requirement-directory context shared by desktop and Agent flows.
 * The application layer must not derive these relationships itself: a
 * requirement directory is always directly below a Sprint directory and its
 * write root is always the configured materials subdirectory.
 */
data class RequirementMaterialsDirectoryContext(
    val requirementDirectory: Path,
    val writeRoot: Path,
    val iterationDirectory: Path,
    val sprint: RequirementSprintSnapshot,
)

/**
 * Creates (or reuses) the non-source-code directory used for requirement notes and scripts.
 *
 * The service deliberately has no deletion operation.  A failed CLI lookup is returned as a
 * value, allowing task creation to continue and the UI to offer a later retry.
 */
class RequirementMaterialsService(
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val meegleExecutable: MeegleExecutable = MeegleExecutable.pathFallback(),
    private val commandTimeout: Duration = Duration.ofSeconds(20),
    private val documentationFiles: RequirementDocumentationFileStore = RequirementDocumentationFileStore(),
) {
    fun ensure(
        requirementInput: String,
        folderName: String,
        materialsRoot: String?,
        subdirectoryName: String?,
        projects: List<MeegleProjectConfig>,
    ): RequirementMaterialsResult = ensure(
        RequirementMaterialsRequest(
            requirementInput = requirementInput,
            folderName = folderName,
            materialsRoot = materialsRoot,
            subdirectoryName = subdirectoryName,
            projects = projects,
        ),
    )

    @Synchronized
    fun ensure(request: RequirementMaterialsRequest): RequirementMaterialsResult {
        lastFailure = null
        val parsed = parseRequirementInput(request.requirementInput)
            ?: return RequirementMaterialsResult.Failed(null, "需求输入必须是数字需求编号或 Meegle 详情链接")
        val requirementId = parsed.id

        return try {
            val root = validateRoot(request.materialsRoot)
                ?: return RequirementMaterialsResult.Failed(requirementId, "资料根路径不能为空")
            val subdirectory = validateSegment(request.subdirectoryName, "资料子目录名", 100)
                ?: return RequirementMaterialsResult.Failed(requirementId, "资料子目录名不能为空")
            val folder = validateSegment(request.folderName, "需求文件夹名称", 80)
                ?: return RequirementMaterialsResult.Failed(requirementId, "需求文件夹名称不能为空")

            FileLocking.withExclusiveLock(
                root.resolve(LOCK_FILE),
                "需求资料目录正在被另一个 AWM 操作修改：$root",
            ) {
                val existing = findRequirementDirectories(root, requirementId)
                if (existing.size > 1) {
                    return@withExclusiveLock RequirementMaterialsResult.Failed(
                        requirementId,
                        "同一需求编号匹配到多个需求目录，未自动选择",
                        existing,
                    )
                }
                if (existing.singleOrNull() != null) {
                    val context = resolveExistingRequirementDirectory(
                        root = root,
                        id = requirementId,
                        subdirectory = subdirectory,
                        requirementInput = request.requirementInput,
                    ) ?: error("需求资料目录复用判定丢失")
                    val writeRoot = ensureDirectory(root, context.writeRoot)
                    return@withExclusiveLock RequirementMaterialsResult.Ready(
                        requirementId = requirementId,
                        requirementPath = context.requirementDirectory,
                        writeRoot = writeRoot,
                        status = RequirementMaterialsResult.Ready.Status.REUSED,
                    )
                }

                val project = resolveProject(parsed, requirementId, request.projects)
                    ?: return@withExclusiveLock RequirementMaterialsResult.Failed(
                        requirementId,
                        lastFailure ?: if (request.projects.isEmpty()) "未配置 Meegle 项目" else "未能唯一匹配需求所属的 Meegle 项目",
                    )
                val sprint = resolveActiveSprint(project, requirementId)
                    ?: return@withExclusiveLock RequirementMaterialsResult.Failed(requirementId, lastFailure ?: "需求没有关联当前进行中的 Sprint")

                val requirementPath = buildRequirementDirectory(root, sprint.name, requirementId, folder)
                val writeRoot = ensureDirectory(root, requirementPath.resolve(subdirectory))
                RequirementMaterialsResult.Ready(
                    requirementId = requirementId,
                    requirementPath = requirementPath,
                    writeRoot = writeRoot,
                    status = RequirementMaterialsResult.Ready.Status.CREATED,
                )
            }
        } catch (error: Exception) {
            RequirementMaterialsResult.Failed(requirementId, "需求资料目录创建失败：${error.message ?: error::class.simpleName}")
        }
    }

    private var lastFailure: String? = null

    /**
     * Parses a Feishu work-item link for Agent flows.
     *
     * This is the single boundary for converting an external requirement link
     * into the domain identity used by both desktop and Agent material flows.
     * Invalid input is returned as a failed Result so callers can keep their
     * own error handling boundary.
     */
    fun parseRequirementIdentity(raw: String): Result<RequirementIdentity> = runCatching {
        val link = FeishuWorkItemLink.parse(raw)
            ?: throw IllegalArgumentException("需求链接不是支持的飞书项目工作项链接")
        RequirementIdentity(
            space = link.space,
            kind = link.kind,
            workItemId = link.workItemId,
        )
    }

    /** Returns the built-in Feishu project key for a validated requirement link, when known. */
    fun resolveRequirementProjectKey(raw: String): String? = FeishuWorkItemLink.parse(raw)?.projectKey

    /** Validates the configured root for both desktop and Agent material flows. */
    fun requireMaterialsRoot(raw: String?): Path = validateRoot(raw)
        ?: throw IllegalStateException("尚未配置需求资料根目录；请在 AWM 设置 > 路径设置中配置")

    /** Validates the configured write-root child for both desktop and Agent flows. */
    fun requireMaterialsSubdirectory(raw: String?): String = validateSegment(raw, "资料子目录名", 100)
        ?: throw IllegalStateException("尚未配置需求资料子目录；请在 AWM 设置 > 路径设置中配置")

    /** Applies the single path-boundary rule owned by the materials service. */
    fun requirePathInsideMaterialsRoot(path: Path, root: Path, label: String): Path {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalized = path.toAbsolutePath().normalize()
        require(normalized.startsWith(normalizedRoot)) { "$label 超出配置的需求资料根目录：$path" }
        return normalized
    }

    /** Creates only missing directories beneath an already validated root. */
    fun ensureMaterialsDirectory(root: Path, path: Path): Path = ensureDirectory(
        root.toAbsolutePath().normalize(),
        requirePathInsideMaterialsRoot(path, root, "需求资料目录"),
    )

    private data class ParsedRequirement(val id: String, val simpleName: String?, val kind: String?)
    private data class ProjectMatch(val project: MeegleProjectConfig)
    private data class SprintMatch(val id: String, val name: String)

    private fun parseRequirementInput(raw: String): ParsedRequirement? {
        val value = raw.trim().trimEnd(',', '，', '。', ';', '；')
        if (value.matches(Regex("\\d+"))) return ParsedRequirement(value, null, null)
        val link = FeishuWorkItemLink.parse(value) ?: return null
        return ParsedRequirement(link.workItemId, link.space, link.kind)
    }

    private fun resolveProject(
        parsed: ParsedRequirement,
        requirementId: String,
        projects: List<MeegleProjectConfig>,
    ): MeegleProjectConfig? {
        val candidates = if (parsed.simpleName != null) {
            projects.filter { it.simpleName.equals(parsed.simpleName, ignoreCase = true) }
        } else {
            projects
        }
        if (candidates.isEmpty()) {
            if (parsed.simpleName != null) {
                lastFailure = "需求链接所属的飞书项目未配置：${parsed.simpleName}"
            }
            return null
        }

        val matches = mutableListOf<ProjectMatch>()
        for (project in candidates) {
            val result = runCommand(
                listOf(
                    meegleExecutable.resolve(),
                    "workitem",
                    "get",
                    "--project-key",
                    project.projectKey,
                    "--work-item-id",
                    requirementId,
                    "--format",
                    "json",
                ),
            ) ?: return null
            if (!result.succeeded) {
                if (isMissingWorkItem(result.stderr)) continue
                lastFailure = "Meegle CLI 执行失败：${result.stderr.trim().ifBlank { "exit=${result.exitCode}" }}"
                return null
            }
            val element = runCatching { json.parseToJsonElement(result.stdout) }.getOrNull() ?: run {
                lastFailure = "Meegle CLI 返回不是可解析的 JSON"
                return null
            }
            if (containsWorkItemId(element, requirementId)) matches += ProjectMatch(project)
        }
        return when {
            matches.size == 1 -> matches.single().project
            matches.isEmpty() -> null
            else -> {
                lastFailure = "同一需求编号匹配到多个 Meegle 项目"
                null
            }
        }
    }

    private fun resolveActiveSprint(project: MeegleProjectConfig, requirementId: String): SprintMatch? {
        // Fetch the work item again so this method remains independent of project lookup and can
        // be retried safely after the user changes CLI authentication.
        val base = runCommand(
            listOf(
                meegleExecutable.resolve(), "workitem", "get",
                "--project-key", project.projectKey,
                "--work-item-id", requirementId,
                "--format", "json",
            ),
        ) ?: return null
        if (!base.succeeded) {
            lastFailure = "Meegle CLI 执行失败：${base.stderr.trim().ifBlank { "exit=${base.exitCode}" }}"
            return null
        }
        val baseElement = runCatching { json.parseToJsonElement(base.stdout) }.getOrNull() ?: return null
        val typeKey = stringAt(baseElement, listOf("work_item_attribute", "work_item_type", "key"))
            ?: stringAt(baseElement, listOf("work_item_attribute", "work_item_type", "name"))
            ?: run {
                lastFailure = "需求详情缺少工作项类型"
                return null
            }
        val meta = runCommand(
            listOf(
                meegleExecutable.resolve(), "workitem", "meta-fields",
                "--project-key", project.projectKey,
                "--work-item-type", typeKey,
                "--page-num", "1",
                "--field-query", "Sprint",
                "--format", "json",
            ),
        ) ?: return null
        if (!meta.succeeded) {
            lastFailure = "Meegle CLI 执行失败：${meta.stderr.trim().ifBlank { "exit=${meta.exitCode}" }}"
            return null
        }
        val metaElement = runCatching { json.parseToJsonElement(meta.stdout) }.getOrNull() ?: return null
        val sprintFields = arrayAt(metaElement, listOf("list")).filter {
            stringAt(it, listOf("field_name")) == "Sprint"
        }
        if (sprintFields.size != 1) {
            lastFailure = "Sprint 字段不存在或匹配不唯一"
            return null
        }
        val fieldKey = stringAt(sprintFields.single(), listOf("field_key")) ?: return null
        val requestedFields = sprintFieldsArgument(fieldKey)
        val withSprint = runCommand(
            listOf(
                meegleExecutable.resolve(), "workitem", "get",
                "--project-key", project.projectKey,
                "--work-item-id", requirementId,
                "--fields", requestedFields,
                "--format", "json",
            ),
        ) ?: return null
        if (!withSprint.succeeded) {
            lastFailure = "Meegle CLI 执行失败：${withSprint.stderr.trim().ifBlank { "exit=${withSprint.exitCode}" }}"
            return null
        }
        val sprintElement = runCatching { json.parseToJsonElement(withSprint.stdout) }.getOrNull() ?: return null
        val relatedIds = fieldValues(sprintElement, fieldKey).mapNotNull(::workItemValue).distinct()
        if (relatedIds.isEmpty()) {
            lastFailure = "需求未关联任何 Sprint"
            return null
        }

        val mql = "SELECT `work_item_id`, `name`, `work_item_status`, `archiving_status` FROM `${project.projectKey}`.`sprint` WHERE `work_item_status` = '进行中'"
        val queried = runCommand(
            listOf(
                meegleExecutable.resolve(), "workitem", "query",
                "--project-key", project.projectKey,
                "--mql", mql,
                "--auto-paginate", "--format", "json",
            ),
        ) ?: return null
        if (!queried.succeeded) {
            lastFailure = "Meegle CLI 执行失败：${queried.stderr.trim().ifBlank { "exit=${queried.exitCode}" }}"
            return null
        }
        val rows = runCatching { mqlRows(json.parseToJsonElement(queried.stdout)) }.getOrElse {
            lastFailure = "Meegle CLI 返回不是可解析的 JSON"
            return null
        }
        val active = rows.filter { row ->
            val id = row["work_item_id"] ?: row["item_id"] ?: return@filter false
            val status = row["work_item_status"].orEmpty()
            val archived = row["archiving_status"]?.toBooleanStrictOrNull() ?: false
            relatedIds.contains(id) && !archived && (status == "进行中" || status.equals("In Progress", true))
        }
        return try {
            val snapshot = resolveUniqueActiveSprint(active.map {
                RequirementSprint(
                    id = it["work_item_id"] ?: it["item_id"].orEmpty(),
                    label = it["name"].orEmpty(),
                    status = "进行中",
                )
            })
            SprintMatch(snapshot.id, snapshot.label)
        } catch (error: IllegalArgumentException) {
            lastFailure = when {
                active.isEmpty() -> "需求没有关联当前进行中的 Sprint"
                else -> "需求同时关联多个当前进行中的 Sprint"
            }
            null
        }
    }

    private fun runCommand(command: List<String>): CommandResult? = runCatching {
        runner.run(command, timeout = commandTimeout, environment = meegleExecutable.environment())
    }.onFailure { lastFailure = "Meegle CLI 执行失败：${it.message.orEmpty()}" }.getOrNull()

    /**
     * NVM's Windows `meegle.cmd` shim consumes unescaped JSON quotes before they
     * reach the Node CLI.  Preserve them only for that launcher; native binaries
     * and Unix shells must continue receiving ordinary JSON.
     */
    private fun sprintFieldsArgument(fieldKey: String): String {
        val jsonArgument = "[\"${fieldKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"]"
        val isWindowsBatchLauncher = System.getProperty("os.name")
            .lowercase(Locale.ROOT)
            .contains("win") && meegleExecutable.current().endsWith(".cmd", ignoreCase = true)
        return if (isWindowsBatchLauncher) jsonArgument.replace("\"", "\\\"") else jsonArgument
    }

    private fun validateRoot(raw: String?): Path? {
        val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val path = Path.of(value).toAbsolutePath().normalize()
        if (!path.isAbsolute || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IllegalArgumentException("资料根路径必须是已存在的目录")
        }
        assertDirectory(path)
        return path
    }

    private fun validateSegment(value: String?, label: String, maxLength: Int): String? {
        val segment = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        require(segment != "." && segment != "..") { "$label 不能是 . 或 .." }
        require(!invalidSegmentPattern.containsMatchIn(segment)) { "$label 包含 Windows 非法文件名字符" }
        require(!segment.endsWith('.') && !segment.endsWith(' ')) { "$label 不能以空格或句点结尾" }
        require(segment.length <= maxLength) { "$label 超过允许长度" }
        val baseName = segment.trimEnd('.', ' ')
        require(baseName.uppercase(Locale.ROOT) !in reservedNames) { "$label 使用了 Windows 保留名称" }
        return segment
    }

    /**
     * Finds requirement directories that can be reused by either the desktop
     * task flow or the Agent documentation flow.  The directory name is the
     * only stable local identity available for a materials directory, so an
     * exact id or an id-prefixed name is accepted and every match is returned
     * to let the caller enforce the unique-match rule.
     */
    fun findExistingRequirementDirectories(root: Path, id: String): List<Path> =
        findRequirementDirectories(root.toAbsolutePath().normalize(), id)

    /**
     * Resolves the one existing requirement directory, if any, and validates
     * its Sprint and configured write-root structure.  This is intentionally
     * independent of Meegle, so historical directories remain reusable when
     * the CLI is offline or their Sprint has ended.
     */
    fun resolveExistingRequirementDirectory(
        root: Path,
        id: String,
        subdirectory: String,
        requirementInput: String? = null,
        readSprint: (Path) -> RequirementSprintSnapshot? = { null },
    ): RequirementMaterialsDirectoryContext? {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val matches = findExistingRequirementDirectories(normalizedRoot, id)
        require(matches.size <= 1) {
            "同一需求编号匹配到多个需求目录，未自动选择（多个历史目录）：${matches.joinToString()}"
        }
        val requirementDirectory = matches.singleOrNull() ?: return null
        requirementInput?.let { validateExistingRequirementDirectory(requirementDirectory, it) }
        return existingDirectoryContext(normalizedRoot, requirementDirectory, id, subdirectory, readSprint)
    }

    /**
     * Finds all process-manifest paths for the supplied requirement number.
     * Reading and decoding those files stays in [RequirementDocumentationFileStore];
     * this API only performs filesystem discovery.
     */
    fun findRequirementManifestPaths(root: Path, id: String): List<Path> {
        val normalizedRoot = root.toAbsolutePath().normalize()
        if (!Files.exists(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) return emptyList()
        val requirementDirectories = findExistingRequirementDirectories(normalizedRoot, id)
        return requirementDirectories.flatMap { requirementDirectory ->
            Files.walk(requirementDirectory).use { paths ->
                paths.filter { path -> path.fileName?.toString() == REQUIREMENT_MANIFEST }.toList()
            }
        }.distinct()
    }

    /**
     * Validates the canonical location of a process manifest and returns its
     * directory context. A manifest directly under the requirement directory
     * is a legacy desktop identity marker and therefore returns null; callers
     * must still read it to validate identity, but must never use it as the
     * Agent write root.
     */
    fun contextForRequirementManifest(
        root: Path,
        manifestPath: Path,
        id: String,
        subdirectory: String,
        readSprint: (Path) -> RequirementSprintSnapshot? = { null },
    ): RequirementMaterialsDirectoryContext? {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val path = manifestPath.toAbsolutePath().normalize()
        require(path.fileName?.toString() == REQUIREMENT_MANIFEST) {
            "需求资料 manifest 文件名不正确：$manifestPath"
        }
        require(path.startsWith(normalizedRoot)) { "需求资料 manifest 超出资料根目录：$manifestPath" }
        val parent = path.parent ?: throw IllegalArgumentException("需求资料 manifest 缺少父目录：$manifestPath")
        val requirementDirectory = if (parent.fileName?.toString() == subdirectory) {
            parent.parent ?: throw IllegalArgumentException("需求资料 manifest 缺少需求目录：$manifestPath")
        } else {
            generateSequence(parent) { it.parent }
                .firstOrNull { candidate ->
                    val name = candidate.fileName?.toString().orEmpty()
                    name.equals(id, ignoreCase = true) || name.startsWith("$id-", ignoreCase = true)
                }
                ?: throw IllegalArgumentException("需求资料 manifest 必须位于需求资料写入目录：$manifestPath")
        }
        val context = existingDirectoryContext(normalizedRoot, requirementDirectory, id, subdirectory, readSprint)
        if (parent == requirementDirectory) return null
        require(parent == context.writeRoot) {
            "需求资料 manifest 必须位于需求资料写入目录：$manifestPath"
        }
        return context
    }

    /** Validates the paths captured by an Agent plan before it is materialized. */
    fun validatePlannedDirectory(
        root: Path,
        requirementDirectory: Path,
        writeRoot: Path,
        iterationDirectory: Path,
        id: String,
        subdirectory: String,
        sprint: RequirementSprintSnapshot,
    ): RequirementMaterialsDirectoryContext {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalizedRequirement = requirePathInsideMaterialsRoot(requirementDirectory, normalizedRoot, "需求目录")
        val normalizedWriteRoot = requirePathInsideMaterialsRoot(writeRoot, normalizedRoot, "需求资料写入目录")
        val normalizedIteration = requirePathInsideMaterialsRoot(iterationDirectory, normalizedRoot, "迭代目录")
        require(normalizedWriteRoot == normalizedRequirement.resolve(subdirectory)) {
            "需求资料写入目录必须是需求目录的当前资料子目录"
        }
        require(normalizedRequirement.parent == normalizedIteration && normalizedIteration.parent == normalizedRoot) {
            "需求目录必须直接位于资料根目录的 Sprint 子目录"
        }
        require(normalizedRequirement.fileName?.toString()?.equals(id, true) == true ||
            normalizedRequirement.fileName?.toString()?.startsWith("$id-", true) == true) {
            "需求目录名称与需求编号不一致：$normalizedRequirement"
        }
        validateSegment(sprint.label, "Sprint 名称", 120)
        return RequirementMaterialsDirectoryContext(
            requirementDirectory = normalizedRequirement,
            writeRoot = normalizedWriteRoot,
            iterationDirectory = normalizedIteration,
            sprint = sprint,
        )
    }

    /** Shared Sprint cardinality rule used by desktop and Agent flows. */
    fun resolveUniqueActiveSprint(sprints: List<RequirementSprint>): RequirementSprintSnapshot {
        val active = sprints.filter {
            it.status == ACTIVE_SPRINT_STATUS || it.status.equals("In Progress", ignoreCase = true)
        }
        require(active.size == 1) {
            when (active.size) {
                0 -> "需求未关联唯一的进行中 Sprint，已停止创建需求资料目录"
                else -> "需求关联多个进行中 Sprint，已停止创建需求资料目录：${active.joinToString { it.label }}"
            }
        }
        val sprint = active.single()
        return RequirementSprintSnapshot(sprint.id, validateSegment(sprint.label, "Sprint 名称", 120)!!)
    }

    /** Shared requirement-directory naming and path-boundary rule. */
    fun buildRequirementDirectory(root: Path, sprintLabel: String, id: String, folderName: String?): Path {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val safeSprint = validateSegment(sprintLabel, "Sprint 名称", 120)
            ?: throw IllegalArgumentException("Sprint 名称不能为空")
        val safeFolder = validateSegment(folderName, "需求文件夹名称", 80)
            ?: throw IllegalArgumentException("需求文件夹名称不能为空")
        val directoryName = validateSegment("$id-$safeFolder", "需求目录名", 140)
            ?: throw IllegalArgumentException("需求目录名不能为空")
        return normalizedRoot.resolve(safeSprint).resolve(directoryName)
            .normalize().also { require(it.startsWith(normalizedRoot)) { "需求资料目录超出资料根目录" } }
    }

    /**
     * Runs a materials operation under the same lock used by desktop creation.
     * Agent planning/materialization uses this seam too, so the two flows cannot
     * make different reuse decisions while another process is changing the tree.
     */
    fun <T> withMaterialsLock(root: Path, block: () -> T): T {
        val normalized = root.toAbsolutePath().normalize()
        return FileLocking.withExclusiveLock(
            normalized.resolve(LOCK_FILE),
            "需求资料目录正在被另一个 AWM 操作修改：$normalized",
            block,
        )
    }

    /**
     * Validates every process manifest beneath an existing materials directory.
     * A desktop-created directory normally has no manifest, but once one exists
     * it is an identity boundary and must never be silently reused for another
     * Feishu work item. Numeric input can validate the stable work-item id;
     * links additionally validate space and work-item kind.
     */
    fun validateExistingRequirementDirectory(requirementPath: Path, requirementInput: String) {
        val parsed = parseRequirementInput(requirementInput)
            ?: throw IllegalArgumentException("需求输入必须是数字需求编号或 Meegle 详情链接")
        val normalized = requirementPath.toAbsolutePath().normalize()
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) return
        require(Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) { "需求目录不是目录：$normalized" }
        require(!Files.isSymbolicLink(normalized) && !isReparsePoint(normalized)) { "需求目录不能是链接或重解析点：$normalized" }
        Files.walk(normalized).use { paths ->
            paths.forEach { path ->
                if (Files.isSymbolicLink(path) || isReparsePoint(path)) {
                    throw IllegalArgumentException("需求目录包含链接或重解析点，拒绝使用：$path")
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || path.fileName?.toString() != REQUIREMENT_MANIFEST) {
                    return@forEach
                }
                val identity = documentationFiles.readRequirementIdentity(path)
                val id = identity.workItemId
                require(id == parsed.id) {
                    "需求资料目录中的 manifest 与当前需求编号不一致，已停止写入：$path"
                }
                parsed.simpleName?.let { space ->
                    require(identity.space.equals(space, ignoreCase = true)) {
                        "需求资料目录中的 manifest 与当前需求空间不一致，已停止写入：$path"
                    }
                    require(identity.kind.equals(parsed.kind, ignoreCase = true)) {
                        "需求资料目录中的 manifest 与当前需求类型不一致，已停止写入：$path"
                    }
                }
            }
        }
    }

    private fun existingDirectoryContext(
        root: Path,
        requirementDirectory: Path,
        id: String,
        subdirectory: String,
        readSprint: (Path) -> RequirementSprintSnapshot?,
    ): RequirementMaterialsDirectoryContext {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalizedRequirement = requirePathInsideMaterialsRoot(requirementDirectory, normalizedRoot, "需求目录")
        assertDirectory(normalizedRequirement)
        require(!Files.isSymbolicLink(normalizedRequirement) && !isReparsePoint(normalizedRequirement)) {
            "需求目录不能是链接或重解析点：$normalizedRequirement"
        }
        val iterationDirectory = normalizedRequirement.parent
            ?: throw IllegalStateException("需求资料目录缺少 Sprint 目录：$normalizedRequirement")
        require(iterationDirectory.parent?.toAbsolutePath()?.normalize() == normalizedRoot) {
            "需求资料目录必须位于资料根目录的 Sprint 子目录：$normalizedRequirement"
        }
        val directoryName = normalizedRequirement.fileName?.toString().orEmpty()
        require(directoryName.equals(id, ignoreCase = true) || directoryName.startsWith("$id-", ignoreCase = true)) {
            "需求目录名称与需求编号不一致：$normalizedRequirement"
        }
        val sprint = readSprint(iterationDirectory.resolve(ITERATION_MANIFEST))
            ?: RequirementSprintSnapshot("", iterationDirectory.fileName?.toString().orEmpty())
        validateSegment(sprint.label, "Sprint 名称", 120)
        val writeRoot = requirePathInsideMaterialsRoot(normalizedRequirement.resolve(subdirectory), normalizedRoot, "需求资料写入目录")
        return RequirementMaterialsDirectoryContext(normalizedRequirement, writeRoot, iterationDirectory, sprint)
    }

    private fun findRequirementDirectories(root: Path, id: String): List<Path> {
        val matches = mutableListOf<Path>()
        Files.walk(root).use { stream ->
            stream.forEach { path ->
                if (path != root && (Files.isSymbolicLink(path) || isReparsePoint(path))) {
                    throw IllegalArgumentException("目标是链接或重解析点，拒绝使用：$path")
                }
                if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return@forEach
                assertDirectory(path)
                val name = path.fileName?.toString().orEmpty()
                if (path != root && (name.equals(id, true) || name.startsWith("$id-", true))) {
                    matches.add(path.toAbsolutePath().normalize())
                }
            }
        }
        return matches
    }

    private fun ensureDirectory(root: Path, path: Path): Path {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalized = path.toAbsolutePath().normalize()
        require(normalized.startsWith(normalizedRoot)) { "路径不在资料根目录内" }
        var current = normalizedRoot
        for (segment in normalizedRoot.relativize(normalized)) {
            current = current.resolve(segment.toString())
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                assertDirectory(current)
            } else {
                Files.createDirectory(current)
                assertDirectory(current)
            }
        }
        return normalized
    }

    private fun assertDirectory(path: Path) {
        require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { "目标不是目录：$path" }
        require(!Files.isSymbolicLink(path) && !isReparsePoint(path)) { "目标是链接或重解析点，拒绝使用：$path" }
    }

    private fun isReparsePoint(path: Path): Boolean = runCatching {
        Files.readAttributes(path, DosFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isOther
    }.getOrDefault(false)

    private fun containsWorkItemId(element: JsonElement, id: String): Boolean = when (element) {
        is JsonObject -> {
            element.any { (key, value) ->
                if (key.replace("_", "").lowercase(Locale.ROOT) in setOf("itemid", "workitemid")) {
                    typedValue(value) == id || primitiveText(value) == id
                } else containsWorkItemId(value, id)
            }
        }
        is kotlinx.serialization.json.JsonArray -> element.any { containsWorkItemId(it, id) }
        else -> false
    }

    private fun workItemValue(element: JsonElement): String? =
        stringAt(element, listOf("id"))
            ?: (element as? JsonObject)?.get("id")?.let(::typedValue)
            ?: primitiveText(element)

    private fun isMissingWorkItem(stderr: String): Boolean = listOf("not found", "not exist", "不存在", "404")
        .any { marker -> stderr.contains(marker, ignoreCase = true) }

    private fun fieldValues(element: JsonElement, fieldKey: String): List<JsonElement> {
        val fields = arrayAt(element, listOf("work_item_fields"))
        return fields.filter { stringAt(it, listOf("key")) == fieldKey }.flatMap { field ->
            val value = (field as? JsonObject)?.get("value") ?: return@flatMap emptyList()
            when (value) {
                is kotlinx.serialization.json.JsonArray -> value.toList()
                else -> listOf(value)
            }
        }
    }

    private fun mqlRows(element: JsonElement): List<Map<String, String>> {
        val rows = mutableListOf<Map<String, String>>()
        val data = (element as? JsonObject)?.get("data") as? JsonObject ?: return rows
        data.values.forEach { group ->
            (group as? kotlinx.serialization.json.JsonArray)?.forEach { item ->
                val list = (item as? JsonObject)?.get("moql_field_list") as? kotlinx.serialization.json.JsonArray ?: return@forEach
                val row = linkedMapOf<String, String>()
                list.forEach { field ->
                    val fieldObject = field as? JsonObject ?: return@forEach
                    val key = primitiveText(fieldObject["key"]) ?: return@forEach
                    val value = fieldObject["value"] ?: return@forEach
                    typedValue(value)?.let { row[key.lowercase(Locale.ROOT).replace(" ", "_")] = it }
                }
                if (row.isNotEmpty()) rows += row
            }
        }
        return rows
    }

    private fun typedValue(element: JsonElement): String? = when (element) {
        is JsonPrimitive -> element.content
        is JsonObject -> listOf("long_value", "string_value", "text_value", "bool_value").firstNotNullOfOrNull {
            element[it]?.let(::typedValue)
        } ?: (element["label"]?.let(::typedValue))
            ?: (element["key_label_value_list"] as? kotlinx.serialization.json.JsonArray)
                ?.firstOrNull()
                ?.let { item -> (item as? JsonObject)?.get("label")?.let(::typedValue) ?: typedValue(item) }
        else -> null
    }

    private fun primitiveText(element: JsonElement?): String? = element?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    private fun stringAt(element: JsonElement, path: List<String>): String? {
        var current: JsonElement = element
        for (part in path) current = (current as? JsonObject)?.get(part) ?: return null
        return primitiveText(current)?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun arrayAt(element: JsonElement, path: List<String>): List<JsonElement> {
        var current: JsonElement = element
        for (part in path) current = (current as? JsonObject)?.get(part) ?: return emptyList()
        return (current as? kotlinx.serialization.json.JsonArray)?.toList().orEmpty()
    }

    private companion object {
        const val ACTIVE_SPRINT_STATUS = "进行中"
        const val LOCK_FILE = ".awm-requirement-materials.lock"
        const val ITERATION_MANIFEST = ".awm-iteration.json"
        const val REQUIREMENT_MANIFEST = ".awm-requirement.json"
        val json = Json { ignoreUnknownKeys = true }
        val invalidSegmentPattern = Regex("[<>:\"/\\\\|?*\\u0000-\\u001F]")
        val reservedNames = setOf(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
        )
    }
}
