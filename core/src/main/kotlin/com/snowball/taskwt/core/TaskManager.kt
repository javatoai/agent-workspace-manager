package com.snowball.taskwt.core

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Clock
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

data class CreateTaskRequest(
    val taskKey: String,
    val featureBranch: String,
    val repositoryIds: List<String>,
)

data class AddServicesRequest(
    val repositoryIds: List<String>,
)

data class DeleteRisk(
    val serviceName: String,
    val staged: Boolean,
    val unstaged: Boolean,
    val untracked: Boolean,
    val operationInProgress: String?,
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

        val selected = resolveRepositories(repositories, request.repositoryIds)
        selected.forEach { repository ->
            prepareRepositoryForFeatureBranch(
                config = config,
                repository = repository,
                featureBranch = request.featureBranch,
                requireAbsent = true,
            )
        }

        val now = Instant.now(clock).toString()
        val workspaces = buildWorkspaces(
            config = config,
            selected = selected,
            existing = emptyList(),
            taskDirectory = taskDirectory,
            taskDirectoryName = taskDirectoryName,
            featureBranch = request.featureBranch,
        )
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
        manifest = manifest.copy(
            updatedAt = Instant.now(clock).toString(),
            status = aggregateStatus(results),
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

    fun addServices(
        config: AppConfig,
        repositories: List<RepositoryInfo>,
        taskDirectory: Path,
        request: AddServicesRequest,
    ): TaskManifest {
        require(request.repositoryIds.isNotEmpty()) { "至少选择一个服务" }
        val manifest = manifests.load(taskDirectory)
        require(manifest.status != WorkspaceStatus.ARCHIVED) { "已归档任务不能添加服务，请先恢复任务" }
        events.info(
            event = "task.add_services.started",
            message = "开始向任务追加服务",
            metadata = mapOf(
                "taskKey" to manifest.taskKey,
                "serviceCount" to request.repositoryIds.distinct().size.toString(),
            ),
            clock = clock,
        )

        val existingIds = manifest.services.map { it.repositoryId }.toSet()
        val newIds = request.repositoryIds.distinct().filterNot { it in existingIds }
        require(newIds.isNotEmpty()) { "所选服务均已在任务中" }

        val selected = resolveRepositories(repositories, newIds)
        selected.forEach { repository ->
            prepareRepositoryForFeatureBranch(
                config = config,
                repository = repository,
                featureBranch = manifest.featureBranch,
                requireAbsent = false,
            )
        }

        val newWorkspaces = buildWorkspaces(
            config = config,
            selected = selected,
            existing = manifest.services,
            taskDirectory = taskDirectory,
            taskDirectoryName = manifest.taskDirectoryName,
            featureBranch = manifest.featureBranch,
        )
        val results = newWorkspaces.map { workspace ->
            createWorkspace(config, workspace)
        }
        val merged = manifest.services + results
        writeIdeaAggregate(taskDirectory, manifest.taskDirectoryName, merged)
        writeJetBrainsProjectNames(
            taskDirectory,
            manifest.taskKey,
            manifest.taskDirectoryName,
            merged,
        )
        return manifest.copy(
            updatedAt = Instant.now(clock).toString(),
            status = aggregateStatus(merged),
            services = merged,
        ).also {
            manifests.save(taskDirectory, it)
            events.info(
                event = "task.add_services.completed",
                message = "任务追加服务结束",
                metadata = mapOf(
                    "taskKey" to it.taskKey,
                    "status" to it.status.name,
                    "addedCount" to results.size.toString(),
                ),
                clock = clock,
            )
        }
    }

    fun initialize(
        config: AppConfig,
        taskDirectory: Path,
        failedOnly: Boolean = false,
    ): TaskManifest {
        val manifest = manifests.load(taskDirectory)
        require(manifest.status != WorkspaceStatus.ARCHIVED) { "已归档任务不能执行初始化，请先恢复任务" }
        val updatedServices = manifest.services.map { workspace ->
            val worktree = Path.of(workspace.worktreePath)
            val repository = Path.of(workspace.repositoryPath)
            // Only re-run Bootstrap on a registered worktree — residual dirs must not become READY.
            if (!isRegisteredWorktree(repository, worktree)) {
                return@map workspace
            }
            if (failedOnly && workspace.status != WorkspaceStatus.READY_WITH_WARNINGS &&
                workspace.status != WorkspaceStatus.FAILED
            ) {
                return@map workspace
            }
            val serviceConfig = config.services[workspace.repositoryId] ?: return@map workspace
            val result = bootstrap.initialize(
                repository,
                worktree,
                serviceConfig.bootstrap,
            )
            workspace.copy(
                status = if (result.succeeded) WorkspaceStatus.READY else WorkspaceStatus.READY_WITH_WARNINGS,
                warnings = result.warnings,
            )
        }
        val status = aggregateStatus(updatedServices)
        return manifest.copy(
            updatedAt = Instant.now(clock).toString(),
            status = status,
            services = updatedServices,
        ).also { manifests.save(taskDirectory, it) }
    }

    fun retryFailedServices(
        config: AppConfig,
        taskDirectory: Path,
        repositoryIds: List<String>? = null,
    ): TaskManifest {
        val manifest = manifests.load(taskDirectory)
        require(manifest.status != WorkspaceStatus.ARCHIVED) { "已归档任务不能重试，请先恢复任务" }
        val targets = manifest.services.filter { workspace ->
            workspace.status == WorkspaceStatus.FAILED &&
                (repositoryIds == null || workspace.repositoryId in repositoryIds)
        }
        require(targets.isNotEmpty()) { "没有可重试的失败服务" }
        events.info(
            event = "task.retry_failed.started",
            message = "开始重试失败服务的 checkout",
            metadata = mapOf(
                "taskKey" to manifest.taskKey,
                "serviceCount" to targets.size.toString(),
            ),
            clock = clock,
        )

        val retriedIds = targets.map { it.repositoryId }.toSet()
        val updated = manifest.services.map { workspace ->
            if (workspace.repositoryId !in retriedIds) {
                workspace
            } else {
                retryCreateWorkspace(config, workspace)
            }
        }
        writeIdeaAggregate(taskDirectory, manifest.taskDirectoryName, updated)
        writeJetBrainsProjectNames(
            taskDirectory,
            manifest.taskKey,
            manifest.taskDirectoryName,
            updated,
        )
        return manifest.copy(
            updatedAt = Instant.now(clock).toString(),
            status = aggregateStatus(updated),
            services = updated,
        ).also {
            manifests.save(taskDirectory, it)
            events.info(
                event = "task.retry_failed.completed",
                message = "失败服务重试结束",
                metadata = mapOf(
                    "taskKey" to it.taskKey,
                    "status" to it.status.name,
                ),
                clock = clock,
            )
        }
    }

    fun inspectDeleteRisk(taskDirectory: Path): List<DeleteRisk> {
        val manifest = manifests.load(taskDirectory)
        return collectDeleteRisks(manifest)
    }

    fun delete(taskDirectory: Path, forceDiscard: Boolean = false) {
        val manifest = manifests.load(taskDirectory)
        events.info(
            event = "task.delete.started",
            message = "开始删除任务工作区",
            metadata = mapOf(
                "taskKey" to manifest.taskKey,
                "forceDiscard" to forceDiscard.toString(),
            ),
            clock = clock,
        )
        val risks = collectDeleteRisks(manifest)
        if (risks.isNotEmpty() && !forceDiscard) {
            val detail = risks.joinToString("\n") { risk ->
                "${risk.serviceName}：staged=${risk.staged}, unstaged=${risk.unstaged}, " +
                    "untracked=${risk.untracked}, operation=${risk.operationInProgress ?: "none"}"
            }
            throw IllegalStateException(
                "存在未提交改动，删除将丢弃这些改动。请确认后使用强制丢弃：\n$detail",
            )
        }
        val dirtyNames = risks.map { it.serviceName }.toSet()
        manifest.services.forEach { workspace ->
            val worktree = Path.of(workspace.worktreePath)
            if (!worktree.exists()) return@forEach
            val force = forceDiscard || workspace.serviceName in dirtyNames
            runCatching {
                git.removeWorktree(Path.of(workspace.repositoryPath), worktree, force)
            }.onFailure {
                if (worktree.exists()) {
                    deleteRecursively(worktree)
                }
            }
        }
        deleteRecursively(taskDirectory)
        events.info(
            event = "task.delete.completed",
            message = "任务工作区已删除",
            metadata = mapOf("taskKey" to manifest.taskKey),
            clock = clock,
        )
    }

    private fun collectDeleteRisks(manifest: TaskManifest): List<DeleteRisk> =
        manifest.services.mapNotNull { workspace ->
            val path = Path.of(workspace.worktreePath)
            if (!path.exists()) return@mapNotNull null
            val status = runCatching { git.status(path) }.getOrNull() ?: return@mapNotNull null
            if (!status.hasUncommittedChanges) return@mapNotNull null
            DeleteRisk(
                serviceName = workspace.serviceName,
                staged = status.staged,
                unstaged = status.unstaged,
                untracked = status.untracked,
                operationInProgress = status.operationInProgress,
            )
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
                attachWorktree(config, workspace, repository, target)
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

    private fun resolveRepositories(
        repositories: List<RepositoryInfo>,
        repositoryIds: List<String>,
    ): List<RepositoryInfo> {
        val repositoryById = repositories.associateBy { it.id }
        return repositoryIds.distinct().map { repositoryId ->
            repositoryById[repositoryId]
                ?: throw IllegalArgumentException("找不到服务：$repositoryId，请先重新扫描")
        }
    }

    private fun prepareRepositoryForFeatureBranch(
        config: AppConfig,
        repository: RepositoryInfo,
        featureBranch: String,
        requireAbsent: Boolean,
    ) {
        val service = config.services[repository.id] ?: ServiceConfig(
            repositoryId = repository.id,
            displayName = repository.name,
        )
        require(service.enabled) { "服务已禁用：${service.displayName}" }
        val repoPath = Path.of(repository.rootPath)
        git.fetch(repoPath, service.uatRemote)
        require(git.run(repoPath, "check-ref-format", "--branch", featureBranch, check = false).succeeded) {
            "分支名不合法：$featureBranch"
        }
        val usedBy = git.worktrees(repoPath).firstOrNull { it.branch == featureBranch }
        require(usedBy == null) { "分支 $featureBranch 已被工作树占用：${usedBy?.path}" }
        if (requireAbsent) {
            require(!git.refExists(repoPath, "refs/heads/$featureBranch")) {
                "本地分支已存在：$featureBranch"
            }
            val remotes = git.run(repoPath, "for-each-ref", "--format=%(refname)", "refs/remotes").stdout
            require(remotes.lineSequence().none { it.endsWith("/$featureBranch") }) {
                "远端分支已存在：$featureBranch"
            }
        }
    }

    private fun isRegisteredWorktree(repository: Path, worktree: Path): Boolean {
        if (!worktree.exists() || !worktree.isDirectory()) return false
        val normalized = worktree.toAbsolutePath().normalize()
        return runCatching {
            git.worktrees(repository).any { it.path == normalized }
        }.getOrDefault(false)
    }

    private fun buildWorkspaces(
        config: AppConfig,
        selected: List<RepositoryInfo>,
        existing: List<ServiceWorkspace>,
        taskDirectory: Path,
        taskDirectoryName: String,
        featureBranch: String,
    ): List<ServiceWorkspace> {
        val nameCounts = mutableMapOf<String, Int>()
        existing.forEach { workspace ->
            val key = Path.of(workspace.worktreePath).fileName.toString()
                .substringBeforeLast("-")
                .lowercase()
            // Prefer repository folder base name from display path when possible.
            val repoName = Path.of(workspace.repositoryPath).fileName.toString().lowercase()
            nameCounts[repoName] = nameCounts.getOrDefault(repoName, 0) + 1
            // Also count the actual directory stem for uniqueness checks below.
            nameCounts[key] = nameCounts.getOrDefault(key, 0)
        }
        selected.forEach { repository ->
            val key = repository.name.lowercase()
            nameCounts[key] = nameCounts.getOrDefault(key, 0) + 1
        }
        val usedDirectoryNames = existing.map {
            Path.of(it.worktreePath).fileName.toString().lowercase()
        }.toMutableSet()

        return selected.map { repository ->
            val service = config.services[repository.id] ?: ServiceConfig(
                repositoryId = repository.id,
                displayName = repository.name,
            )
            val ideDirectory = when (service.ideType) {
                IdeType.IDEA -> taskDirectory.resolve("idea-$taskDirectoryName")
                IdeType.WEBSTORM -> taskDirectory.resolve("webstorm-$taskDirectoryName")
            }
            val baseName = if (nameCounts.getOrDefault(repository.name.lowercase(), 0) > 1) {
                "${repository.name}-${repository.id.removePrefix("repo-").take(6)}"
            } else {
                repository.name
            }
            var directoryName = baseName
            var suffix = 2
            while (directoryName.lowercase() in usedDirectoryNames) {
                directoryName = "$baseName-$suffix"
                suffix += 1
            }
            usedDirectoryNames += directoryName.lowercase()
            ServiceWorkspace(
                repositoryId = repository.id,
                serviceName = service.displayName,
                repositoryPath = repository.rootPath,
                worktreePath = ideDirectory.resolve(directoryName).toString(),
                ideType = service.ideType,
                branch = featureBranch,
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
            attachWorktree(config, workspace, repository, target, allowCreateFromBase = true)
            val result = bootstrap.initialize(repository, target, service.bootstrap)
            workspace.copy(
                status = if (result.succeeded) WorkspaceStatus.READY else WorkspaceStatus.READY_WITH_WARNINGS,
                warnings = result.warnings,
            )
        }.getOrElse {
            cleanupFailedTarget(repository, target)
            workspace.copy(
                status = WorkspaceStatus.FAILED,
                warnings = listOf(it.message ?: "创建工作区失败"),
            )
        }
    }

    private fun retryCreateWorkspace(config: AppConfig, workspace: ServiceWorkspace): ServiceWorkspace {
        val repository = Path.of(workspace.repositoryPath)
        val target = Path.of(workspace.worktreePath)
        val service = config.services[workspace.repositoryId] ?: ServiceConfig(
            repositoryId = workspace.repositoryId,
            displayName = workspace.serviceName,
        )
        return runCatching {
            cleanupFailedTarget(repository, target)
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
            attachWorktree(config, workspace, repository, target, allowCreateFromBase = true)
            val result = bootstrap.initialize(repository, target, service.bootstrap)
            workspace.copy(
                status = if (result.succeeded) WorkspaceStatus.READY else WorkspaceStatus.READY_WITH_WARNINGS,
                warnings = result.warnings,
            )
        }.getOrElse {
            cleanupFailedTarget(repository, target)
            workspace.copy(
                status = WorkspaceStatus.FAILED,
                warnings = listOf(it.message ?: "重试创建工作区失败"),
            )
        }
    }

    private fun attachWorktree(
        config: AppConfig,
        workspace: ServiceWorkspace,
        repository: Path,
        target: Path,
        allowCreateFromBase: Boolean = false,
    ) {
        val localRef = "refs/heads/${workspace.branch}"
        if (git.refExists(repository, localRef)) {
            val usedBy = git.worktrees(repository).firstOrNull { it.branch == workspace.branch }
            require(usedBy == null || usedBy.path == target.toAbsolutePath().normalize()) {
                "分支 ${workspace.branch} 已被工作树占用：${usedBy?.path}"
            }
            git.addExistingWorktree(repository, target, workspace.branch)
            return
        }
        val service = config.services[workspace.repositoryId]
            ?: throw IllegalStateException("服务配置不存在：${workspace.serviceName}")
        val remoteRef = "refs/remotes/${service.uatRemote}/${workspace.branch}"
        if (git.refExists(repository, remoteRef)) {
            git.run(
                repository,
                "-c",
                "core.symlinks=false",
                "worktree",
                "add",
                "-b",
                workspace.branch,
                "--track",
                target.toString(),
                "${service.uatRemote}/${workspace.branch}",
            )
            return
        }
        require(allowCreateFromBase) {
            "本地和远端都找不到分支：${workspace.branch}"
        }
        git.addWorktree(repository, target, workspace.branch, service.defaultBaseRef)
    }

    private fun cleanupFailedTarget(repository: Path, target: Path) {
        if (!target.exists()) return
        runCatching { git.removeWorktree(repository, target, force = true) }
        if (target.exists()) {
            deleteRecursively(target)
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!path.exists()) return
        Files.walkFileTree(
            path,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun writeIdeaAggregate(
        taskDirectory: Path,
        taskDirectoryName: String,
        workspaces: List<ServiceWorkspace>,
    ) {
        val ideaWorkspaces = workspaces.filter {
            it.ideType == IdeType.IDEA &&
                it.status != WorkspaceStatus.FAILED &&
                Path.of(it.worktreePath).exists()
        }
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
        val ready = workspaces.filter {
            it.status != WorkspaceStatus.FAILED && Path.of(it.worktreePath).exists()
        }
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
