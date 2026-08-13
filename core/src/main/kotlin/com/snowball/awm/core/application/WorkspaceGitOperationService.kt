package com.snowball.awm.core

import java.nio.file.Path
import java.security.MessageDigest

object RequirementReference {
    private val detailNumber = Regex("/detail/(\\d{4,})(?=[/?#]|$)", RegexOption.IGNORE_CASE)
    private val fallbackNumber = Regex("(?<!\\d)\\d{4,}(?!\\d)")

    fun number(reference: String): String? =
        detailNumber.find(reference)?.groupValues?.getOrNull(1)
            ?: fallbackNumber.findAll(reference).lastOrNull()?.value
}

object CommitMessageTemplate {

    fun render(template: String, requirementLink: String): String {
        val number = RequirementReference.number(requirementLink).orEmpty()
        return template.replace("{num}", number)
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .trim()
    }

    fun requireValid(message: String): String = message.trim().also {
        require(it.isNotBlank()) { "提交信息不能为空" }
    }
}

object WorkspacePushCommand {
    fun build(remote: String, branch: String, setUpstream: Boolean): List<String> {
        require(remote.isNotBlank() && branch.isNotBlank()) { "远程和当前分支不能为空" }
        require(!remote.startsWith("-") && !branch.startsWith("-") && !branch.startsWith("+")) { "拒绝不安全的 Push 参数" }
        return buildList {
            add("push")
            if (setUpstream) add("-u")
            add(remote)
            add("HEAD:refs/heads/$branch")
        }
    }
}

data class WorkspaceGitOperationResult(val message: String)

enum class WorkspaceGitBatchMode {
    COMMIT,
    PUSH,
    COMMIT_AND_PUSH,
}

enum class WorkspaceGitStepState {
    NOT_RUN,
    SUCCESS,
    SKIPPED,
    FAILED,
}

data class WorkspaceGitBatchItemResult(
    val workspacePath: String,
    val serviceName: String,
    val branch: String,
    val commitState: WorkspaceGitStepState,
    val pushState: WorkspaceGitStepState,
    val message: String,
)

data class WorkspaceGitBatchResult(val items: List<WorkspaceGitBatchItemResult>)

data class WorkspaceGitChangePreview(
    val workspacePath: String,
    val branch: String,
    val head: String,
    val upstream: String?,
    val files: List<String>,
    val diffStat: String,
    val fingerprint: String,
)

private data class WorkspaceGitPreflight(
    val workspace: ServiceWorkspace,
    val hasChanges: Boolean,
    val fingerprint: String,
)

