package com.snowball.taskwt.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

data class CreateTaskRequest(
    val taskKey: String,
    val featureBranch: String,
    val repositoryIds: List<String>,
)

class TaskManager(
    private val git: GitClient = GitClient(),
    private val manifests: ManifestStore = ManifestStore(),
    private val bootstrap: BootstrapService = BootstrapService(),
    private val clock: Clock = Clock.systemUTC(),
    private val events: EventSink = NoOpEventSink,
) {
    fun create(
        config: AppConfig,
        repositories: List<RepositoryInfo>,
        request: CreateTaskRequest,
    ): TaskManifest {
        require(request.taskKey.isNotBlank()) { "任务编号不能为空" }
        require(request.featureBranch.isNotBlank()) { "分支名不能为空" }
        require(request.featureBranch.none { it.isWhitespace() }) { "分支名不能包含空白字符" }
        require(request.repositoryIds.isNotEmpty()) { "至少选择一个服务" }
        events.info(
            event = "task.create.started",
            message = "开始创建任务工作区",
            metadata = mapOf(
                "taskKey" to request.taskKey,
                "featureBranch" to request.featureBranch,
                "serviceCount" to request.repositoryIds.distinct().size.toString(),
            ),
            clock = clock,
        )
        val taskRoot = config.taskRoot?.let(Path::of)
            ?: throw IllegalStateException("尚未配置任务根目录")
        val taskDirectoryName = TaskNaming.directoryName(request.taskKey)
        val taskDirectory = taskRoot.resolve(taskDirectoryName)
        require(!taskDirectory.resolve(ManifestStore.FILE_NAME).exists()) {
            "任务已存在：${request.taskKey}"
        }

        val repositoryById = repositories.associateBy { it.id }
        val selected = request.repositoryIds.distinct().map { repositoryId ->
            repositoryById[repositoryId]
                ?: throw IllegalArgumentException("找不到服务：$repositoryId，请先重新扫描")
        }
        selected.forEach { repository ->
            val service = config.services[repository.id] ?: ServiceConfig(
                repositoryId = repository.id,
                displayName = repository.name,
            )
            require(service.enabled) { "服务已禁用：${service.displayName}" }
            git.fetch(Path.of(repository.rootPath), service.uatRemote)
            ensureBranchAvailable(Path.of(repository.rootPath), request.featureBranch)
        }

        val now = Instant.now(clock).toString()
        val duplicateNames = selected.groupingBy { it.name.lowercase() }.eachCount()
        val workspaces = selected.map { repository ->
            val service = config.services[repository.id] ?: ServiceConfig(
                repositoryId = repository.id,
                displayName = repository.name,
            )
            val ideDirectory = when (service.ideType) {
                IdeType.IDEA -> taskDirectory.resolve("idea-$taskDirectoryName")
                IdeType.WEBSTORM -> taskDirectory.resolve("webstorm-$taskDirectoryName")
            }
            val worktreeDirectoryName = if (duplicateNames.getValue(repository.name.lowercase()) > 1) {
                "${repository.name}-${repository.id.removePrefix("repo-").take(6)}"
            } else {
                repository.name
            }
            ServiceWorkspace(
                repositoryId = repository.id,
                serviceName = service.displayName,
                repositoryPath = repository.rootPath,
                worktreePath = ideDirectory.resolve(worktreeDirectoryName).toString(),
                ideType = service.ideType,
                branch = request.featureBranch,
            )
        }
        var manifest = TaskManifest(
            taskKey = request.taskKey.trim(),
            taskDirectoryName = taskDirectoryName,
            featureBranch = request.featureBranch,
            createdAt = now,
            updatedAt = now,
            status = WorkspaceStatus.CREATING,
            services = workspaces,
        )
        manifests.save(taskDirectory, manifest)

        val results = workspaces.map { workspace ->
            createWorkspace(config, workspace)
        }
        writeIdeaAggregate(taskDirectory, taskDirectoryName, results)
        writeJetBrainsProjectNames(taskDirectory, manifest.taskKey, taskDirectoryName, results)
        val overallStatus = when {
            results.any { it.status == WorkspaceStatus.FAILED } -> WorkspaceStatus.FAILED
            results.any { it.status == WorkspaceStatus.READY_WITH_WARNINGS } -> WorkspaceStatus.READY_WITH_WARNINGS
            else -> WorkspaceStatus.READY
        }
        manifest = manifest.copy(
            updatedAt = Instant.now(clock).toString(),
            status = overallStatus,
            services = results,
        )
        manifests.save(taskDirectory, manifest)
        events.info(
            event = "task.create.completed",
            message = "任务工作区创建结束",
            metadata = mapOf(
                "taskKey" to manifest.taskKey,
                "status" to manifest.status.name,
            ),
            clock = clock,
        )
        return manifest
    }

    fun initialize(
        config: AppConfig,
        taskDirectory: Path,
        failedOnly: Boolean = false,
    ): TaskManifest {
        val manifest = manifests.load(taskDirectory)
        require(manifest.status != WorkspaceStatus.ARCHIVED) { "已归档任务不能执行初始化，请先恢复任务" }
        val updatedServices = manifest.services.map { workspace ->
            if (failedOnly && workspace.status != WorkspaceStatus.READY_WITH_WARNINGS &&
                workspace.status != WorkspaceStatus.FAILED
            ) {
                workspace
            } else {
                val serviceConfig = config.services[workspace.repositoryId] ?: return@map workspace
                val result = bootstrap.initialize(
                    Path.of(workspace.repositoryPath),
                    Path.of(workspace.worktreePath),
                    serviceConfig.bootstrap,
                )
                workspace.copy(
                    status = if (result.succeeded) WorkspaceStatus.READY else WorkspaceStatus.READY_WITH_WARNINGS,
                    warnings = result.warnings,
                )
            }
        }
        val status = aggregateStatus(updatedServices)
        return manifest.copy(
            updatedAt = Instant.now(clock).toString(),
            status = status,
            services = updatedServices,
        ).also { manifests.save(taskDirectory, it) }
    }

    fun archive(taskDirectory: Path, force: Boolean = false): TaskManifest {
        val manifest = manifests.load(taskDirectory)
        events.info(
            event = "task.archive.started",
            message = "开始归档任务",
            metadata = mapOf("taskKey" to manifest.taskKey, "force" to force.toString()),
            clock = clock,
        )
        require(manifest.status != WorkspaceStatus.ARCHIVED) { "任务已经归档" }
        val unsafe = manifest.services.mapNotNull { workspace ->
            val path = Path.of(workspace.worktreePath)
            if (!path.exists()) return@mapNotNull null
            val status = git.status(path)
            if (status.safeToArchive) null else workspace.serviceName to status
        }
        if (unsafe.isNotEmpty() && !force) {
            val detail = unsafe.joinToString("\n") { (name, status) ->
                "$name：staged=${status.staged}, unstaged=${status.unstaged}, " +
                    "untracked=${status.untracked}, unpushed=${status.unpushedCommits}, " +
                    "operation=${status.operationInProgress ?: "none"}"
            }
            throw IllegalStateException("存在未安全保存的工作区，无法归档：\n$detail")
        }
        manifest.services.forEach { workspace ->
            val worktree = Path.of(workspace.worktreePath)
            if (worktree.exists()) {
                git.removeWorktree(Path.of(workspace.repositoryPath), worktree, force)
            }
        }
        return manifest.copy(
            status = WorkspaceStatus.ARCHIVED,
            updatedAt = Instant.now(clock).toString(),
            services = manifest.services.map { it.copy(status = WorkspaceStatus.ARCHIVED) },
        ).also {
            manifests.save(taskDirectory, it)
            events.info(
                event = "task.archive.completed",
                message = "任务归档完成",
                metadata = mapOf("taskKey" to it.taskKey),
                clock = clock,
            )
        }
    }

    fun restore(
        config: AppConfig,
        taskDirectory: Path,
        rerunBootstrap: Boolean = true,
    ): TaskManifest {
        val manifest = manifests.load(taskDirectory)
        events.info(
            event = "task.restore.started",
            message = "开始恢复任务",
            metadata = mapOf("taskKey" to manifest.taskKey),
            clock = clock,
        )
        require(manifest.status == WorkspaceStatus.ARCHIVED) { "只有已归档任务可以恢复" }
        val restored = manifest.services.map { workspace ->
            runCatching {
                val repository = Path.of(workspace.repositoryPath)
                val target = Path.of(workspace.worktreePath)
                require(!target.exists()) { "目标路径已存在：$target" }
                val localRef = "refs/heads/${workspace.branch}"
                if (git.refExists(repository, localRef)) {
                    git.addExistingWorktree(repository, target, workspace.branch)
                } else {
                    val service = config.services[workspace.repositoryId]
                        ?: throw IllegalStateException("服务配置不存在：${workspace.serviceName}")
                    val remoteRef = "refs/remotes/${service.uatRemote}/${workspace.branch}"
                    require(git.refExists(repository, remoteRef)) {
                        "本地和远端都找不到分支：${workspace.branch}"
                    }
                    git.run(
                        repository,
                        "worktree",
                        "add",
                        "-b",
                        workspace.branch,
                        "--track",
                        target.toString(),
                        "${service.uatRemote}/${workspace.branch}",
                    )
                }
                if (rerunBootstrap) {
                    val service = config.services[workspace.repositoryId]
                    if (service != null) {
                        val result = bootstrap.initialize(repository, target, service.bootstrap)
                        workspace.copy(
                            status = if (result.succeeded) WorkspaceStatus.READY else WorkspaceStatus.READY_WITH_WARNINGS,
                            warnings = result.warnings,
                        )
                    } else {
                        workspace.copy(status = WorkspaceStatus.READY)
                    }
                } else {
                    workspace.copy(status = WorkspaceStatus.READY)
                }
            }.getOrElse {
                workspace.copy(status = WorkspaceStatus.FAILED, warnings = listOf(it.message ?: "恢复失败"))
            }
        }
        writeIdeaAggregate(taskDirectory, manifest.taskDirectoryName, restored)
        writeJetBrainsProjectNames(
            taskDirectory,
            manifest.taskKey,
            manifest.taskDirectoryName,
            restored,
        )
        return manifest.copy(
            status = aggregateStatus(restored),
            updatedAt = Instant.now(clock).toString(),
            services = restored,
        ).also {
            manifests.save(taskDirectory, it)
            events.info(
                event = "task.restore.completed",
                message = "任务恢复结束",
                metadata = mapOf("taskKey" to it.taskKey, "status" to it.status.name),
                clock = clock,
            )
        }
    }

    private fun createWorkspace(config: AppConfig, workspace: ServiceWorkspace): ServiceWorkspace {
        val repository = Path.of(workspace.repositoryPath)
        val target = Path.of(workspace.worktreePath)
        val service = config.services[workspace.repositoryId] ?: ServiceConfig(
            repositoryId = workspace.repositoryId,
            displayName = workspace.serviceName,
        )
        return runCatching {
            target.parent.createDirectories()
            require(
                git.run(
                    repository,
                    "rev-parse",
                    "--verify",
                    "${service.defaultBaseRef}^{commit}",
                    check = false,
                ).succeeded,
            ) {
                "基础分支不存在：${service.defaultBaseRef}"
            }
            git.addWorktree(repository, target, workspace.branch, service.defaultBaseRef)
            val result = bootstrap.initialize(repository, target, service.bootstrap)
            workspace.copy(
                status = if (result.succeeded) WorkspaceStatus.READY else WorkspaceStatus.READY_WITH_WARNINGS,
                warnings = result.warnings,
            )
        }.getOrElse {
            workspace.copy(
                status = WorkspaceStatus.FAILED,
                warnings = listOf(it.message ?: "创建工作区失败"),
            )
        }
    }

    private fun ensureBranchAvailable(repository: Path, branch: String) {
        require(git.run(repository, "check-ref-format", "--branch", branch, check = false).succeeded) {
            "分支名不合法：$branch"
        }
        val usedBy = git.worktrees(repository).firstOrNull { it.branch == branch }
        require(usedBy == null) { "分支 $branch 已被工作树占用：${usedBy?.path}" }
        require(!git.refExists(repository, "refs/heads/$branch")) { "本地分支已存在：$branch" }
        val remotes = git.run(repository, "for-each-ref", "--format=%(refname)", "refs/remotes").stdout
        require(remotes.lineSequence().none { it.endsWith("/$branch") }) { "远端分支已存在：$branch" }
    }

    private fun writeIdeaAggregate(
        taskDirectory: Path,
        taskDirectoryName: String,
        workspaces: List<ServiceWorkspace>,
    ) {
        val ideaWorkspaces = workspaces.filter { it.ideType == IdeType.IDEA && it.status != WorkspaceStatus.FAILED }
        if (ideaWorkspaces.isEmpty()) return
        val ideaDirectory = taskDirectory.resolve("idea-$taskDirectoryName")
        ideaDirectory.createDirectories()
        val modules = ideaWorkspaces.joinToString("\n") {
            "        <module>${xmlEscape(Path.of(it.worktreePath).fileName.toString())}</module>"
        }
        Files.writeString(
            ideaDirectory.resolve("pom.xml"),
            """
            |<?xml version="1.0" encoding="UTF-8"?>
            |<project xmlns="http://maven.apache.org/POM/4.0.0"
            |         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            |         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
            |    <modelVersion>4.0.0</modelVersion>
            |    <groupId>com.snowball.taskwt</groupId>
            |    <artifactId>idea-$taskDirectoryName</artifactId>
            |    <version>1.0-SNAPSHOT</version>
            |    <packaging>pom</packaging>
            |    <modules>
            |$modules
            |    </modules>
            |</project>
            |
            """.trimMargin(),
        )
    }

    private fun writeJetBrainsProjectNames(
        taskDirectory: Path,
        taskKey: String,
        taskDirectoryName: String,
        workspaces: List<ServiceWorkspace>,
    ) {
        val ready = workspaces.filter { it.status != WorkspaceStatus.FAILED }
        if (ready.any { it.ideType == IdeType.IDEA }) {
            val metadata = taskDirectory.resolve("idea-$taskDirectoryName").resolve(".idea")
            metadata.createDirectories()
            Files.writeString(metadata.resolve(".name"), "TaskWT - $taskKey - IDEA")
        }
        if (ready.any { it.ideType == IdeType.WEBSTORM }) {
            val metadata = taskDirectory.resolve("webstorm-$taskDirectoryName").resolve(".idea")
            metadata.createDirectories()
            Files.writeString(metadata.resolve(".name"), "TaskWT - $taskKey - WebStorm")
        }
    }

    private fun aggregateStatus(workspaces: List<ServiceWorkspace>): WorkspaceStatus = when {
        workspaces.any { it.status == WorkspaceStatus.FAILED } -> WorkspaceStatus.FAILED
        workspaces.any { it.status == WorkspaceStatus.READY_WITH_WARNINGS } -> WorkspaceStatus.READY_WITH_WARNINGS
        workspaces.all { it.status == WorkspaceStatus.ARCHIVED } -> WorkspaceStatus.ARCHIVED
        else -> WorkspaceStatus.READY
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
