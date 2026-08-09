package com.snowball.awm.core

import java.nio.file.Files
import java.nio.file.Path

data class WorkspaceProvisionRequest(
    val taskDirectory: Path,
    val repository: RepositoryConfig,
    val service: GroupServiceConfig,
    val requestedFeatureBranch: String? = null,
)

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
        "${TaskNaming.directoryName(service.displayName)}-${TaskNaming.directoryName(service.id)}"

    fun standardDirectoryName(
        service: GroupServiceConfig,
        module: ServiceModuleConfig,
        distinctBaseCount: Int,
    ): String {
        val base = serviceDirectoryBase(service)
        return if (distinctBaseCount == 1) {
            base
        } else {
            "$base-${TaskNaming.directoryName(TaskBranchNaming.baseIdentity(module).substringAfter('|'))}"
        }
    }

    fun cloneDirectoryName(service: GroupServiceConfig, module: IndependentCloneModuleConfig): String =
        "${serviceDirectoryBase(service)}-${TaskNaming.directoryName(module.id)}"
}

class StandardWorktreeProvisioner(
    private val git: GitClient = GitClient(),
    private val bootstrap: BootstrapService = BootstrapService(),
) : WorkspaceProvisioner {
    override val strategy: WorkspaceStrategy = WorkspaceStrategy.STANDARD_WORKTREE

    override fun provision(request: WorkspaceProvisionRequest): List<ServiceWorkspace> {
        require(request.service.strategy == strategy) { "服务不是标准 Worktree 策略" }
        val requestedBranch = request.requestedFeatureBranch?.trim().orEmpty()
        val branches = TaskBranchNaming.derive(requestedBranch, request.service.modules)
        val repositoryPath = Path.of(request.repository.rootPath).toAbsolutePath().normalize()
        require(git.topLevel(repositoryPath).toAbsolutePath().normalize() == repositoryPath) {
            "配置的仓库路径已不再是 Git 顶层目录：$repositoryPath"
        }
        require(
            git.commonDirectory(repositoryPath).toAbsolutePath().normalize() ==
                Path.of(request.repository.gitCommonDirectory).toAbsolutePath().normalize(),
        ) { "仓库 Git 身份已变化，请先手动刷新并检查配置" }
        request.taskDirectory.toFile().mkdirs()

        val moduleGroups = request.service.modules.groupBy(TaskBranchNaming::baseIdentity).values
        val created = mutableListOf<Pair<Path, String>>()
        try {
            return moduleGroups.flatMap { modules ->
                val representative = modules.first()
                val branch = branches.getValue(representative.id)
                val target = request.taskDirectory
                    .resolve(WorkspaceLayout.standardDirectoryName(request.service, representative, moduleGroups.size))
                    .toAbsolutePath()
                    .normalize()
                require(target.parent == request.taskDirectory.toAbsolutePath().normalize()) { "Worktree 必须位于任务目录的直接子级" }
                git.fetch(repositoryPath, representative.baseRemote)
                val baseBranch = TaskBranchNaming.normalizeBaseRef(representative)
                val remoteBaseRef = "${representative.baseRemote}/$baseBranch"
                require(git.refExists(repositoryPath, "refs/remotes/$remoteBaseRef")) {
                    "远端基础分支不存在：$remoteBaseRef"
                }
                require(git.run(repositoryPath, "check-ref-format", "--branch", branch, check = false).succeeded) {
                    "分支名不合法：$branch"
                }
                require(!git.refExists(repositoryPath, "refs/heads/$branch")) { "本地分支已存在：$branch" }
                modules.map { RemoteBranchRef.parse(it.uatRef).remote }.distinct().forEach { featureRemote ->
                    if (featureRemote != representative.baseRemote) git.fetch(repositoryPath, featureRemote)
                    require(!git.refExists(repositoryPath, "refs/remotes/$featureRemote/$branch")) {
                        "远程分支已存在：$featureRemote/$branch"
                    }
                }
                git.addWorktree(repositoryPath, target, branch, remoteBaseRef, representative.baseRemote)
                created += target to branch
                val initialization = bootstrap.initialize(repositoryPath, target, request.service.bootstrap)
                modules.map { module ->
                    ServiceWorkspace(
                        repositoryId = request.repository.id,
                        serviceName = request.service.displayName,
                        repositoryPath = request.repository.rootPath,
                        worktreePath = target.toString(),
                        ideType = request.service.ideType,
                        branch = branch,
                        status = if (initialization.succeeded) WorkspaceStatus.READY else WorkspaceStatus.READY_WITH_WARNINGS,
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
                    )
                }
            }
        } catch (error: Throwable) {
            created.asReversed().forEach { (target, branch) ->
                runCatching { git.removeWorktree(repositoryPath, target, force = true) }
                runCatching { git.run(repositoryPath, "branch", "-D", branch, check = false) }
            }
            runCatching { git.run(repositoryPath, "worktree", "prune", check = false) }
            throw error
        }
    }

    override fun rollback(request: WorkspaceProvisionRequest, workspaces: List<ServiceWorkspace>) {
        val repository = Path.of(request.repository.rootPath).toAbsolutePath().normalize()
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
                    git.run(repository, "branch", "-D", workspace.branch, check = false)
                }.onFailure(failures::add)
            }
        runCatching { git.run(repository, "worktree", "prune", check = false) }.onFailure(failures::add)
        if (failures.isNotEmpty()) {
            throw IllegalStateException("Worktree 回滚未完整完成：${request.service.displayName}")
                .apply { failures.forEach(::addSuppressed) }
        }
    }

}

class IndependentCloneProvisioner(
    private val git: GitClient = GitClient(),
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
                created += ServiceWorkspace(
                    repositoryId = request.repository.id,
                    serviceName = request.service.displayName,
                    repositoryPath = target.toString(),
                    worktreePath = target.toString(),
                    ideType = request.service.ideType,
                    branch = branchRef.branch,
                    status = WorkspaceStatus.READY,
                    groupServiceId = request.service.id,
                    moduleId = module.id,
                    moduleName = ModuleDisplayNaming.resolve(module.name, request.service.displayName, module.branch, request.service.cloneModules.size),
                    strategy = strategy,
                    originUrl = origin,
                    baseRef = module.branch,
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
