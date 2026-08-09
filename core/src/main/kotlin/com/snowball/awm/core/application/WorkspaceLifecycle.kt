package com.snowball.awm.core

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.io.path.exists

interface WorkspaceLifecycle {
    fun inspectDeleteRisks(config: AppConfig, taskDirectory: Path, manifest: TaskManifest): List<DeleteRisk>
    fun requireArchiveSafe(config: AppConfig, taskDirectory: Path, manifest: TaskManifest, force: Boolean)
    fun removeAll(config: AppConfig, taskDirectory: Path, manifest: TaskManifest, force: Boolean)
    fun restoreAll(config: AppConfig, taskDirectory: Path, manifest: TaskManifest): List<ServiceWorkspace>
    fun validateForMutation(
        config: AppConfig,
        taskDirectory: Path,
        manifest: TaskManifest,
        workspace: ServiceWorkspace,
    ): WorkspaceMutationTarget
}

data class WorkspaceMutationTarget(
    val repository: Path,
    val worktree: Path,
)

/** Git/file-system implementation of the physical workspace lifecycle safety boundary. */
class GitWorkspaceLifecycle(
    private val git: GitClient = GitClient(),
    private val bootstrap: BootstrapService = BootstrapService(),
) : WorkspaceLifecycle {
    override fun inspectDeleteRisks(config: AppConfig, taskDirectory: Path, manifest: TaskManifest): List<DeleteRisk> =
        physicalWorkspaces(manifest).also { validateTaskDirectory(config, taskDirectory, manifest) }.mapNotNull { workspace ->
            val target = validateTarget(taskDirectory, workspace)
            if (!target.exists()) return@mapNotNull null
            validateExistingIdentity(config = config, workspace = workspace, target = target)
            val status = runCatching { git.status(target) }.getOrElse { error ->
                return@mapNotNull DeleteRisk(
                    workspace.serviceName,
                    staged = false,
                    unstaged = false,
                    untracked = false,
                    operationInProgress = null,
                    statusCheckError = error.message,
                )
            }
            val allBranchUnpushed = if (workspace.strategy == WorkspaceStrategy.INDEPENDENT_CLONE) {
                runCatching { git.localOnlyCommitCount(target) }.getOrElse { error ->
                    return@mapNotNull DeleteRisk(
                        workspace.serviceName,
                        status.staged,
                        status.unstaged,
                        status.untracked,
                        status.operationInProgress,
                        statusCheckError = error.message,
                    )
                }.coerceAtLeast(status.unpushedCommits)
            } else {
                0
            }
            if (!status.hasUncommittedChanges && allBranchUnpushed == 0) return@mapNotNull null
            DeleteRisk(
                workspace.serviceName,
                status.staged,
                status.unstaged,
                status.untracked,
                status.operationInProgress,
                unpushedCommits = allBranchUnpushed,
            )
        }

    override fun requireArchiveSafe(config: AppConfig, taskDirectory: Path, manifest: TaskManifest, force: Boolean) {
        validateTaskDirectory(config, taskDirectory, manifest)
        validateRemovalCandidates(config, taskDirectory, manifest)
        if (force) return
        val unsafe = physicalWorkspaces(manifest).mapNotNull { workspace ->
            val target = validateTarget(taskDirectory, workspace)
            if (!target.exists()) return@mapNotNull null
            val status = git.status(target)
            val cloneHasLocalOnlyCommits = workspace.strategy == WorkspaceStrategy.INDEPENDENT_CLONE &&
                git.localOnlyCommitCount(target) > 0
            workspace.takeUnless { status.safeToArchive && !cloneHasLocalOnlyCommits }
        }
        require(unsafe.isEmpty()) {
            "存在未提交或未推送的工作区，无法归档：${unsafe.joinToString { it.serviceName }}"
        }
    }

    override fun removeAll(config: AppConfig, taskDirectory: Path, manifest: TaskManifest, force: Boolean) {
        validateTaskDirectory(config, taskDirectory, manifest)
        validateRemovalCandidates(config, taskDirectory, manifest)
        physicalWorkspaces(manifest).forEach { workspace -> removeOne(config, taskDirectory, workspace, force) }
    }

    override fun restoreAll(config: AppConfig, taskDirectory: Path, manifest: TaskManifest): List<ServiceWorkspace> {
        validateTaskDirectory(config, taskDirectory, manifest)
        val restoredByPath = linkedMapOf<String, ServiceWorkspace>()
        try {
            physicalWorkspaces(manifest).forEach { workspace ->
                val target = validateTarget(taskDirectory, workspace)
                require(!target.exists()) { "恢复目标已存在：$target" }
                val restored = when (workspace.strategy) {
                    WorkspaceStrategy.STANDARD_WORKTREE -> {
                        val repository = validateConfiguredRepository(config, workspace)
                        git.addExistingWorktree(repository, target, workspace.branch)
                        restoredByPath[target.toString()] = workspace.copy(status = WorkspaceStatus.READY, warnings = emptyList())
                        val service = config.group(manifest.groupId).services.firstOrNull { it.id == workspace.groupServiceId }
                        val initialized = service?.let { bootstrap.initialize(repository, target, it.bootstrap) }
                        workspace.copy(
                            status = if (initialized == null || initialized.succeeded) {
                                WorkspaceStatus.READY
                            } else {
                                WorkspaceStatus.READY_WITH_WARNINGS
                            },
                            warnings = initialized?.warnings.orEmpty(),
                        )
                    }
                    WorkspaceStrategy.INDEPENDENT_CLONE -> {
                        validateCloneConfig(config, workspace)
                        try {
                            git.cloneRepository(workspace.originUrl!!, target, workspace.branch)
                        } catch (error: Throwable) {
                            if (target.exists()) deleteRecursively(target)
                            throw error
                        }
                        workspace.copy(status = WorkspaceStatus.READY, warnings = emptyList())
                    }
                }
                restoredByPath[target.toString()] = restored
            }
        } catch (error: Throwable) {
            val cleanupFailures = mutableListOf<Throwable>()
            restoredByPath.values.toList().asReversed().forEach { restored ->
                runCatching { removeOne(config, taskDirectory, restored, force = true) }
                    .onFailure(cleanupFailures::add)
            }
            if (cleanupFailures.isNotEmpty()) {
                throw IllegalStateException(
                    "工作区恢复失败，且自动回滚未完成；已保留现场供人工检查：$taskDirectory",
                    error,
                ).apply { cleanupFailures.forEach(::addSuppressed) }
            }
            throw error
        }
        return manifest.services.map { workspace ->
            val restored = restoredByPath.getValue(Path.of(workspace.worktreePath).toAbsolutePath().normalize().toString())
            workspace.copy(status = restored.status, warnings = restored.warnings)
        }
    }

    override fun validateForMutation(
        config: AppConfig,
        taskDirectory: Path,
        manifest: TaskManifest,
        workspace: ServiceWorkspace,
    ): WorkspaceMutationTarget {
        validateTaskDirectory(config, taskDirectory, manifest)
        val target = validateTarget(taskDirectory, workspace)
        require(target.exists()) { "任务工作区不存在：$target" }
        val actualBranch = git.currentBranch(target)
        require(actualBranch == workspace.branch) {
            "工作区当前分支与任务记录不一致：期望 ${workspace.branch}，实际 ${actualBranch ?: "detached"}"
        }
        return when (workspace.strategy) {
            WorkspaceStrategy.STANDARD_WORKTREE -> {
                val repository = validateConfiguredRepository(config, workspace)
                validateExistingIdentity(config, workspace, target)
                WorkspaceMutationTarget(repository, target)
            }
            WorkspaceStrategy.INDEPENDENT_CLONE -> {
                validateCloneConfig(config, workspace)
                validateExistingIdentity(config, workspace, target)
                WorkspaceMutationTarget(target, target)
            }
        }
    }

    private fun removeOne(config: AppConfig, taskDirectory: Path, workspace: ServiceWorkspace, force: Boolean) {
        val target = validateTarget(taskDirectory, workspace)
        if (!target.exists()) return
        when (workspace.strategy) {
            WorkspaceStrategy.STANDARD_WORKTREE -> {
                val repository = validateConfiguredRepository(config, workspace)
                validateExistingIdentity(config, workspace, target)
                git.removeWorktree(repository, target, force)
                git.run(repository, "worktree", "prune", check = false)
            }
            WorkspaceStrategy.INDEPENDENT_CLONE -> {
                validateCloneConfig(config, workspace)
                validateExistingIdentity(config, workspace, target)
                deleteRecursively(target)
            }
        }
    }

    /** Validate every candidate before deleting the first physical workspace. */
    private fun validateRemovalCandidates(config: AppConfig, taskDirectory: Path, manifest: TaskManifest) {
        physicalWorkspaces(manifest).forEach { workspace ->
            val target = validateTarget(taskDirectory, workspace)
            if (!target.exists()) return@forEach
            when (workspace.strategy) {
                WorkspaceStrategy.STANDARD_WORKTREE -> validateConfiguredRepository(config, workspace)
                WorkspaceStrategy.INDEPENDENT_CLONE -> validateCloneConfig(config, workspace)
            }
            validateExistingIdentity(config, workspace, target)
        }
    }

    private fun validateTarget(taskDirectory: Path, workspace: ServiceWorkspace): Path {
        val task = taskDirectory.toAbsolutePath().normalize()
        require(task.parent != null && task != task.root) { "拒绝对文件系统根目录执行任务操作" }
        val target = Path.of(workspace.worktreePath).toAbsolutePath().normalize()
        require(target.parent == task) { "工作区路径不在任务目录的直接子级，已拒绝操作：$target" }
        return target
    }

    private fun validateConfiguredRepository(config: AppConfig, workspace: ServiceWorkspace): Path {
        val configured = config.repositories.firstOrNull { it.id == workspace.repositoryId }
        requireNotNull(configured) { "仓库配置不存在：${workspace.repositoryId}" }
        val repository = Path.of(configured.rootPath).toAbsolutePath().normalize()
        require(git.commonDirectory(repository).toAbsolutePath().normalize() == Path.of(configured.gitCommonDirectory).toAbsolutePath().normalize()) {
            "主仓库 Git 身份与配置不匹配"
        }
        return repository
    }

    private fun validateTaskDirectory(config: AppConfig, taskDirectory: Path, manifest: TaskManifest) {
        val root = config.taskRoot?.let(Path::of)?.toAbsolutePath()?.normalize()
            ?: error("尚未配置任务根目录")
        val task = taskDirectory.toAbsolutePath().normalize()
        require(task.parent == root && task.fileName.toString() == manifest.taskDirectoryName) {
            "任务目录与配置或任务清单不匹配：$task"
        }
    }

    private fun validateCloneConfig(config: AppConfig, workspace: ServiceWorkspace) {
        val configured = config.repositories.firstOrNull { it.id == workspace.repositoryId }
            ?: error("仓库配置不存在：${workspace.repositoryId}")
        require(!workspace.originUrl.isNullOrBlank() && workspace.originUrl == configured.originUrl) {
            "独立克隆 origin 与配置不匹配"
        }
    }

    private fun validateExistingIdentity(config: AppConfig?, workspace: ServiceWorkspace, target: Path) {
        require(Files.isDirectory(target.resolve(".git")) || workspace.strategy == WorkspaceStrategy.STANDARD_WORKTREE) {
            "独立克隆不是常规 Git checkout：$target"
        }
        require(git.topLevel(target).toAbsolutePath().normalize() == target) { "Git 顶层目录不匹配：$target" }
        if (workspace.strategy == WorkspaceStrategy.INDEPENDENT_CLONE) {
            val actualOrigin = git.remoteUrl(target)?.trim().orEmpty()
            require(actualOrigin == workspace.originUrl?.trim().orEmpty()) { "独立克隆 origin 与任务记录不匹配" }
        } else if (config != null) {
            val expectedCommon = config.repositories.firstOrNull { it.id == workspace.repositoryId }
                ?.let { Path.of(it.gitCommonDirectory).toAbsolutePath().normalize() }
                ?: git.commonDirectory(Path.of(workspace.repositoryPath)).toAbsolutePath().normalize()
            require(git.commonDirectory(target).toAbsolutePath().normalize() == expectedCommon) {
                "Linked Worktree 不属于配置的主仓库"
            }
        }
    }

    private fun physicalWorkspaces(manifest: TaskManifest): List<ServiceWorkspace> =
        manifest.services.distinctBy { Path.of(it.worktreePath).toAbsolutePath().normalize().toString() }

    private fun deleteRecursively(path: Path) {
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                deleteWritable(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                if (exc != null) throw exc
                deleteWritable(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun deleteWritable(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (denied: java.nio.file.AccessDeniedException) {
            // Git object files copied/cloned on Windows may retain DOS read-only.
            // The target has already passed task containment and Git identity checks.
            path.toFile().setWritable(true)
            try {
                Files.deleteIfExists(path)
            } catch (retry: Throwable) {
                retry.addSuppressed(denied)
                throw retry
            }
        }
    }
}