class WorkspaceGitOperationService(
    private val git: GitClient = GitClient(),
    private val repositoryLock: RepositoryOperationLock = RepositoryOperationLock(),
) {
    fun preview(workspace: ServiceWorkspace): WorkspaceGitChangePreview {
        val target = validate(workspace)
        val branch = requireNotNull(git.currentBranch(target)) { "Detached HEAD 不能执行 Git 操作" }
        val head = git.run(target, "rev-parse", "HEAD").stdout.trim()
        val upstreamResult = git.run(target, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}", check = false)
        val upstream = upstreamResult.stdout.trim().takeIf { upstreamResult.succeeded && it.isNotBlank() }
        val porcelain = git.run(target, "status", "--short", "--untracked-files=all").stdout
        val diffStat = git.run(target, "diff", "--stat", "HEAD", check = false).stdout.trim()
        return WorkspaceGitChangePreview(
            workspacePath = workspacePathKey(workspace),
            branch = branch,
            head = head,
            upstream = upstream,
            files = porcelain.lineSequence().filter(String::isNotBlank).toList(),
            diffStat = diffStat,
            fingerprint = fingerprint(branch, head, upstream, porcelain),
        )
    }
    fun commit(workspace: ServiceWorkspace, message: String, expectedFingerprint: String? = null): WorkspaceGitOperationResult =
        withRepositoryLock(workspace) { commitUnlocked(workspace, message, expectedFingerprint) }

    private fun commitUnlocked(workspace: ServiceWorkspace, message: String, expectedFingerprint: String? = null): WorkspaceGitOperationResult {
        expectedFingerprint?.let { expected ->
            require(preview(workspace).fingerprint == expected) { "工作区状态已变化，请重新预览后确认：${workspace.operationName()}" }
        }
        val target = validate(workspace)
        val commitMessage = CommitMessageTemplate.requireValid(message)
        git.run(target, "add", "-A")
        require(!git.run(target, "diff", "--cached", "--quiet", check = false).succeeded) { "没有可提交的变更" }
        git.run(target, "commit", "-m", commitMessage)
        return WorkspaceGitOperationResult("已提交 ${workspace.branch}")
    }

    fun push(workspace: ServiceWorkspace): WorkspaceGitOperationResult =
        withRepositoryLock(workspace) { pushUnlocked(workspace) }

    private fun pushUnlocked(workspace: ServiceWorkspace): WorkspaceGitOperationResult {
        val target = validate(workspace)
        val plan = resolvePushPlan(workspace, target)
        val command = WorkspacePushCommand.build(plan.remote, plan.branch, plan.setUpstream)
        git.run(target, *command.toTypedArray())
        return WorkspaceGitOperationResult("已推送 ${plan.remote}/${plan.branch}")
    }

    fun commitAndPush(workspace: ServiceWorkspace, message: String, expectedFingerprint: String? = null): WorkspaceGitOperationResult =
        withRepositoryLock(workspace) {
            commitUnlocked(workspace, message, expectedFingerprint)
            pushUnlocked(workspace)
        }

    /**
     * Performs a complete read-only preflight before the first write. Runtime failures after that
     * point are isolated per physical workspace and returned to the caller.
     */
    fun batch(
        workspaces: List<ServiceWorkspace>,
        mode: WorkspaceGitBatchMode,
        commitMessages: Map<String, String> = emptyMap(),
        expectedFingerprints: Map<String, String> = emptyMap(),
    ): WorkspaceGitBatchResult {
        val unique = workspaces.distinctBy(::workspacePathKey)
        val preflights = unique.map { workspace ->
            val changePreview = preview(workspace)
            expectedFingerprints[workspacePathKey(workspace)]?.let { expected ->
                require(changePreview.fingerprint == expected) { "工作区状态已变化，请重新预览后确认：${workspace.operationName()}" }
            }
            val target = validate(workspace)
            val hasChanges = changePreview.files.isNotEmpty()
            if (mode != WorkspaceGitBatchMode.COMMIT) {
                resolvePushPlan(workspace, target)
            }
            if (hasChanges && mode != WorkspaceGitBatchMode.PUSH) {
                CommitMessageTemplate.requireValid(commitMessages[workspacePathKey(workspace)].orEmpty())
            }
            WorkspaceGitPreflight(workspace, hasChanges, changePreview.fingerprint)
        }

        return WorkspaceGitBatchResult(preflights.map { preflight ->
            executeBatchItem(preflight, mode, commitMessages[workspacePathKey(preflight.workspace)].orEmpty())
        })
    }

    private fun executeBatchItem(
        preflight: WorkspaceGitPreflight,
        mode: WorkspaceGitBatchMode,
        commitMessage: String,
    ): WorkspaceGitBatchItemResult = withRepositoryLock(preflight.workspace) {
        val workspace = preflight.workspace
        var commitState = WorkspaceGitStepState.NOT_RUN
        var pushState = WorkspaceGitStepState.NOT_RUN
        val messages = mutableListOf<String>()

        if (mode != WorkspaceGitBatchMode.PUSH) {
            if (!preflight.hasChanges) {
                commitState = WorkspaceGitStepState.SKIPPED
                messages += "没有需要提交的变更"
            } else {
                runCatching { commitUnlocked(workspace, commitMessage, preflight.fingerprint) }
                    .onSuccess { commitState = WorkspaceGitStepState.SUCCESS; messages += it.message }
                    .onFailure { error ->
                        commitState = WorkspaceGitStepState.FAILED
                        messages += (error.message ?: "提交失败")
                    }
            }
        }

        val shouldPush = mode == WorkspaceGitBatchMode.PUSH ||
            mode == WorkspaceGitBatchMode.COMMIT_AND_PUSH && commitState != WorkspaceGitStepState.FAILED
        if (shouldPush) {
            runCatching { pushUnlocked(workspace) }
                .onSuccess { pushState = WorkspaceGitStepState.SUCCESS; messages += it.message }
                .onFailure { error ->
                    pushState = WorkspaceGitStepState.FAILED
                    messages += (error.message ?: "推送失败")
                }
        }

        WorkspaceGitBatchItemResult(
            workspacePath = workspacePathKey(workspace),
            serviceName = workspace.moduleName.ifBlank { workspace.serviceName },
            branch = workspace.branch,
            commitState = commitState,
            pushState = pushState,
            message = messages.joinToString("；"),
        )
    }

    private fun <T> withRepositoryLock(workspace: ServiceWorkspace, block: () -> T): T {
        val target = Path.of(workspace.worktreePath).toAbsolutePath().normalize()
        val commonDirectory = when (workspace.strategy) {
            WorkspaceStrategy.STANDARD_WORKTREE ->
                git.commonDirectory(Path.of(workspace.repositoryPath).toAbsolutePath().normalize())
            WorkspaceStrategy.INDEPENDENT_CLONE -> git.commonDirectory(target)
        }.toAbsolutePath().normalize()
        return repositoryLock.withLock(commonDirectory, block)
    }

    private fun validate(workspace: ServiceWorkspace): Path {
        val target = Path.of(workspace.worktreePath).toAbsolutePath().normalize()
        require(git.topLevel(target) == target) { "工作区路径不再是 Git 顶层目录：$target" }
        val recordedRepository = Path.of(workspace.repositoryPath).toAbsolutePath().normalize()
        when (workspace.strategy) {
            WorkspaceStrategy.STANDARD_WORKTREE -> require(git.commonDirectory(target) == git.commonDirectory(recordedRepository)) { "Worktree Git 身份与任务记录不一致" }
            WorkspaceStrategy.INDEPENDENT_CLONE -> require(git.remoteUrl(target)?.trim() == workspace.originUrl?.trim()) { "独立克隆 origin 与任务记录不一致" }
        }
        require(git.currentBranch(target) == workspace.branch) { "当前分支与任务记录不一致" }
        require(git.status(target).operationInProgress == null) { "存在进行中的 Git 操作，完成或中止后再试" }
        require(git.run(target, "config", "user.name", check = false).stdout.isNotBlank()) { "Git user.name 未配置" }
        require(git.run(target, "config", "user.email", check = false).stdout.isNotBlank()) { "Git user.email 未配置" }
        return target
    }

    private data class PushPlan(
        val remote: String,
        val branch: String,
        val setUpstream: Boolean,
    )

    private fun resolvePushPlan(workspace: ServiceWorkspace, target: Path): PushPlan {
        val branch = git.currentBranch(target) ?: error("Detached HEAD 不能推送")
        val upstream = git.run(target, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}", check = false)
        val upstreamName = upstream.stdout.trim().takeIf { upstream.succeeded && it.contains('/') }
        val remote = upstreamName?.substringBefore('/') ?: workspace.pushRemote.ifBlank { "origin" }
        require(git.remoteUrl(target, remote) != null) { "Push 远程不存在：$remote" }
        val remoteBranch = git.run(target, "ls-remote", "--exit-code", "--heads", remote, "refs/heads/$branch", check = false)
        if (!remoteBranch.succeeded && remoteBranch.exitCode != 2) {
            throw GitException("远程分支检查失败：$remote/$branch", remoteBranch)
        }
        return PushPlan(remote, branch, upstreamName != "$remote/$branch" || !remoteBranch.succeeded)
    }

    companion object {
        fun workspacePathKey(workspace: ServiceWorkspace): String =
            Path.of(workspace.worktreePath).toAbsolutePath().normalize().toString()

        private fun fingerprint(branch: String, head: String, upstream: String?, porcelain: String): String {
            val bytes = "$branch\u0000$head\u0000${upstream.orEmpty()}\u0000$porcelain".toByteArray()
            return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
    }

    private fun ServiceWorkspace.operationName(): String = moduleName.ifBlank { serviceName }
}
