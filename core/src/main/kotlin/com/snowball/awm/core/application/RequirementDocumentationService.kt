package com.snowball.awm.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

@Serializable
data class RequirementIdentity(
    val space: String,
    val kind: String,
    val workItemId: String,
) {
    init {
        require(space.matches(Regex("[A-Za-z0-9_-]+"))) { "需求空间不合法" }
        require(kind in setOf("userstory", "technical", "bug", "othertask")) { "需求类型不合法" }
        require(workItemId.matches(Regex("\\d+"))) { "需求 ID 不合法" }
    }

    val stableKey: String get() = "$space/$kind/$workItemId"
}

@Serializable
data class RequirementDocumentationManifest(
    val formatVersion: Int = 1,
    val identity: RequirementIdentity,
    val requirementTitle: String,
    val sprint: RequirementSprintSnapshot,
    val directoryName: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class RequirementSprintSnapshot(
    val id: String,
    val label: String,
)

@Serializable
data class IterationDocumentationManifest(
    val formatVersion: Int = 1,
    val sprint: RequirementSprintSnapshot,
    val createdAt: String,
)

@Serializable
data class RequirementDocumentationIndexEntry(
    val identity: RequirementIdentity,
    /** Relative to the configured documentation root; never a user-supplied absolute path. */
    val requirementDirectory: String,
    val sprint: RequirementSprintSnapshot,
    val updatedAt: String,
)

@Serializable
data class RequirementDocumentationPlan(
    val identity: RequirementIdentity,
    val requirementTitle: String,
    val sprint: RequirementSprintSnapshot,
    val documentationDirectory: String,
    val iterationDirectory: String,
    val reusedHistoricalDirectory: Boolean,
)

data class RequirementDocumentationMaterialization(
    val plan: RequirementDocumentationPlan,
    val agentContext: AgentTaskContext,
    val createdRequirementDirectory: Boolean,
)

/**
 * Creates the documentation hierarchy only for the Agent CLI path. History is
 * resolved before Meegle by the persisted manifest identity, never by a fuzzy
 * title or a directory-name guess.
 */
class RequirementDocumentationService(
    private val iterations: RequirementIterationProvider = MeegleRequirementIterationProvider(),
    private val metadata: RequirementMetadataProvider = MeegleRequirementMetadataProvider(),
    private val clock: Clock = Clock.systemUTC(),
    private val json: Json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false },
    private val indexJson: Json = Json { encodeDefaults = true; ignoreUnknownKeys = false },
) {
    fun plan(config: AppConfig, requirementLink: String, requestedTitle: String?): RequirementDocumentationPlan {
        val root = documentationRoot(config)
        val parsed = FeishuWorkItemLink.parse(requirementLink)
            ?: throw IllegalArgumentException("需求链接不是支持的飞书项目工作项链接")
        val identity = RequirementIdentity(parsed.space, parsed.kind, parsed.workItemId)
        val historical = findHistorical(root, identity)
        require(historical.size <= 1) {
            "本地需求文档发现多个历史目录，无法安全复用：${historical.joinToString { it.directory.toString() }}"
        }
        historical.singleOrNull()?.let { existing ->
            return RequirementDocumentationPlan(
                identity = identity,
                requirementTitle = existing.manifest.requirementTitle,
                sprint = existing.manifest.sprint,
                documentationDirectory = existing.directory.toString(),
                iterationDirectory = existing.directory.parent.toString(),
                reusedHistoricalDirectory = true,
            )
        }

        val projectKey = config.meegleProjects
            .firstOrNull { it.simpleName.equals(parsed.space, ignoreCase = true) }
            ?.projectKey
            ?: parsed.projectKey
            ?: throw IllegalStateException("需求空间 ${parsed.space} 未配置 Meegle project key")
        val active = iterations.resolve(requirementLink, projectKey)
            .filter { it.status == ACTIVE_SPRINT_STATUS }
        require(active.size == 1) {
            when (active.size) {
                0 -> "需求未关联唯一的进行中 Sprint，已停止创建文档目录"
                else -> "需求关联多个进行中 Sprint，已停止创建文档目录：${active.joinToString { it.label }}"
            }
        }
        val sprint = active.single()
        val safeSprintLabel = TaskNaming.requireValidDirectoryName(sprint.label)
        val title = requestedTitle?.trim()?.takeIf(String::isNotBlank)
            ?: metadata.fetch(requirementLink, projectKey)?.title?.trim()?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("需要提供需求中文简写，或确保本地 Meegle 能读取需求标题")
        val directoryName = TaskNaming.directoryName("${identity.workItemId}-$title")
        val iterationDirectory = root.resolve(safeSprintLabel).normalize().inside(root, "迭代目录")
        val documentationDirectory = iterationDirectory.resolve(directoryName).normalize().inside(root, "需求文档目录")
        return RequirementDocumentationPlan(
            identity = identity,
            requirementTitle = title,
            sprint = RequirementSprintSnapshot(sprint.id, safeSprintLabel),
            documentationDirectory = documentationDirectory.toString(),
            iterationDirectory = iterationDirectory.toString(),
            reusedHistoricalDirectory = false,
        )
    }

    fun materialize(config: AppConfig, plan: RequirementDocumentationPlan): RequirementDocumentationMaterialization {
        val root = documentationRoot(config)
        val expectedDirectory = Path.of(plan.documentationDirectory).toAbsolutePath().normalize().inside(root, "需求文档目录")
        val expectedIteration = Path.of(plan.iterationDirectory).toAbsolutePath().normalize().inside(root, "迭代目录")
        return FileLocking.withExclusiveLock(
            root.resolve(LOCK_FILE),
            "需求过程文档正在被另一个 AWM Agent 操作修改：$root",
        ) {
            val current = findHistorical(root, plan.identity)
            require(current.size <= 1) {
                "本地需求文档发现多个历史目录，无法安全复用：${current.joinToString { it.directory.toString() }}"
            }
            current.singleOrNull()?.let { existing ->
                return@withExclusiveLock materialization(existing.directory, existing.manifest, created = false)
            }
            require(!plan.reusedHistoricalDirectory) { "计划中的历史需求文档已不存在，请重新生成计划" }
            require(!expectedDirectory.exists()) { "需求文档目录已被创建或占用，请重新生成计划：$expectedDirectory" }
            ensureIteration(expectedIteration, plan.sprint)
            Files.createDirectory(expectedDirectory)
            val now = AwmTime.format(Instant.now(clock))
            val manifest = RequirementDocumentationManifest(
                identity = plan.identity,
                requirementTitle = plan.requirementTitle,
                sprint = plan.sprint,
                directoryName = expectedDirectory.name,
                createdAt = now,
                updatedAt = now,
            )
            writeAtomically(expectedDirectory.resolve(REQUIREMENT_MANIFEST), json.encodeToString(manifest))
            writeAtomically(expectedDirectory.resolve(REQUIREMENT_OVERVIEW), renderRequirementOverview(manifest))
            appendIterationOverview(expectedIteration, manifest)
            updateIndex(root, expectedDirectory, manifest)
            materialization(expectedDirectory, manifest, created = true)
        }
    }

    private fun materialization(
        directory: Path,
        manifest: RequirementDocumentationManifest,
        created: Boolean,
    ): RequirementDocumentationMaterialization {
        val agentContext = AgentTaskContext(
            documentationDirectory = directory.toString(),
            iterationLabel = manifest.sprint.label,
        )
        return RequirementDocumentationMaterialization(
            plan = RequirementDocumentationPlan(
                identity = manifest.identity,
                requirementTitle = manifest.requirementTitle,
                sprint = manifest.sprint,
                documentationDirectory = directory.toString(),
                iterationDirectory = directory.parent.toString(),
                reusedHistoricalDirectory = !created,
            ),
            agentContext = agentContext,
            createdRequirementDirectory = created,
        )
    }

    private fun ensureIteration(directory: Path, sprint: RequirementSprintSnapshot) {
        val manifestPath = directory.resolve(ITERATION_MANIFEST)
        if (directory.exists()) {
            require(directory.isDirectory()) { "迭代路径不是目录：$directory" }
            require(!Files.isSymbolicLink(directory)) { "迭代目录不能是符号链接：$directory" }
            require(manifestPath.isRegularFile()) { "迭代目录不是 AWM 管理的目录，已停止写入：$directory" }
            val existing = decodeIteration(manifestPath)
            require(existing.sprint == sprint) { "迭代目录与当前 Sprint 不匹配，已停止写入：$directory" }
            return
        }
        Files.createDirectory(directory)
        val iteration = IterationDocumentationManifest(sprint = sprint, createdAt = AwmTime.format(Instant.now(clock)))
        writeAtomically(manifestPath, json.encodeToString(iteration))
        writeAtomically(
            directory.resolve(ITERATION_OVERVIEW),
            "# ${sprint.label} 迭代任务总览\n\n" +
                "本目录由 AWM Agent CLI 创建；每个需求的过程文档位于其独立子目录。\n",
        )
    }

    private fun appendIterationOverview(directory: Path, manifest: RequirementDocumentationManifest) {
        val overview = directory.resolve(ITERATION_OVERVIEW)
        val marker = "<!-- AWM:REQUIREMENT:${manifest.identity.stableKey} -->"
        val current = Files.readString(overview)
        if (current.contains(marker)) return
        val line = buildString {
            appendLine()
            appendLine(marker)
            appendLine("- [${manifest.identity.workItemId}-${manifest.requirementTitle}](${manifest.directoryName}/$REQUIREMENT_OVERVIEW)")
        }
        writeAtomically(overview, current.trimEnd() + "\n" + line)
    }

    private fun renderRequirementOverview(manifest: RequirementDocumentationManifest): String = buildString {
        appendLine("# ${manifest.identity.workItemId}-${manifest.requirementTitle}")
        appendLine()
        appendLine("- 需求：`${manifest.identity.stableKey}`")
        appendLine("- Sprint：`${manifest.sprint.label}`")
        appendLine("- 创建时间：${manifest.createdAt}")
        appendLine()
        appendLine("## 过程文档")
        appendLine()
        appendLine("在本目录维护需求分析、技术方案、验收、风险和交接资料。代码仓库自身的 README、ADR、API 文档仍留在对应 Worktree。")
    }

    private fun findHistorical(root: Path, identity: RequirementIdentity): List<HistoricalRequirement> {
        val indexMatches = readIndex(root).filter { it.identity == identity }
        val indexed = indexMatches.map { entry ->
            val directory = root.resolve(entry.requirementDirectory).normalize().inside(root, "需求文档索引路径")
            require(directory.resolve(REQUIREMENT_MANIFEST).isRegularFile()) {
                "需求文档索引指向的 manifest 缺失：$directory"
            }
            val manifest = decodeRequirement(directory.resolve(REQUIREMENT_MANIFEST))
            require(manifest.identity == identity && manifest.sprint == entry.sprint) {
                "需求文档索引与 manifest 不一致：$directory"
            }
            HistoricalRequirement(directory, manifest)
        }
        // The index accelerates the direct lookup but is not an authority that
        // can hide a second manifest copied or created outside a prior run.
        val scanned = scanRequirementManifests(root, identity)
        return (indexed + scanned).distinctBy { it.directory.toAbsolutePath().normalize().toString() }
    }

    private fun scanRequirementManifests(root: Path, identity: RequirementIdentity): List<HistoricalRequirement> {
        if (!root.exists()) return emptyList()
        return Files.walk(root, 3).use { paths ->
            paths.filter { path -> path.name == REQUIREMENT_MANIFEST }
                .map { path ->
                    val manifest = decodeRequirement(path)
                    HistoricalRequirement(path.parent, manifest)
                }
                .filter { it.manifest.identity == identity }
                .toList()
        }
    }

    private fun readIndex(root: Path): List<RequirementDocumentationIndexEntry> {
        val index = root.resolve(INDEX_FILE)
        if (!index.exists()) return emptyList()
        return Files.readAllLines(index)
            .filter(String::isNotBlank)
            .mapIndexed { indexNumber, line ->
                runCatching { indexJson.decodeFromString<RequirementDocumentationIndexEntry>(line) }
                    .getOrElse { throw IllegalStateException("需求文档索引第 ${indexNumber + 1} 行损坏", it) }
            }
    }

    private fun updateIndex(root: Path, directory: Path, manifest: RequirementDocumentationManifest) {
        val relative = root.relativize(directory).toString()
        val next = (readIndex(root).filterNot { it.identity == manifest.identity } + RequirementDocumentationIndexEntry(
            identity = manifest.identity,
            requirementDirectory = relative,
            sprint = manifest.sprint,
            updatedAt = manifest.updatedAt,
        )).sortedBy { it.identity.stableKey }
        writeAtomically(root.resolve(INDEX_FILE), next.joinToString("\n") { indexJson.encodeToString(it) } + "\n")
    }

    private fun decodeRequirement(path: Path): RequirementDocumentationManifest = runCatching {
        json.decodeFromString<RequirementDocumentationManifest>(Files.readString(path))
    }.getOrElse { throw IllegalStateException("需求文档 manifest 损坏：$path", it) }

    private fun decodeIteration(path: Path): IterationDocumentationManifest = runCatching {
        json.decodeFromString<IterationDocumentationManifest>(Files.readString(path))
    }.getOrElse { throw IllegalStateException("迭代 manifest 损坏：$path", it) }

    private fun documentationRoot(config: AppConfig): Path {
        val configured = config.requirementDocumentationRoot?.let(Path::of)?.toAbsolutePath()?.normalize()
            ?: throw IllegalStateException("尚未配置需求过程文档根目录；请在 AWM 设置 > 路径设置中配置")
        val root = runCatching { configured.toRealPath() }
            .getOrElse { throw IllegalStateException("无法解析需求过程文档根目录：$configured", it) }
        require(root.isDirectory()) { "需求过程文档根目录不存在或不是目录：$root" }
        require(Files.isWritable(root)) { "需求过程文档根目录不可写：$root" }
        return root
    }

    private fun Path.inside(root: Path, label: String): Path {
        require(startsWith(root)) { "$label 超出配置的需求过程文档根目录：$this" }
        return this
    }

    private data class HistoricalRequirement(
        val directory: Path,
        val manifest: RequirementDocumentationManifest,
    )

    private fun writeAtomically(target: Path, content: String) {
        target.parent.createDirectories()
        val temporary = Files.createTempFile(target.parent, ".${target.fileName}-", ".tmp")
        Files.writeString(temporary, content, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val ACTIVE_SPRINT_STATUS = "进行中"
        const val LOCK_FILE = ".awm-requirement-documents.lock"
        const val INDEX_FILE = ".awm-requirement-index.jsonl"
        const val ITERATION_MANIFEST = ".awm-iteration.json"
        const val REQUIREMENT_MANIFEST = ".awm-requirement.json"
        const val ITERATION_OVERVIEW = "00-迭代任务总览.md"
        const val REQUIREMENT_OVERVIEW = "00-需求总览.md"
    }
}
