package com.snowball.awm.core

import java.nio.file.Files
import java.nio.file.Path

data class WorkspaceProvisionRequest(
    val taskDirectory: Path,
    val repository: RepositoryConfig,
    val service: GroupServiceConfig,
    val requestedFeatureBranch: String? = null,
    /** Explicit acknowledgement for pre-existing local or remote feature branches. */
    val confirmedBranchReuseKeys: Set<BranchReuseKey> = emptySet(),
    /** Optional per-module final task branches. Missing entries use TaskBranchNaming defaults. */
    val moduleBranches: Map<String, String> = emptyMap(),
)

/** Identifies one local Git branch in a configured repository. */
data class BranchReuseKey(
    val repositoryId: String,
    val branch: String,
    /** Snapshot from preflight; null is retained for source-compatible callers. */
    val stateFingerprint: String? = null,
)

private fun branchReuseFingerprint(
    localExists: Boolean,
    remoteRefs: List<String>,
    worktrees: List<WorktreeRecord>,
): String = listOf(
    "local=$localExists",
    "remotes=${remoteRefs.sorted().joinToString(",")}",
    "worktrees=${worktrees.sortedBy { it.path.toString() }.joinToString(",") { "${it.path}|${it.locked}" }}",
).joinToString(";")

/** Read-only conflict information shown before a task creates any directories. */
data class BranchReuseConflict(
    val key: BranchReuseKey,
    val serviceId: String,
    val serviceName: String,
    val moduleName: String,
    val localExists: Boolean,
    val remoteRefs: List<String>,
    val occupiedWorktreePaths: List<String>,
    val lockedWorktreePaths: List<String> = emptyList(),
    private val forceAttachRequired: Boolean = occupiedWorktreePaths.isNotEmpty(),
) {
    val requiresForceAttach: Boolean get() = forceAttachRequired
}

interface WorkspaceProvisioner {
    val strategy: WorkspaceStrategy
    fun provision(request: WorkspaceProvisionRequest): List<ServiceWorkspace>

    /**
     * Compensates workspaces created by one provisioning request. Implementations
     * must only remove paths and branches derived from that request; repositories
     * and pre-existing branches are outside this rollback boundary.
     */
    fun rollback(request: WorkspaceProvisionRequest, workspaces: List<ServiceWorkspace>) = Unit
}

/** Deterministic on-disk layout shared by provisioning and live AGENTS previews. */
object WorkspaceLayout {
    private fun serviceDirectoryBase(service: GroupServiceConfig): String =
        TaskNaming.directoryName(service.displayName)

    fun standardDirectoryName(
        service: GroupServiceConfig,
        module: ServiceModuleConfig,
        moduleCount: Int,
    ): String {
        val base = serviceDirectoryBase(service)
        return if (moduleCount == 1) {
            base
        } else {
            "$base-${StandardWorktreeModuleNaming.directorySegment(StandardWorktreeModuleNaming.effectiveName(module))}"
        }
    }

    fun cloneDirectoryName(service: GroupServiceConfig, module: IndependentCloneModuleConfig): String =
        "${serviceDirectoryBase(service)}-${TaskNaming.directoryName(module.name.ifBlank { RemoteBranchRef.parse(module.branch).branch.substringAfterLast('/') })}"
}

/**
 * Refreshes and reports only feature-branch conflicts. It deliberately does
 * not create task or worktree directories, so the UI can ask for consent
 * before entering a mutating task operation.
 */
