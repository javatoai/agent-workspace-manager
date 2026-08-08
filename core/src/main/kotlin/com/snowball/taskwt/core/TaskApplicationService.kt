package com.snowball.taskwt.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import kotlin.io.path.exists

data class CreateGroupedTaskRequest(
    val folderName: String,
    val featureBranch: String,
    val groupId: String,
    val serviceIds: List<String>,
    val requirementLink: String = "",
    val cloneBranchOverrides: Map<String, String> = emptyMap(),
    val taskNotes: String = "",
)

data class StartupSnapshot(
    val config: AppConfig,
    val tasks: List<TaskManifest>,
    val ignoredLegacyTaskDirectories: List<Path> = emptyList(),
)

/** Loads only persisted JSON. Git and remote integrations are deliberately absent from this startup path. */
class StartupSnapshotLoader(
    private val configurations: ConfigurationRepository = ConfigStore(),
    private val manifests: TaskManifestRepository = ManifestStore(),
) {
    fun load(): StartupSnapshot {
        val config = configurations.load()
        val scan = config.taskRoot?.let(Path::of)?.let(manifests::scan)
        val tasks = scan?.current
            ?.map { it.second }
            ?.sortedByDescending(TaskManifest::updatedAt)
            .orEmpty()
        return StartupSnapshot(config, tasks, scan?.ignoredLegacyDirectories.orEmpty())
    }
}

/**
 * Coordinates task use cases through strategy and storage ports. It owns no Git
 * command construction and no JSON details, which keeps Desktop state orchestration testable.
 */
