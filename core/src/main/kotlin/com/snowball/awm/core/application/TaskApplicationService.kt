package com.snowball.awm.core

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
    val taskNotes: String = "",
    val confirmedBranchReuseKeys: Set<BranchReuseKey> = emptySet(),
)

data class AddGroupedTaskServicesRequest(
    val serviceIds: List<String>,
    val confirmedBranchReuseKeys: Set<BranchReuseKey> = emptySet(),
)

data class StartupSnapshot(
    val config: AppConfig,
    val tasks: List<TaskManifest>,
    val unsupportedTaskDirectories: List<Path> = emptyList(),
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
        return StartupSnapshot(config, tasks, scan?.unsupportedDirectories.orEmpty())
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
    private val branchValidator: BranchReferenceValidator = GitBranchReferenceValidator(),
    private val branchReuseInspector: WorkspaceBranchReuseInspector = WorkspaceBranchReuseInspector(),
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Finds branch reuse decisions needed before task-directory creation begins. */
    fun inspectCreateBranchReuse(config: AppConfig, request: CreateGroupedTaskRequest): List<BranchReuseConflict> {
        val group = config.group(request.groupId)
        val services = request.serviceIds.map { id ->
            group.services.firstOrNull { it.id == id && it.enabled }
                ?: throw IllegalArgumentException("组 ${group.name} 中不存在或未启用服务：$id")
        }
        return inspectBranchReuse(config, services, request.featureBranch)
    }

    /** Finds branch reuse decisions for the same branch used by an existing task. */
    fun inspectAddServicesBranchReuse(
        config: AppConfig,
        taskDirectory: Path,
        request: AddGroupedTaskServicesRequest,
    ): List<BranchReuseConflict> = operationLock.withLock(taskDirectory) {
        val manifest = manifests.load(taskDirectory)
        val group = config.group(manifest.groupId)
        val existingIds = manifest.services.map(ServiceWorkspace::groupServiceId).toSet()
        val services = request.serviceIds.map { id ->
            require(id !in existingIds) { "服务已在任务中：$id" }
            group.services.firstOrNull { it.id == id && it.enabled }
                ?: throw IllegalArgumentException("组 ${group.name} 中不存在或未启用服务：$id")
        }
        inspectBranchReuse(config, services, manifest.featureBranch)
    }

    private fun inspectBranchReuse(
        config: AppConfig,
        services: List<GroupServiceConfig>,
        featureBranch: String,
    ): List<BranchReuseConflict> {
        val repositories = config.repositories.associateBy(RepositoryConfig::id)
        return services.flatMap { service ->
            val repository = repositories[service.repositoryId]
                ?: error("服务 ${service.displayName} 的仓库配置不存在")
            branchReuseInspector.inspect(repository, service, featureBranch)
        }.distinctBy(BranchReuseConflict::key)
    }

    fun create(config: AppConfig, request: CreateGroupedTaskRequest): TaskManifest {
        val taskRoot = config.taskRoot?.let(Path::of)
            ?: error("尚未配置任务根目录")
        val folderName = TaskNaming.requireValidDirectoryName(request.folderName)
        val taskDirectory = taskRoot.resolve(folderName)
        return operationLock.withLock(taskDirectory) {
            createUnlocked(config, request, taskRoot, taskDirectory, folderName)
        }
    }

    private fun createUnlocked(
        config: AppConfig,
        request: CreateGroupedTaskRequest,
        taskRoot: Path,
        taskDirectory: Path,
        folderName: String,
    ): TaskManifest {
        val featureBranch = request.featureBranch.trim()
        require(folderName.isNotEmpty()) { "任务名称不能为空" }
        require(featureBranch.isNotEmpty() && featureBranch.none(Char::isWhitespace)) { "任务分支不能为空或包含空格" }
        require(!BranchPrefixResolver.containsUnresolvedPlaceholder(featureBranch)) {
            "任务分支仍包含未解析的 {num}"
        }
        require(branchValidator.isValid(featureBranch)) { "任务分支不是合法的 Git 分支名：$featureBranch" }
        require(request.serviceIds.isNotEmpty()) { "至少选择一个服务" }
        require(request.serviceIds.distinct().size == request.serviceIds.size) { "服务不能重复选择" }
        val group = config.group(request.groupId)
        val services = request.serviceIds.map { id ->
            group.services.firstOrNull { it.id == id && it.enabled }
                ?: throw IllegalArgumentException("组 ${group.name} 中不存在或未启用服务：$id")
        }
        val repositories = config.repositories.associateBy(RepositoryConfig::id)
        val taskDirectoryName = folderName
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
                        confirmedBranchReuseKeys = request.confirmedBranchReuseKeys,
                    ),
                )
            }
            val now = AwmTime.format(Instant.now(clock))
            val manifest = TaskManifest(
                folderName = folderName,
                taskDirectoryName = taskDirectoryName,
                featureBranch = featureBranch,
                requirementLink = request.requirementLink.trim(),
                createdAt = now,
                updatedAt = now,
                lifecycleStatus = TaskLifecycleStatus.ACTIVE,
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
                    createdAt = AwmTime.format(Instant.now(clock)),
                    updatedAt = AwmTime.format(Instant.now(clock)),
                    lifecycleStatus = TaskLifecycleStatus.ACTIVE,
                    services = workspaces,
                    groupId = group.id,
                )
                val cleanup = runCatching {
                    rollbackProvisionedWorkspaces(config, taskDirectory, rollbackManifest)
                }
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

    /** Rolls back only paths and branches marked as created by this provisioning transaction. */
    private fun rollbackProvisionedWorkspaces(
        config: AppConfig,
        taskDirectory: Path,
        manifest: TaskManifest,
    ) {
        val group = config.group(manifest.groupId)
        val repositories = config.repositories.associateBy(RepositoryConfig::id)
        manifest.services
            .groupBy(ServiceWorkspace::groupServiceId)
            .values
            .toList()
            .asReversed()
            .forEach { workspaces ->
                val workspace = workspaces.first()
                val service = group.services.firstOrNull { it.id == workspace.groupServiceId }
                    ?: error("回滚服务配置不存在：${workspace.groupServiceId}")
                val repository = repositories[workspace.repositoryId]
                    ?: error("回滚仓库配置不存在：${workspace.repositoryId}")
                provisioning.rollback(
                    WorkspaceProvisionRequest(
                        taskDirectory = taskDirectory,
                        repository = repository,
                        service = service,
                        requestedFeatureBranch = manifest.featureBranch,
                    ),
                    workspaces,
                )
            }
    }

    fun refreshAgents(config: AppConfig, taskDirectory: Path): Path = operationLock.withLock(taskDirectory) {
        val manifest = manifests.load(taskDirectory)
        agentDocuments.writeTaskDocument(taskDirectory, manifest, config.repositories.map(RepositoryConfig::toInfo))
    }

    /** Adds configured services without allowing a task to move between groups. */
    fun addServices(
        config: AppConfig,
        taskDirectory: Path,
        request: AddGroupedTaskServicesRequest,
    ): TaskManifest = operationLock.withLock(taskDirectory) {
        val manifest = manifests.load(taskDirectory)
        require(request.serviceIds.isNotEmpty()) { "至少选择一个服务" }
        require(request.serviceIds.distinct().size == request.serviceIds.size) { "服务不能重复选择" }
        val group = config.group(manifest.groupId)
        val existingIds = manifest.services.map(ServiceWorkspace::groupServiceId).toSet()
        val services = request.serviceIds.map { serviceId ->
            require(serviceId !in existingIds) { "服务已在任务中：$serviceId" }
            group.services.firstOrNull { it.id == serviceId && it.enabled }
                ?: throw IllegalArgumentException("组 ${group.name} 中不存在或未启用服务：$serviceId")
        }
        val repositories = config.repositories.associateBy(RepositoryConfig::id)
        val created = mutableListOf<Pair<WorkspaceProvisionRequest, List<ServiceWorkspace>>>()
        var manifestCommitted = false
        try {
            services.forEach { service ->
                val repository = repositories[service.repositoryId]
                    ?: error("服务 ${service.displayName} 的仓库配置不存在")
                val provisionRequest = WorkspaceProvisionRequest(
                    taskDirectory = taskDirectory,
                    repository = repository,
                    service = service,
                    requestedFeatureBranch = manifest.featureBranch,
                    confirmedBranchReuseKeys = request.confirmedBranchReuseKeys,
                )
                created += provisionRequest to provisioning.provision(provisionRequest)
            }
            val added = created.flatMap { it.second }
            val updated = manifest.copy(
                updatedAt = AwmTime.format(Instant.now(clock)),
                services = manifest.services + added,
            )
            manifests.save(taskDirectory, updated)
            manifestCommitted = true
            agentDocuments.writeTaskDocument(
                taskDirectory,
                updated,
                config.repositories.map(RepositoryConfig::toInfo),
            )
            updated
        } catch (error: Throwable) {
            val rollbackFailures = mutableListOf<Throwable>()
            if (manifestCommitted) {
                runCatching {
                    manifests.save(taskDirectory, manifest)
                    agentDocuments.writeTaskDocument(
                        taskDirectory,
                        manifest,
                        config.repositories.map(RepositoryConfig::toInfo),
                    )
                }.onFailure(rollbackFailures::add)
            }
            created.asReversed().forEach { (provisionRequest, workspaces) ->
                runCatching { provisioning.rollback(provisionRequest, workspaces) }
                    .onFailure(rollbackFailures::add)
            }
            if (rollbackFailures.isNotEmpty()) {
                throw IllegalStateException(
                    "追加服务失败，且自动回滚未完整完成；已保留任务数据供人工检查：$taskDirectory",
                    error,
                ).apply { rollbackFailures.forEach(::addSuppressed) }
            }
            throw error
        }
    }

    /** Re-provisions complete failed service entries while keeping successful workspaces untouched. */
    fun retryFailedServices(
        config: AppConfig,
        taskDirectory: Path,
        serviceIds: List<String>? = null,
    ): TaskManifest = operationLock.withLock(taskDirectory) {
        val manifest = manifests.load(taskDirectory)
        val failedIds = manifest.services
            .filter { it.health == WorkspaceHealth.FAILED }
            .map(ServiceWorkspace::groupServiceId)
            .filter(String::isNotBlank)
            .toSet()
        val selected = serviceIds?.toSet() ?: failedIds
        require(selected.isNotEmpty() && selected.all { it in failedIds }) { "没有可重试的失败服务" }
        val group = config.group(manifest.groupId)
        val repositories = config.repositories.associateBy(RepositoryConfig::id)
        val created = mutableListOf<Pair<WorkspaceProvisionRequest, List<ServiceWorkspace>>>()
        var manifestCommitted = false
        try {
            selected.forEach { serviceId ->
                val service = group.services.firstOrNull { it.id == serviceId }
                    ?: error("失败服务已不在组配置中：$serviceId")
                val repository = repositories[service.repositoryId]
                    ?: error("服务 ${service.displayName} 的仓库配置不存在")
                val provisionRequest = WorkspaceProvisionRequest(
                    taskDirectory = taskDirectory,
                    repository = repository,
                    service = service,
                    requestedFeatureBranch = manifest.featureBranch,
                )
                created += provisionRequest to provisioning.provision(provisionRequest)
            }
            val replacements = created.flatMap { it.second }
            val retained = manifest.services.filterNot { it.groupServiceId in selected }
            val updated = manifest.copy(
                updatedAt = AwmTime.format(Instant.now(clock)),
                services = retained + replacements,
            )
            manifests.save(taskDirectory, updated)
            manifestCommitted = true
            agentDocuments.writeTaskDocument(
                taskDirectory,
                updated,
                config.repositories.map(RepositoryConfig::toInfo),
            )
            updated
        } catch (error: Throwable) {
            val rollbackFailures = mutableListOf<Throwable>()
            if (manifestCommitted) {
                runCatching {
                    manifests.save(taskDirectory, manifest)
                    agentDocuments.writeTaskDocument(
                        taskDirectory,
                        manifest,
                        config.repositories.map(RepositoryConfig::toInfo),
                    )
                }.onFailure(rollbackFailures::add)
            }
            created.asReversed().forEach { (request, workspaces) ->
                runCatching { provisioning.rollback(request, workspaces) }.onFailure(rollbackFailures::add)
            }
            if (rollbackFailures.isNotEmpty()) {
                throw IllegalStateException(
                    "重试失败，且自动回滚未完整完成；已保留任务数据供人工检查：$taskDirectory",
                    error,
                ).apply { rollbackFailures.forEach(::addSuppressed) }
            }
            throw error
        }
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
        require(manifest.lifecycleStatus != TaskLifecycleStatus.ARCHIVED) { "任务已经归档" }
        // Archive is a navigation classification only. Workspaces remain intact
        // so an archived task can still be opened and resumed without Git work.
        val updated = manifest.copy(
            updatedAt = AwmTime.format(Instant.now(clock)),
            lifecycleStatus = TaskLifecycleStatus.ARCHIVED,
            services = manifest.services,
        )
        try {
            manifests.save(taskDirectory, updated)
        } catch (saveError: Throwable) {
            throw saveError
        }
        agentDocuments.writeTaskDocument(taskDirectory, updated, config.repositories.map(RepositoryConfig::toInfo))
        updated
    }

    fun restore(config: AppConfig, taskDirectory: Path): TaskManifest = operationLock.withLock(taskDirectory) {
        val manifest = manifests.load(taskDirectory)
        require(manifest.lifecycleStatus == TaskLifecycleStatus.ARCHIVED) { "只有已归档任务可以恢复" }
        val updated = manifest.copy(
            updatedAt = AwmTime.format(Instant.now(clock)),
            lifecycleStatus = TaskLifecycleStatus.ACTIVE,
            services = manifest.services,
        )
        try {
            manifests.save(taskDirectory, updated)
        } catch (saveError: Throwable) {
            throw saveError
        }
        agentDocuments.writeTaskDocument(taskDirectory, updated, config.repositories.map(RepositoryConfig::toInfo))
        updated
    }

    fun delete(config: AppConfig, taskDirectory: Path, forceDiscard: Boolean = false) =
        operationLock.withLock(taskDirectory) {
        val manifest = manifests.load(taskDirectory)
        val risks = lifecycle.inspectDeleteRisks(config, taskDirectory, manifest)
        require(risks.none { it.statusCheckError != null }) {
            "存在无法安全验证的工作区，请先修复其 Git 身份或路径后再删除任务"
        }
        if (risks.isNotEmpty() && !forceDiscard) error("存在未提交改动，请确认强制丢弃后再删除")
        lifecycle.removeAll(config, taskDirectory, manifest, forceDiscard)
        deleteRecursively(taskDirectory)
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
