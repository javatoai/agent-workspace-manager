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
 * Creates (or reuses) the non-source-code directory used for requirement notes and scripts.
 *
 * The service deliberately has no deletion operation.  A failed CLI lookup is returned as a
 * value, allowing task creation to continue and the UI to offer a later retry.
 */
class RequirementMaterialsService(
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val meegleExecutable: MeegleExecutable = MeegleExecutable.pathFallback(),
    private val commandTimeout: Duration = Duration.ofSeconds(20),
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

            val existing = findRequirementDirectories(root, requirementId)
            if (existing.size > 1) {
                return RequirementMaterialsResult.Failed(
                    requirementId,
                    "同一需求编号匹配到多个需求目录，未自动选择",
                    existing,
                )
            }
            if (existing.singleOrNull() != null) {
                val requirementPath = existing.single()
                val writeRoot = ensureDirectory(root, requirementPath.resolve(subdirectory))
                return RequirementMaterialsResult.Ready(
                    requirementId = requirementId,
                    requirementPath = requirementPath,
                    writeRoot = writeRoot,
                    status = RequirementMaterialsResult.Ready.Status.REUSED,
                )
            }

            val project = resolveProject(parsed, requirementId, request.projects)
                ?: return RequirementMaterialsResult.Failed(
                    requirementId,
                    lastFailure ?: if (request.projects.isEmpty()) "未配置 Meegle 项目" else "未能唯一匹配需求所属的 Meegle 项目",
                )
            val sprint = resolveActiveSprint(project, requirementId)
                ?: return RequirementMaterialsResult.Failed(requirementId, lastFailure ?: "需求没有关联当前进行中的 Sprint")

            val sprintName = validateSegment(sprint.name, "Sprint 名称", 120)
                ?: return RequirementMaterialsResult.Failed(requirementId, "Sprint 名称不能为空")
            val requirementName = validateSegment("$requirementId-$folder", "需求目录名", 140)
                ?: return RequirementMaterialsResult.Failed(requirementId, "需求目录名不能为空")
            val requirementPath = root.resolve(sprintName).resolve(requirementName)
            val writeRoot = ensureDirectory(root, requirementPath.resolve(subdirectory))
            RequirementMaterialsResult.Ready(
                requirementId = requirementId,
                requirementPath = requirementPath,
                writeRoot = writeRoot,
                status = RequirementMaterialsResult.Ready.Status.CREATED,
            )
        } catch (error: Exception) {
            RequirementMaterialsResult.Failed(requirementId, "需求资料目录创建失败：${error.message ?: error::class.simpleName}")
        }
    }

    private var lastFailure: String? = null

    private data class ParsedRequirement(val id: String, val simpleName: String?)
    private data class ProjectMatch(val project: MeegleProjectConfig)
    private data class SprintMatch(val id: String, val name: String)

    private fun parseRequirementInput(raw: String): ParsedRequirement? {
        val value = raw.trim().trimEnd(',', '，', '。', ';', '；')
        if (value.matches(Regex("\\d+"))) return ParsedRequirement(value, null)
        val link = FeishuWorkItemLink.parse(value) ?: return null
        return ParsedRequirement(link.workItemId, link.space)
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
        return when {
            active.size == 1 -> SprintMatch(active.single()["work_item_id"]!!, active.single()["name"].orEmpty())
            active.isEmpty() -> { lastFailure = "需求没有关联当前进行中的 Sprint"; null }
            else -> { lastFailure = "需求同时关联多个当前进行中的 Sprint"; null }
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
        val json = Json { ignoreUnknownKeys = true }
        val invalidSegmentPattern = Regex("[<>:\"/\\\\|?*\\u0000-\\u001F]")
        val reservedNames = setOf(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
        )
    }
}