class WorkspaceBranchReuseInspector(
    private val git: GitClient = GitClient(),
    private val repositoryLock: RepositoryOperationLock = RepositoryOperationLock(),
) {
    fun inspect(
        repository: RepositoryConfig,
        service: GroupServiceConfig,
        requestedFeatureBranch: String,
        moduleBranches: Map<String, String> = emptyMap(),
    ): List<BranchReuseConflict> {
        if (service.strategy == WorkspaceStrategy.INDEPENDENT_CLONE) return emptyList()
        val repositoryPath = Path.of(repository.rootPath).toAbsolutePath().normalize()
        val commonDirectory = git.commonDirectory(repositoryPath).toAbsolutePath().normalize()
        return repositoryLock.withLock(commonDirectory) {
            inspectLocked(repository, service, requestedFeatureBranch, moduleBranches, repositoryPath)
        }
    }

    private fun inspectLocked(
        repository: RepositoryConfig,
        service: GroupServiceConfig,
        requestedFeatureBranch: String,
        moduleBranches: Map<String, String>,
        repositoryPath: Path,
    ): List<BranchReuseConflict> {
        require(git.topLevel(repositoryPath).toAbsolutePath().normalize() == repositoryPath) {
            "配置的仓库路径已不再是 Git 顶层目录：$repositoryPath"
        }
        require(
            git.commonDirectory(repositoryPath).toAbsolutePath().normalize() ==
                Path.of(repository.gitCommonDirectory).toAbsolutePath().normalize(),
        ) { "配置的仓库 Git 身份已变化：$repositoryPath" }

        git.pruneWorktrees(repositoryPath)
        val branches = TaskBranchNaming.resolve(requestedFeatureBranch.trim(), service.modules, moduleBranches)
        StandardWorktreeModuleNaming.requireValid(service.modules)
        return service.modules.mapNotNull { module ->
            val branch = branches.getValue(module.id)
            git.fetch(repositoryPath, module.baseRemote)
            val featureRemotes = listOf(tagRemote(module)).distinct()
            featureRemotes.filter { it != module.baseRemote }.forEach { git.fetch(repositoryPath, it) }

            val localExists = git.refExists(repositoryPath, "refs/heads/$branch")
            val remoteRefs = featureRemotes
                .filter { git.refExists(repositoryPath, "refs/remotes/$it/$branch") }
                .map { "$it/$branch" }
            if (!localExists && remoteRefs.isEmpty()) return@mapNotNull null

            val occupied = if (localExists) {
                git.worktrees(repositoryPath)
                    .filter { it.branch == branch }
            } else {
                emptyList()
            }
            BranchReuseConflict(
                key = BranchReuseKey(
                    repository.id,
                    branch,
                    branchReuseFingerprint(localExists, remoteRefs, occupied),
                ),
                serviceId = service.id,
                serviceName = service.displayName,
                moduleName = ModuleDisplayNaming.resolve(module.name, service.displayName, module.baseRef, service.modules.size),
                localExists = localExists,
                remoteRefs = remoteRefs,
                occupiedWorktreePaths = occupied.map { it.path.toString() },
                lockedWorktreePaths = occupied.filter { it.locked }.map { it.path.toString() },
                forceAttachRequired = service.strategy == WorkspaceStrategy.STANDARD_WORKTREE && occupied.isNotEmpty(),
            )
        }
    }
}

private data class CreatedStandardWorktree(
    val target: Path,
    val branch: String,
    val branchCreatedByTask: Boolean,
)