class TaskApplicationService(
    private val manifests: TaskManifestRepository = ManifestStore(),
    private val provisioning: WorkspaceProvisioningService = WorkspaceProvisioningService(),
    private val agentDocuments: AgentDocuments = AgentDocumentService(),
    private val lifecycle: WorkspaceLifecycle = GitWorkspaceLifecycle(),
    private val operationLock: TaskOperationLock = FileTaskOperationLock(),
    private val clock: Clock = Clock.systemUTC(),
) {
    fun create(config: AppConfig, request: CreateGroupedTaskRequest): TaskManifest {
        val taskRoot = config.taskRoot?.let(Path::of)
            ?: error("尚未配置任务根目录")
        val taskDirectory = taskRoot.resolve(TaskNaming.directoryName(request.folderName.trim()))
        return operationLock.withLock(taskDirectory) { createUnlocked(config, request, taskRoot, taskDirectory) }
    }

    private fun createUnlocked(
        config: AppConfig,
        request: CreateGroupedTaskRequest,
        taskRoot: Path,
        taskDirectory: Path,
    ): TaskManifest {
        val folderName = request.folderName.trim()
        val featureBranch = request.featureBranch.trim()
        require(folderName.isNotEmpty()) { "任务名称不能为空" }
        require(featureBranch.isNotEmpty() && featureBranch.none(Char::isWhitespace)) { "任务分支不能为空或包含空格" }
        require(request.serviceIds.isNotEmpty()) { "至少选择一个服务" }
        require(request.serviceIds.distinct().size == request.serviceIds.size) { "服务不能重复选择" }
        val group = config.group(request.groupId)
        val services = request.serviceIds.map { id ->
            group.services.firstOrNull { it.id == id && it.enabled }
                ?: throw IllegalArgumentException("组 ${group.name} 中不存在或未启用服务：$id")
        }
        val repositories = config.repositories.associateBy(RepositoryConfig::id)
        val taskDirectoryName = TaskNaming.directoryName(folderName)
        taskRoot.toAbsolutePath().normalize().let { normalizedRoot ->
            Files.createDirectories(normalizedRoot)
            require(taskDirectory.toAbsolutePath().normalize().parent == normalizedRoot) { "任务目录必须是任务根目录的直接子目录" }
        }
        // Exclusive creation is a safety boundary: rollback may remove this directory,
        // therefore it must never predate the current request.
        require(!taskDirectory.exists()) { "任务目录已存在，请换一个任务名称：$taskDirectory" }
        Files.createDirectory(taskDirectory)

        val workspaces = mutableListOf<ServiceWorkspace>()
        try {
            services.forEach { service ->
                val repository = repositories[service.repositoryId]
                    ?: error("服务 ${service.displayName} 的仓库配置不存在")
                workspaces += provisioning.provision(
                    WorkspaceProvisionRequest(
                        taskDirectory = taskDirectory,
                        repository = repository,
                        service = service,
                        requestedFeatureBranch = featureBranch,
                        cloneBranchOverride = request.cloneBranchOverrides[service.id],
                    ),
                )
            }
            val now = TaskWtTime.format(Instant.now(clock))
            val manifest = TaskManifest(
                folderName = folderName,
                taskDirectoryName = taskDirectoryName,
                featureBranch = featureBranch,
                requirementLink = request.requirementLink.trim(),
                createdAt = now,
                updatedAt = now,
                status = aggregateStatus(workspaces),
                services = workspaces,
                groupId = group.id,
            )
            manifests.save(taskDirectory, manifest)
            agentDocuments.writeTaskDocument(
                taskDirectory,
                manifest,
                config.repositories.map(RepositoryConfig::toInfo),
                request.taskNotes,
            )
            return manifest
        } catch (error: Throwable) {
            // Roll back only resources created by this request. Existing repositories
            // and branches are never deleted implicitly.
            if (workspaces.isNotEmpty()) {
                val rollbackManifest = TaskManifest(
                    folderName = folderName,
                    taskDirectoryName = taskDirectoryName,
                    featureBranch = featureBranch,
                    createdAt = TaskWtTime.format(Instant.now(clock)),
                    updatedAt = TaskWtTime.format(Instant.now(clock)),
                    status = WorkspaceStatus.FAILED,
                    services = workspaces,
                    groupId = group.id,
                )
                val cleanup = runCatching { lifecycle.removeAll(config, taskDirectory, rollbackManifest, force = true) }
                if (cleanup.isFailure) {
                    throw IllegalStateException(
                        "任务创建失败且自动回滚未完成；为避免 Git 元数据损坏，已保留目录供人工检查：$taskDirectory",
                        cleanup.exceptionOrNull(),
                    ).apply { addSuppressed(error) }
                }
            }
            if (taskDirectory.exists()) deleteRecursively(taskDirectory)
            throw error
        }
    }

    fun refreshAgents(config: AppConfig, taskDirectory: Path): Path = operationLock.withLock(taskDirectory) {
        val manifest = manifests.load(taskDirectory)
        agentDocuments.writeTaskDocument(taskDirectory, manifest, config.repositories.map(RepositoryConfig::toInfo))
    }

    fun saveTaskNotes(config: AppConfig, taskDirectory: Path, notes: String): Path = operationLock.withLock(taskDirectory) {
        val manifest = manifests.load(taskDirectory)
        agentDocuments.writeTaskDocument(
            taskDirectory,
            manifest,
            config.repositories.map(RepositoryConfig::toInfo),
            notes,
        )
    }

    fun inspectDeleteRisk(config: AppConfig, taskDirectory: Path): List<DeleteRisk> = operationLock.withLock(taskDirectory) {
        val manifest = manifests.load(taskDirectory)
        lifecycle.inspectDeleteRisks(config, taskDirectory, manifest)
    }

    fun archive(config: AppConfig, taskDirectory: Path, force: Boolean = false): TaskManifest =
        operationLock.withLock(taskDirectory) {
        val manifest = manifests.load(taskDirectory)
        require(manifest.status != WorkspaceStatus.ARCHIVED) { "任务已经归档" }
        // Validate marker integrity before the destructive removal phase.
        agentDocuments.writeTaskDocument(taskDirectory, manifest, config.repositories.map(RepositoryConfig::toInfo))
        lifecycle.requireArchiveSafe(config, taskDirectory, manifest, force)
        lifecycle.removeAll(config, taskDirectory, manifest, force)
        val updated = manifest.copy(
            updatedAt = TaskWtTime.format(Instant.now(clock)),
            status = WorkspaceStatus.ARCHIVED,
            services = manifest.services.map { it.copy(status = WorkspaceStatus.ARCHIVED) },
        )
        try {
            manifests.save(taskDirectory, updated)
        } catch (saveError: Throwable) {
            val compensation = runCatching { lifecycle.restoreAll(config, taskDirectory, manifest) }
            if (compensation.isFailure) {
                throw compensationFailure("归档清单保存失败，且工作区恢复补偿也失败", saveError, compensation.exceptionOrNull()!!)
            }
            throw saveError
        }
        agentDocuments.writeTaskDocument(taskDirectory, updated, config.repositories.map(RepositoryConfig::toInfo))
        updated
    }

    fun restore(config: AppConfig, taskDirectory: Path): TaskManifest = operationLock.withLock(taskDirectory) {
        val manifest = manifests.load(taskDirectory)
        require(manifest.status == WorkspaceStatus.ARCHIVED) { "只有已归档任务可以恢复" }
        agentDocuments.writeTaskDocument(taskDirectory, manifest, config.repositories.map(RepositoryConfig::toInfo))
        val restored = lifecycle.restoreAll(config, taskDirectory, manifest)
        val updated = manifest.copy(
            updatedAt = TaskWtTime.format(Instant.now(clock)),
            status = aggregateStatus(restored),
            services = restored,
        )
        try {
            manifests.save(taskDirectory, updated)
        } catch (saveError: Throwable) {
            val physical = manifest.copy(services = restored)
            val compensation = runCatching { lifecycle.removeAll(config, taskDirectory, physical, force = true) }
            if (compensation.isFailure) {
                throw compensationFailure("恢复清单保存失败，且移除已恢复工作区的补偿也失败", saveError, compensation.exceptionOrNull()!!)
            }
            throw saveError
        }
        agentDocuments.writeTaskDocument(taskDirectory, updated, config.repositories.map(RepositoryConfig::toInfo))
        updated
    }

    fun delete(config: AppConfig, taskDirectory: Path, forceDiscard: Boolean = false) =
        operationLock.withLock(taskDirectory) {
        val manifest = manifests.load(taskDirectory)
        val risks = lifecycle.inspectDeleteRisks(config, taskDirectory, manifest)
        if (risks.isNotEmpty() && !forceDiscard) error("存在未提交改动，请确认强制丢弃后再删除")
        lifecycle.removeAll(config, taskDirectory, manifest, forceDiscard)
        deleteRecursively(taskDirectory)
    }

    private fun aggregateStatus(workspaces: List<ServiceWorkspace>): WorkspaceStatus = when {
        workspaces.any { it.status == WorkspaceStatus.FAILED } -> WorkspaceStatus.FAILED
        workspaces.any { it.status == WorkspaceStatus.READY_WITH_WARNINGS } -> WorkspaceStatus.READY_WITH_WARNINGS
        else -> WorkspaceStatus.READY
    }

    private fun compensationFailure(message: String, primary: Throwable, compensation: Throwable): Throwable =
        IllegalStateException(message, compensation).apply { addSuppressed(primary) }

    private fun deleteRecursively(path: Path) {
        if (!path.exists()) return
        Files.walk(path).use { entries ->
            entries.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
