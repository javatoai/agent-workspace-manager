package com.snowball.taskwt.core

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

@Serializable
enum class MergeMode {
    ALREADY_MERGED,
    FAST_FORWARD,
    MERGE_COMMIT
}

@Serializable
data class FeatureSyncStatus(
    val remoteExists: Boolean,
    val ahead: Int,
    val behind: Int,
) {
    val canPush: Boolean get() = behind == 0
    val pushRequired: Boolean get() = !remoteExists || ahead > 0
}

@Serializable
data class TagPreflight(
    val folderName: String,
    val serviceName: String,
    val featureBranch: String,
    val featureSha: String,
    val remote: String,
    val testBranch: String,
    val testSha: String,
    val featureSync: FeatureSyncStatus,
    val mergeMode: MergeMode,
    val commitList: List<String>,
    val diffStat: String,
    val estimatedTag: String,
)

class TagBuildService(
    private val paths: ApplicationPaths = ApplicationPaths.systemDefault(),
    private val git: GitClient = GitClient(),
    private val manifests: ManifestStore = ManifestStore(),
    private val operations: TagOperationStore = TagOperationStore(),
    private val clock: Clock = Clock.systemUTC(),
    private val events: EventSink = JsonlEventSink(paths, clock),
    private val workspaceLifecycle: WorkspaceLifecycle = GitWorkspaceLifecycle(git),
    private val taskLock: TaskOperationLock = FileTaskOperationLock(paths),
) {
    companion object {
        private val auditTimestampFormatter = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneOffset.UTC)
    }

    fun buildBatch(
        config: AppConfig,
        taskDirectory: Path,
        repositoryIds: List<String>,
    ): List<TagOperation> {
        if (repositoryIds.isEmpty()) return emptyList()
        // Task lifecycle uses an exclusive lock. Batch operations intentionally
        // serialize within one task so archive/delete cannot interleave and a
        // non-blocking file lock does not turn sibling entries into false failures.
        return taskLock.withLock(taskDirectory) {
            repositoryIds.map { repositoryId ->
                buildSafelyUnlocked(config, taskDirectory, repositoryId)
            }
        }
    }

    private fun buildSafelyUnlocked(
        config: AppConfig,
        taskDirectory: Path,
        repositoryId: String,
    ): TagOperation = runCatching {
        buildUnlocked(config, taskDirectory, repositoryId)
    }.getOrElse { error ->
        val manifest = manifests.load(taskDirectory)
        val workspace = resolveWorkspace(manifest, repositoryId)
            ?: throw error
        val target = runCatching { TagPolicy.resolve(config, manifest, repositoryId) }.getOrNull()
        val now = Instant.now(clock).toString()
        TagOperation(
            operationId = UUID.randomUUID().toString(),
            folderName = manifest.folderName,
            serviceName = workspace.serviceName,
            repositoryId = workspace.repositoryId,
            featureBranch = workspace.branch,
            testBranch = target?.uatBranch.orEmpty(),
            remote = target?.remote.orEmpty(),
            state = TagOperationState.FAILED,
            createdAt = now,
            updatedAt = now,
            message = error.message ?: error::class.simpleName ?: "构建失败",
            groupServiceId = workspace.groupServiceId,
            moduleId = workspace.moduleId,
        ).also { operations.save(taskDirectory, it) }
    }

    fun preflight(
        config: AppConfig,
        taskDirectory: Path,
        repositoryId: String,
    ): TagPreflight = taskLock.withLock(taskDirectory) {
        val manifest = manifests.load(taskDirectory)
        val target = TagPolicy.resolve(config, manifest, repositoryId)
        val workspace = target.workspace
        val service = target.asLegacyServiceConfig()
        val validated = workspaceLifecycle.validateForMutation(config, taskDirectory, manifest, workspace)
        val repository = validated.repository
        val worktree = validated.worktree

        withRepositoryLock(repository) {
            ensureCleanFeatureWorktree(worktree)
            git.fetch(repository, service.uatRemote)
            val featureSha = git.resolve(repository, workspace.branch)
            val remoteFeatureRef = "${service.uatRemote}/${workspace.branch}"
            val sync = featureSync(repository, featureSha, remoteFeatureRef)
            require(sync.canPush) {
                "远端特性分支领先本地 ${sync.behind} 个提交，请先手工同步；工具不会自动 pull/rebase"
            }
            val testRef = "${service.uatRemote}/${service.uatBranch}"
            val testSha = git.resolve(repository, testRef)
            val mergeMode = when {
                git.isAncestor(repository, featureSha, testSha) -> MergeMode.ALREADY_MERGED
                git.isAncestor(repository, testSha, featureSha) -> MergeMode.FAST_FORWARD
                else -> MergeMode.MERGE_COMMIT
            }
            verifyMergeInTemporaryWorktree(repository, testSha, featureSha, workspace.serviceName)
            val commitList = git.run(
                repository,
                "log",
                "--format=%h %s",
                "--no-merges",
                "$testSha..$featureSha",
            ).stdout.lineSequence().filter { it.isNotBlank() }.toList()
            val diffStat = git.run(repository, "diff", "--stat", testSha, featureSha).stdout.trim()
            val estimatedTag = nextTag(repository, testSha, service.initialUatTag)
            TagPreflight(
                folderName = manifest.folderName,
                serviceName = workspace.serviceName,
                featureBranch = workspace.branch,
                featureSha = featureSha,
                remote = service.uatRemote,
                testBranch = service.uatBranch,
                testSha = testSha,
                featureSync = sync,
                mergeMode = mergeMode,
                commitList = commitList,
                diffStat = diffStat,
                estimatedTag = estimatedTag,
            )
        }
    }

    fun build(
        config: AppConfig,
        taskDirectory: Path,
        repositoryId: String,
    ): TagOperation = taskLock.withLock(taskDirectory) {
        buildUnlocked(config, taskDirectory, repositoryId)
    }

    private fun buildUnlocked(
        config: AppConfig,
        taskDirectory: Path,
        repositoryId: String,
    ): TagOperation {
        val manifest = manifests.load(taskDirectory)
        val target = TagPolicy.resolve(config, manifest, repositoryId)
        val workspace = target.workspace
        val service = target.asLegacyServiceConfig()
        val validated = workspaceLifecycle.validateForMutation(config, taskDirectory, manifest, workspace)
        val repository = validated.repository
        val now = Instant.now(clock).toString()
        var operation = TagOperation(
            operationId = UUID.randomUUID().toString(),
            folderName = manifest.folderName,
            serviceName = workspace.serviceName,
            repositoryId = workspace.repositoryId,
            featureBranch = workspace.branch,
            testBranch = service.uatBranch,
            remote = service.uatRemote,
            state = TagOperationState.CREATED,
            createdAt = now,
            updatedAt = now,
            groupServiceId = workspace.groupServiceId,
            moduleId = workspace.moduleId,
        )
        operations.save(taskDirectory, operation)
        events.info(
            event = "tag.build.started",
            message = "开始 UAT Tag 构建",
            metadata = mapOf(
                "operationId" to operation.operationId,
                "folderName" to operation.folderName,
                "service" to operation.serviceName,
            ),
            clock = clock,
        )

        return withRepositoryLock(repository) {
            try {
                val preview = preflightUnlocked(service, manifest, workspace, validated.repository, validated.worktree)
                operation = transition(
                    taskDirectory,
                    operation,
                    TagOperationState.PREFLIGHT_PASSED,
                    featureSha = preview.featureSha,
                    testSha = preview.testSha,
                )
                if (preview.featureSync.pushRequired) {
                    git.run(
                        repository,
                        "push",
                        "--set-upstream",
                        service.uatRemote,
                        "${workspace.branch}:${workspace.branch}",
                    )
                }
                operation = transition(taskDirectory, operation, TagOperationState.FEATURE_PUSHED)

                val mergeResult = mergeAndPushTestBranch(
                    repository = repository,
                    featureSha = preview.featureSha,
                    service = service,
                    serviceName = workspace.serviceName,
                )
                if (mergeResult.conflicts.isNotEmpty()) {
                    operation = transition(
                        taskDirectory,
                        operation,
                        TagOperationState.CONFLICT,
                        message = "自动合并检测到冲突，请手工合并并推送 ${service.uatBranch} 后重试",
                        conflictFiles = mergeResult.conflicts,
                    )
                    recordHistory(taskDirectory, operation)
                    return@withRepositoryLock operation
                }
                operation = transition(
                    taskDirectory,
                    operation,
                    TagOperationState.TEST_BRANCH_PUSHED,
                    testSha = mergeResult.testSha,
                )

                var tag = nextTag(repository, mergeResult.testSha, service.initialUatTag)
                var pushed = false
                for (attempt in 0..1) {
                    createOrValidateLocalTag(
                        repository,
                        tag,
                        mergeResult.testSha,
                        auditMessage(manifest, workspace, service, preview.featureSha, mergeResult.testSha),
                    )
                    operation = transition(
                        taskDirectory,
                        operation,
                        TagOperationState.LOCAL_TAG_CREATED,
                        tag = tag,
                    )
                    try {
                        pushTag(repository, service.uatRemote, tag, mergeResult.testSha)
                        pushed = true
                        break
                    } catch (collision: TagCollisionException) {
                        if (attempt == 1) throw collision
                        git.run(repository, "tag", "-d", tag)
                        tag = TagVersioning.next(tag)
                    }
                }
                check(pushed) { "Tag 推送未完成" }
                operation = transition(taskDirectory, operation, TagOperationState.TAG_PUSHED)
                operation = transition(
                    taskDirectory,
                    operation,
                    TagOperationState.SUCCESS,
                    message = "${workspace.serviceName}：$tag",
                )
                recordHistory(taskDirectory, operation)
                operation
            } catch (conflict: MergeConflictException) {
                operation = transition(
                    taskDirectory,
                    operation,
                    TagOperationState.CONFLICT,
                    message = "自动合并检测到冲突，请手工合并并推送 ${service.uatBranch} 后重试",
                    conflictFiles = conflict.files,
                )
                recordHistory(taskDirectory, operation)
                operation
            } catch (error: Throwable) {
                val partial = operation.state == TagOperationState.TEST_BRANCH_PUSHED ||
                    operation.state == TagOperationState.LOCAL_TAG_CREATED
                operation = transition(
                    taskDirectory,
                    operation,
                    if (partial) TagOperationState.PARTIAL else TagOperationState.FAILED,
                    message = error.message ?: "构建失败",
                )
                recordHistory(taskDirectory, operation)
                operation
            }
        }
    }

    fun resumePartial(
        config: AppConfig,
        taskDirectory: Path,
        operationId: String,
    ): TagOperation = taskLock.withLock(taskDirectory) {
        var operation = operations.load(taskDirectory, operationId)
        require(operation.state == TagOperationState.PARTIAL) { "只有 PARTIAL 操作可以恢复" }
        val manifest = manifests.load(taskDirectory)
        val target = TagPolicy.resolve(config, manifest, "${operation.groupServiceId}:${operation.moduleId}")
        val service = target.asLegacyServiceConfig()
        val workspace = target.workspace
        val validated = workspaceLifecycle.validateForMutation(config, taskDirectory, manifest, workspace)
        val repository = validated.repository
        val tag = operation.tag ?: throw IllegalStateException("操作没有可恢复的本地 Tag")
        val testSha = operation.testSha ?: throw IllegalStateException("操作没有测试分支提交")

        withRepositoryLock(repository) {
            try {
                createOrValidateLocalTag(
                    repository,
                    tag,
                    testSha,
                    auditMessage(manifest, workspace, service, operation.featureSha.orEmpty(), testSha),
                )
                pushTag(repository, service.uatRemote, tag, testSha)
                operation = transition(taskDirectory, operation, TagOperationState.TAG_PUSHED)
                operation = transition(
                    taskDirectory,
                    operation,
                    TagOperationState.SUCCESS,
                    message = "${workspace.serviceName}：$tag",
                )
                recordHistory(taskDirectory, operation)
                operation
            } catch (error: Throwable) {
                operation = transition(
                    taskDirectory,
                    operation,
                    TagOperationState.PARTIAL,
                    message = error.message ?: "恢复失败",
                )
                recordHistory(taskDirectory, operation)
                operation
            }
        }
    }

    private fun preflightUnlocked(
        service: ServiceConfig,
        manifest: TaskManifest,
        workspace: ServiceWorkspace,
        repository: Path,
        worktree: Path,
    ): TagPreflight {
        ensureCleanFeatureWorktree(worktree)
        git.fetch(repository, service.uatRemote)
        val featureSha = git.resolve(repository, workspace.branch)
        val remoteFeatureRef = "${service.uatRemote}/${workspace.branch}"
        val sync = featureSync(repository, featureSha, remoteFeatureRef)
        require(sync.canPush) {
            "远端特性分支领先本地 ${sync.behind} 个提交，请先手工同步"
        }
        val testRef = "${service.uatRemote}/${service.uatBranch}"
        val testSha = git.resolve(repository, testRef)
        val mergeMode = when {
            git.isAncestor(repository, featureSha, testSha) -> MergeMode.ALREADY_MERGED
            git.isAncestor(repository, testSha, featureSha) -> MergeMode.FAST_FORWARD
            else -> MergeMode.MERGE_COMMIT
        }
        verifyMergeInTemporaryWorktree(repository, testSha, featureSha, workspace.serviceName)
        return TagPreflight(
            folderName = manifest.folderName,
            serviceName = workspace.serviceName,
            featureBranch = workspace.branch,
            featureSha = featureSha,
            remote = service.uatRemote,
            testBranch = service.uatBranch,
            testSha = testSha,
            featureSync = sync,
            mergeMode = mergeMode,
            commitList = git.run(
                repository,
                "log",
                "--format=%h %s",
                "--no-merges",
                "$testSha..$featureSha",
            ).stdout.lineSequence().filter { it.isNotBlank() }.toList(),
            diffStat = git.run(repository, "diff", "--stat", testSha, featureSha).stdout.trim(),
            estimatedTag = nextTag(repository, testSha, service.initialUatTag),
        )
    }

    private fun resolveWorkspace(manifest: TaskManifest, selection: String): ServiceWorkspace? {
        val matches = manifest.services.filter { it.selectionKey == selection || it.repositoryId == selection }
        if (matches.size > 1) throw IllegalArgumentException("Tag 目标不唯一，请选择具体模块：$selection")
        return matches.singleOrNull()
    }

    private data class MergePushResult(
        val testSha: String,
        val conflicts: List<String> = emptyList(),
    )

    private fun mergeAndPushTestBranch(
        repository: Path,
        featureSha: String,
        service: ServiceConfig,
        serviceName: String,
    ): MergePushResult {
        repeat(2) { attempt ->
            git.fetch(repository, service.uatRemote)
            val remoteTestRef = "${service.uatRemote}/${service.uatBranch}"
            val remoteTestSha = git.resolve(repository, remoteTestRef)
            if (git.isAncestor(repository, featureSha, remoteTestSha)) {
                return MergePushResult(remoteTestSha)
            }
            val temporary = temporaryWorktreePath(repository, "$serviceName-build-${attempt + 1}")
            try {
                git.addDetachedWorktree(repository, temporary, remoteTestSha)
                val merge = git.run(
                    temporary,
                    "merge",
                    "--no-edit",
                    featureSha,
                    check = false,
                )
                if (!merge.succeeded) {
                    val conflicts = conflictFiles(temporary)
                    git.run(temporary, "merge", "--abort", check = false)
                    if (conflicts.isNotEmpty()) return MergePushResult(remoteTestSha, conflicts)
                    throw GitException("合并测试分支失败", merge)
                }
                val mergedSha = git.resolve(temporary, "HEAD")
                val push = git.run(
                    temporary,
                    "push",
                    service.uatRemote,
                    "HEAD:refs/heads/${service.uatBranch}",
                    check = false,
                )
                if (push.succeeded) {
                    git.fetch(repository, service.uatRemote)
                    val verified = git.resolve(repository, remoteTestRef)
                    require(verified == mergedSha) {
                        "推送后远端 ${service.uatBranch} 的 SHA 校验失败"
                    }
                    return MergePushResult(mergedSha)
                }
                if (attempt == 1) throw GitException("测试分支在推送期间被更新，重试后仍失败", push)
            } finally {
                cleanupTemporaryWorktree(repository, temporary)
            }
        }
        error("无法更新测试分支")
    }

    private fun verifyMergeInTemporaryWorktree(
        repository: Path,
        testSha: String,
        featureSha: String,
        serviceName: String,
    ) {
        if (git.isAncestor(repository, featureSha, testSha)) return
        val temporary = temporaryWorktreePath(repository, "$serviceName-preflight")
        try {
            git.addDetachedWorktree(repository, temporary, testSha)
            val merge = git.run(
                temporary,
                "merge",
                "--no-commit",
                "--no-ff",
                featureSha,
                check = false,
            )
            val conflicts = conflictFiles(temporary)
            git.run(temporary, "merge", "--abort", check = false)
            if (conflicts.isNotEmpty()) {
                throw MergeConflictException(conflicts)
            }
            if (!merge.succeeded) throw GitException("合并预检失败", merge)
        } finally {
            cleanupTemporaryWorktree(repository, temporary)
        }
    }

    private fun conflictFiles(worktree: Path): List<String> =
        git.run(worktree, "diff", "--name-only", "--diff-filter=U", check = false)
            .stdout
            .lineSequence()
            .filter { it.isNotBlank() }
            .toList()

    private fun featureSync(
        repository: Path,
        featureSha: String,
        remoteFeatureRef: String,
    ): FeatureSyncStatus {
        val exists = git.run(
            repository,
            "rev-parse",
            "--verify",
            "$remoteFeatureRef^{commit}",
            check = false,
        ).succeeded
        if (!exists) return FeatureSyncStatus(remoteExists = false, ahead = 0, behind = 0)
        val ahead = git.run(repository, "rev-list", "--count", "$remoteFeatureRef..$featureSha")
            .stdout.trim().toInt()
        val behind = git.run(repository, "rev-list", "--count", "$featureSha..$remoteFeatureRef")
            .stdout.trim().toInt()
        return FeatureSyncStatus(remoteExists = true, ahead = ahead, behind = behind)
    }

    private fun ensureCleanFeatureWorktree(worktree: Path) {
        require(worktree.exists()) { "特性工作区不存在：$worktree" }
        val status = git.status(worktree)
        require(!status.staged && !status.unstaged && !status.untracked) {
            "特性工作区存在未提交改动，请先提交或清理"
        }
        require(status.operationInProgress == null) {
            "特性工作区正在执行 ${status.operationInProgress}"
        }
    }

    private fun nextTag(repository: Path, commit: String, initialTag: String?): String {
        val tags = git.run(
            repository,
            "for-each-ref",
            "--merged",
            commit,
            "--format=%(creatordate:unix) %(refname:short)",
            "refs/tags",
        ).stdout.lineSequence().mapNotNull { line ->
            val timestamp = line.substringBefore(' ').toLongOrNull() ?: return@mapNotNull null
            val name = line.substringAfter(' ', missingDelimiterValue = "").trim()
            name.takeIf { it.isNotEmpty() }?.let { VersionTag(it, timestamp) }
        }.toList()
        val latest = TagVersioning.latest(tags)
        if (latest != null) return TagVersioning.next(latest)
        val initial = initialTag?.trim()
            ?: throw IllegalStateException("仓库没有可用的历史 Tag，请先配置 initialUatTag")
        require(TagVersioning.validPattern.matches(initial)) {
            "initialUatTag 格式不合法：$initial"
        }
        return initial
    }

    private fun createOrValidateLocalTag(
        repository: Path,
        tag: String,
        commit: String,
        message: String,
    ) {
        val existing = git.run(repository, "rev-parse", "--verify", "refs/tags/$tag^{commit}", check = false)
        if (existing.succeeded) {
            require(existing.stdout.trim() == commit) { "本地 Tag $tag 已指向其他提交" }
            return
        }
        git.run(repository, "tag", "-a", tag, commit, "-m", message)
    }

    private fun pushTag(repository: Path, remote: String, tag: String, commit: String) {
        val remoteBefore = remoteTagSha(repository, remote, tag)
        if (remoteBefore != null) {
            if (remoteBefore != commit) {
                throw TagCollisionException(tag, remoteBefore, commit)
            }
            return
        }
        val push = git.run(repository, "push", remote, "refs/tags/$tag:refs/tags/$tag", check = false)
        if (!push.succeeded) {
            val remoteAfter = remoteTagSha(repository, remote, tag)
            if (remoteAfter != null && remoteAfter != commit) {
                throw TagCollisionException(tag, remoteAfter, commit)
            }
            if (remoteAfter == commit) return
            throw GitException("推送 Tag 失败", push)
        }
    }

    private fun remoteTagSha(repository: Path, remote: String, tag: String): String? {
        val result = git.run(repository, "ls-remote", remote, "refs/tags/$tag^{}", check = false)
        val dereferenced = result.stdout.lineSequence().firstOrNull()?.substringBefore('\t')?.trim()
        if (!dereferenced.isNullOrBlank()) return dereferenced
        val direct = git.run(repository, "ls-remote", remote, "refs/tags/$tag", check = false)
        return direct.stdout.lineSequence().firstOrNull()?.substringBefore('\t')?.trim()?.ifBlank { null }
    }

    private fun auditMessage(
        manifest: TaskManifest,
        workspace: ServiceWorkspace,
        service: ServiceConfig,
        featureSha: String,
        testSha: String,
    ): String = buildString {
        appendLine("${service.tagMessagePrefix} build")
        appendLine("Task: ${manifest.folderName}")
        if (manifest.requirementLink.isNotBlank()) {
            appendLine("需求链接：${manifest.requirementLink.trim()}")
        }
        appendLine("Builder: ${System.getProperty("user.name")}")
        append("Timestamp: ${auditTimestampFormatter.format(Instant.now(clock))}")
    }

    private fun temporaryWorktreePath(repository: Path, label: String): Path {
        val repoHash = repositoryHash(git.commonDirectory(repository))
        val safeLabel = label.replace(Regex("""[^A-Za-z0-9._-]"""), "-").take(50)
        val parent = paths.temp.resolve("tag-build").resolve(repoHash)
        parent.createDirectories()
        return parent.resolve("$safeLabel-${UUID.randomUUID()}")
    }

    private fun cleanupTemporaryWorktree(repository: Path, temporary: Path) {
        if (temporary.exists()) {
            git.run(temporary, "merge", "--abort", check = false)
            git.removeWorktree(repository, temporary, force = true)
        }
        git.run(repository, "worktree", "prune", check = false)
    }

    private fun transition(
        taskDirectory: Path,
        operation: TagOperation,
        state: TagOperationState,
        featureSha: String? = operation.featureSha,
        testSha: String? = operation.testSha,
        tag: String? = operation.tag,
        message: String? = operation.message,
        conflictFiles: List<String> = operation.conflictFiles,
    ): TagOperation = operation.copy(
        state = state,
        updatedAt = Instant.now(clock).toString(),
        featureSha = featureSha,
        testSha = testSha,
        tag = tag,
        message = message,
        conflictFiles = conflictFiles,
    ).also { operations.save(taskDirectory, it) }

    private fun recordHistory(taskDirectory: Path, operation: TagOperation) {
        operations.appendHistory(
            taskDirectory,
            TagBuildHistoryEntry(
                operationId = operation.operationId,
                timestamp = operation.updatedAt,
                folderName = operation.folderName,
                serviceName = operation.serviceName,
                featureBranch = operation.featureBranch,
                testBranch = operation.testBranch,
                tag = operation.tag,
                state = operation.state,
                message = operation.message,
            ),
        )
        val metadata = mapOf(
            "operationId" to operation.operationId,
            "folderName" to operation.folderName,
            "service" to operation.serviceName,
            "state" to operation.state.name,
            "tag" to operation.tag.orEmpty(),
        )
        if (operation.state == TagOperationState.SUCCESS) {
            events.info(
                event = "tag.build.completed",
                message = "UAT Tag 构建成功",
                metadata = metadata,
                clock = clock,
            )
        } else {
            events.error(
                event = "tag.build.completed",
                message = operation.message ?: "UAT Tag 构建未成功",
                metadata = metadata,
                clock = clock,
            )
        }
    }

    private fun <T> withRepositoryLock(repository: Path, block: () -> T): T {
        val lockPath = paths.locks.resolve("${repositoryHash(git.commonDirectory(repository))}.lock")
        return FileLocking.withExclusiveLock(
            lockPath,
            "repository is already running another Tag operation",
            block,
        )
    }

    private fun repositoryHash(path: Path): String =
        MessageDigest.getInstance("SHA-256")
            .digest(path.toAbsolutePath().normalize().toString().lowercase(Locale.ROOT).toByteArray(StandardCharsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
}

class MergeConflictException(
    val files: List<String>,
) : RuntimeException("合并存在冲突：${files.joinToString(", ")}")

class TagCollisionException(
    tag: String,
    remoteCommit: String,
    expectedCommit: String,
) : RuntimeException("远端 Tag $tag 已指向 $remoteCommit，预期为 $expectedCommit")