class StandardWorktreeProvisioner(
    private val git: GitClient = GitClient(),
    private val bootstrap: BootstrapService = BootstrapService(),
    private val repositoryLock: RepositoryOperationLock = RepositoryOperationLock(),
) : WorkspaceProvisioner {
    override val strategy: WorkspaceStrategy = WorkspaceStrategy.STANDARD_WORKTREE

    override fun provision(request: WorkspaceProvisionRequest): List<ServiceWorkspace> {
        require(request.service.strategy == strategy) { "服务不是标准 Worktree 策略" }
        val repositoryPath = Path.of(request.repository.rootPath).toAbsolutePath().normalize()
        val commonDirectory = git.commonDirectory(repositoryPath).toAbsolutePath().normalize()
        return repositoryLock.withLock(commonDirectory) { provisionLocked(request, repositoryPath) }
    }

    private fun provisionLocked(request: WorkspaceProvisionRequest, repositoryPath: Path): List<ServiceWorkspace> {
        val requestedBranch = request.requestedFeatureBranch?.trim().orEmpty()
        val branches = TaskBranchNaming.resolve(requestedBranch, request.service.modules, request.moduleBranches)
        require(git.topLevel(repositoryPath).toAbsolutePath().normalize() == repositoryPath) {
            "配置的仓库路径已不再是 Git 顶层目录：$repositoryPath"
        }
        require(
            git.commonDirectory(repositoryPath).toAbsolutePath().normalize() ==
                Path.of(request.repository.gitCommonDirectory).toAbsolutePath().normalize(),
        ) { "仓库 Git 身份已变化，请先手动刷新并检查配置" }
        git.pruneWorktrees(repositoryPath)
        request.taskDirectory.toFile().mkdirs()

        StandardWorktreeModuleNaming.requireValid(request.service.modules)
        val modules = request.service.modules
        val created = mutableListOf<CreatedStandardWorktree>()
        try {
            return modules.map { module ->
                val branch = branches.getValue(module.id)
                val target = request.taskDirectory
                    .resolve(WorkspaceLayout.standardDirectoryName(request.service, module, modules.size))
                    .toAbsolutePath()
                    .normalize()
                require(target.parent == request.taskDirectory.toAbsolutePath().normalize()) { "Worktree 必须位于任务目录的直接子级" }
                git.fetch(repositoryPath, module.baseRemote)
                val baseBranch = TaskBranchNaming.normalizeBaseRef(module)
                val remoteBaseRef = "${module.baseRemote}/$baseBranch"
                require(git.refExists(repositoryPath, "refs/remotes/$remoteBaseRef")) {
                    "远端基础分支不存在：$remoteBaseRef"
                }
                require(git.run(repositoryPath, "check-ref-format", "--branch", branch, check = false).succeeded) {
                    "分支名不合法：$branch"
                }
                val featureRemotes = listOf(tagRemote(module)).distinct()
                featureRemotes.forEach { featureRemote ->
                    if (featureRemote != module.baseRemote) git.fetch(repositoryPath, featureRemote)
                }
                val localExists = git.refExists(repositoryPath, "refs/heads/$branch")
                val remoteBranches = featureRemotes.filter {
                    git.refExists(repositoryPath, "refs/remotes/$it/$branch")
                }
                val matchingWorktrees = if (localExists) {
                    git.worktrees(repositoryPath).filter { it.branch == branch && it.path != target }
                } else emptyList()
                val currentKey = BranchReuseKey(
                    request.repository.id,
                    branch,
                    branchReuseFingerprint(localExists, remoteBranches.map { "$it/$branch" }, matchingWorktrees),
                )
                val confirmationMatches = request.confirmedBranchReuseKeys.any { confirmed ->
                    confirmed.repositoryId == currentKey.repositoryId && confirmed.branch == currentKey.branch &&
                        (confirmed.stateFingerprint == null || confirmed.stateFingerprint == currentKey.stateFingerprint)
                }
                require(!localExists && remoteBranches.isEmpty() || confirmationMatches) {
                    "分支复用状态已变化或尚未确认，请重新预检：$branch"
                }
                require(matchingWorktrees.none { it.locked }) {
                    "分支被锁定 Worktree 占用，不能强制复用：" +
                        matchingWorktrees.filter { it.locked }.joinToString { it.path.toString() }
                }
                val forceAttach = matchingWorktrees.isNotEmpty()
                when {
                    localExists -> git.addExistingWorktree(repositoryPath, target, branch, force = forceAttach)
                    remoteBranches.isNotEmpty() -> {
                        val trackingRemote = tagRemote(module)
                            .takeIf { it in remoteBranches }
                            ?: remoteBranches.first()
                        git.addTrackedRemoteWorktree(repositoryPath, target, branch, trackingRemote)
                    }
                    else -> git.addWorktree(repositoryPath, target, branch, remoteBaseRef, module.baseRemote)
                }
                val branchCreatedByTask = !localExists
                created += CreatedStandardWorktree(target, branch, branchCreatedByTask)
                val initialization = bootstrap.initialize(repositoryPath, target, request.service.bootstrap)
                ServiceWorkspace(
                    repositoryId = request.repository.id,
                    serviceName = request.service.displayName,
                    repositoryPath = request.repository.rootPath,
                    worktreePath = target.toString(),
                    developmentTool = request.service.developmentTool,
                    branch = branch,
                    health = if (initialization.succeeded) WorkspaceHealth.READY else WorkspaceHealth.READY_WITH_WARNINGS,
                    warnings = initialization.warnings,
                    groupServiceId = request.service.id,
                    moduleId = module.id,
                    moduleName = ModuleDisplayNaming.resolve(
                        module.name,
                        request.service.displayName,
                        module.baseRef,
                        request.service.modules.size,
                    ),
                    strategy = strategy,
                    originUrl = request.repository.originUrl,
                    baseRef = "${module.baseRemote}/${TaskBranchNaming.normalizeBaseRef(module)}",
                    branchCreatedByTask = branchCreatedByTask,
                    forceWorktreeAttach = forceAttach,
                    pushRemote = module.baseRemote,
                )
            }
        } catch (error: Throwable) {
            created.asReversed().forEach { createdWorktree ->
                runCatching { git.removeWorktree(repositoryPath, createdWorktree.target, force = true) }
                if (createdWorktree.branchCreatedByTask) {
                    runCatching { git.run(repositoryPath, "branch", "-D", createdWorktree.branch, check = false) }
                }
            }
            runCatching { git.run(repositoryPath, "worktree", "prune", check = false) }
            throw error
        }
    }

    override fun rollback(request: WorkspaceProvisionRequest, workspaces: List<ServiceWorkspace>) {
        val repository = Path.of(request.repository.rootPath).toAbsolutePath().normalize()
        val commonDirectory = git.commonDirectory(repository).toAbsolutePath().normalize()
        repositoryLock.withLock(commonDirectory) { rollbackLocked(request, workspaces, repository) }
    }

    private fun rollbackLocked(request: WorkspaceProvisionRequest, workspaces: List<ServiceWorkspace>, repository: Path) {
        val failures = mutableListOf<Throwable>()
        workspaces
            .distinctBy { Path.of(it.worktreePath).toAbsolutePath().normalize() }
            .asReversed()
            .forEach { workspace ->
                val target = Path.of(workspace.worktreePath).toAbsolutePath().normalize()
                require(target.parent == request.taskDirectory.toAbsolutePath().normalize()) {
                    "拒绝回滚任务目录之外的 Worktree：$target"
                }
                runCatching {
                    git.removeWorktree(repository, target, force = true)
                    if (workspace.branchCreatedByTask) {
                        git.run(repository, "branch", "-D", workspace.branch, check = false)
                    }
                }.onFailure(failures::add)
            }
        runCatching { git.run(repository, "worktree", "prune", check = false) }.onFailure(failures::add)
        if (failures.isNotEmpty()) {
            throw IllegalStateException("Worktree 回滚未完整完成：${request.service.displayName}")
                .apply { failures.forEach(::addSuppressed) }
        }
    }

}

private fun tagRemote(module: ServiceModuleConfig): String =
    if (module.tagMode == TagBuildMode.MERGE_TO_TARGET_BRANCH) {
        RemoteBranchRef.parse(requireNotNull(module.tagTargetRef)).remote
    } else {
        module.baseRemote
    }

class IndependentCloneProvisioner(
    private val git: GitClient = GitClient(),
    private val bootstrap: BootstrapService = BootstrapService(),
) : WorkspaceProvisioner {
    override val strategy: WorkspaceStrategy = WorkspaceStrategy.INDEPENDENT_CLONE

    override fun provision(request: WorkspaceProvisionRequest): List<ServiceWorkspace> {
        require(request.service.strategy == strategy) { "服务不是独立克隆策略" }
        val origin = request.repository.originUrl?.trim().orEmpty()
        require(origin.isNotBlank()) { "独立克隆需要仓库配置 origin URL" }
        val created = mutableListOf<ServiceWorkspace>()
        try {
            request.service.cloneModules.forEach { module ->
                val branchRef = RemoteBranchRef.parse(module.branch)
                val target = request.taskDirectory.resolve(WorkspaceLayout.cloneDirectoryName(request.service, module))
                require(!Files.exists(target)) { "目标目录已存在：$target" }
                git.cloneRepository(origin, target, branchRef.branch)
                val initialization = bootstrap.initialize(
                    Path.of(request.repository.rootPath).toAbsolutePath().normalize(),
                    target,
                    request.service.bootstrap,
                )
                created += ServiceWorkspace(
                    repositoryId = request.repository.id,
                    serviceName = request.service.displayName,
                    repositoryPath = target.toString(),
                    worktreePath = target.toString(),
                    developmentTool = request.service.developmentTool,
                    branch = branchRef.branch,
                    health = if (initialization.succeeded) WorkspaceHealth.READY else WorkspaceHealth.READY_WITH_WARNINGS,
                    warnings = initialization.warnings,
                    groupServiceId = request.service.id,
                    moduleId = module.id,
                    moduleName = ModuleDisplayNaming.resolve(module.name, request.service.displayName, module.branch, request.service.cloneModules.size),
                    strategy = strategy,
                    originUrl = origin,
                    baseRef = module.branch,
                    pushRemote = branchRef.remote,
                )
            }
            return created
        } catch (error: Throwable) {
            rollback(request, created)
            throw error
        }
    }

    override fun rollback(request: WorkspaceProvisionRequest, workspaces: List<ServiceWorkspace>) {
        workspaces.distinctBy(ServiceWorkspace::worktreePath).forEach { workspace ->
            val target = Path.of(workspace.worktreePath).toAbsolutePath().normalize()
            require(target.parent == request.taskDirectory.toAbsolutePath().normalize()) {
                "拒绝回滚任务目录之外的独立克隆：$target"
            }
            if (Files.exists(target)) {
                Files.walk(target).use { entries ->
                    entries.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
        }
    }
}

class WorkspaceProvisioningService(
    provisioners: List<WorkspaceProvisioner> = listOf(
        StandardWorktreeProvisioner(),
        IndependentCloneProvisioner(),
    ),
) {
    private val byStrategy = provisioners.associateBy(WorkspaceProvisioner::strategy)

    fun provision(request: WorkspaceProvisionRequest): List<ServiceWorkspace> =
        byStrategy[request.service.strategy]
            ?.provision(request)
            ?: error("未注册工作区策略：${request.service.strategy}")

    fun rollback(request: WorkspaceProvisionRequest, workspaces: List<ServiceWorkspace>) {
        byStrategy[request.service.strategy]
            ?.rollback(request, workspaces)
            ?: error("未注册工作区策略：${request.service.strategy}")
    }
}
