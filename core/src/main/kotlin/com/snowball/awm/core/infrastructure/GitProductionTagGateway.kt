package com.snowball.awm.core

import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.io.path.Path
import kotlin.io.path.createDirectories

class GitProductionTagGateway(
    private val paths: ApplicationPaths = ApplicationPaths.systemDefault(),
    private val git: GitClient = GitClient(),
    private val locks: RepositoryOperationLock = RepositoryOperationLock(paths),
    private val now: () -> String = { AwmTime.format(Instant.now()) },
    private val operationId: () -> String = { UUID.randomUUID().toString().take(8) },
) : ProductionGitGateway {
    override fun inspectBaseline(
        repository: RepositoryConfig,
        productionTag: String,
    ): ProductionBaselineEvidence = locked(repository) { root ->
        git.fetch(root)
        git.fetchTags(root)
        val tagRef = "refs/tags/$productionTag"
        require(git.refExists(root, tagRef)) { "生产 Tag 在远端仓库中不存在：$productionTag" }
        val productionTagSha = git.resolve(root, tagRef)
        val masterSha = git.resolve(root, "refs/remotes/origin/master")
        ProductionBaselineEvidence(
            productionTag = productionTag,
            productionTagSha = productionTagSha,
            masterSha = masterSha,
            state = if (git.isAncestor(root, productionTagSha, masterSha)) {
                ProductionBaselineState.ALREADY_CONTAINED
            } else {
                ProductionBaselineState.MERGE_REQUIRED
            },
        )
    }

    override fun formalTags(repository: RepositoryConfig): List<String> = locked(repository) { root ->
        remoteTags(root).filter(FORMAL_TAG::matches).sortedWith(semanticVersionComparator)
    }

    override fun mergeProduction(
        repository: RepositoryConfig,
        pipeline: ProductionTagPipeline,
    ): ProductionBranchWrite = locked(repository) { root ->
        git.fetch(root)
        git.fetchTags(root)
        val remoteMaster = git.resolve(root, "refs/remotes/origin/master")
        check(remoteMaster == pipeline.masterSha) { "master 已发生变化，请先刷新生产基线" }
        check(git.resolve(root, "refs/tags/${pipeline.productionTag}") == pipeline.productionTagSha) {
            "生产 Tag 已发生变化，请先刷新生产基线"
        }
        temporaryWorktree(root, repository, "production", remoteMaster) { worktree, sourceBranch ->
            val merge = git.run(
                worktree,
                "merge",
                "--no-ff",
                pipeline.productionTagSha,
                "-m",
                "Merge production tag ${pipeline.productionTag} into master",
                check = false,
            )
            if (!merge.succeeded) {
                val files = conflictFiles(worktree)
                throw GitException(
                    if (files.isEmpty()) "生产 Tag 合并到 master 失败" else "生产 Tag 合并到 master 存在冲突：${files.joinToString()} ",
                    merge,
                )
            }
            val expectedCommit = git.resolve(worktree, "HEAD")
            pushBranchOrRequest(root, repository, sourceBranch, "master", expectedCommit)
        }
    }

    override fun createRelease(
        repository: RepositoryConfig,
        branch: String,
        masterSha: String,
    ): String = locked(repository) { root ->
        git.fetch(root)
        val remoteMaster = git.resolve(root, "refs/remotes/origin/master")
        check(remoteMaster == masterSha) { "master 已发生变化，请先刷新生产基线" }
        check(remoteBranchCommit(root, branch) == null) { "远端 Release 分支已存在：$branch" }
        temporaryWorktree(root, repository, "release", masterSha) { worktree, _ ->
            val result = git.run(worktree, "push", "origin", "HEAD:refs/heads/$branch", check = false)
            if (!result.succeeded) throw GitException("创建远端 Release 分支失败：$branch", result)
            val remoteSha = remoteBranchCommit(root, branch)
            check(remoteSha == masterSha) { "Release 分支推送后校验失败：$branch" }
            remoteSha
        }
    }

    override fun resolveFeatures(
        repository: RepositoryConfig,
        branches: List<String>,
    ): List<ProductionFeatureSelection> = locked(repository) { root ->
        git.fetch(root)
        branches.map { branch ->
            require(branch.isNotBlank() && !branch.startsWith("-") && !branch.contains("..")) {
                "Feature 分支名不合法：$branch"
            }
            val sha = remoteBranchCommit(root, branch) ?: error("远端 Feature 分支不存在：$branch")
            ProductionFeatureSelection(branch, sha)
        }
    }

    override fun mergeFeatures(
        repository: RepositoryConfig,
        pipeline: ProductionTagPipeline,
        features: List<ProductionFeatureSelection>,
    ): ProductionFeatureWrite = locked(repository) { root ->
        git.fetch(root)
        val releaseSha = pipeline.releaseSha ?: error("Release 尚未创建")
        val currentReleaseSha = remoteBranchCommit(root, pipeline.releaseBranch)
            ?: error("远端 Release 分支不存在：${pipeline.releaseBranch}")
        check(currentReleaseSha == releaseSha) { "Release 分支已发生变化，请刷新后重试" }
        features.forEach { feature ->
            check(remoteBranchCommit(root, feature.branch) == feature.sha) {
                "Feature 分支已发生变化，请重新选择：${feature.branch}"
            }
        }
        temporaryWorktree(root, repository, "features", releaseSha) { worktree, sourceBranch ->
            val merges = mutableListOf<ProductionFeatureMergeRecord>()
            for (feature in features) {
                val result = git.run(
                    worktree,
                    "merge",
                    "--no-ff",
                    feature.sha,
                    "-m",
                    "Merge ${feature.branch} into ${pipeline.releaseBranch}",
                    check = false,
                )
                if (!result.succeeded) {
                    return@temporaryWorktree ProductionFeatureWrite.Conflict(
                        listOf(ProductionConflict(feature.branch, conflictFiles(worktree), feature.sha)),
                    )
                }
                merges += ProductionFeatureMergeRecord(
                    branch = feature.branch,
                    sourceSha = feature.sha,
                    mergeCommit = git.resolve(worktree, "HEAD"),
                    completedAt = now(),
                )
            }
            val expectedCommit = git.resolve(worktree, "HEAD")
            val push = git.run(
                worktree,
                "push",
                "origin",
                "HEAD:refs/heads/${pipeline.releaseBranch}",
                check = false,
            )
            if (push.succeeded) {
                ProductionFeatureWrite.Direct(expectedCommit, merges)
            } else if (isPermissionFailure(push)) {
                val request = pushSourceBranch(root, repository, sourceBranch, pipeline.releaseBranch, expectedCommit)
                ProductionFeatureWrite.AwaitingRequest(expectedCommit, merges, request)
            } else {
                throw GitException("Feature 合并结果推送失败：${pipeline.releaseBranch}", push)
            }
        }
    }

    override fun tagsForBase(
        repository: RepositoryConfig,
        baseVersion: String,
    ): List<ProductionRemoteTag> = locked(repository) { root ->
        git.fetchTags(root)
        remoteTags(root)
            .filter { it == baseVersion || it.matches(Regex("${Regex.escape(baseVersion)}\\.\\d+")) }
            .map { tag -> ProductionRemoteTag(tag, git.resolve(root, "refs/tags/$tag")) }
    }

    override fun pushTag(
        repository: RepositoryConfig,
        tag: String,
        releaseSha: String,
    ): ProductionTagPush = locked(repository) { root ->
        git.fetch(root)
        git.fetchTags(root)
        val remoteExisting = remoteTagCommit(root, tag)
        if (remoteExisting != null) return@locked ProductionTagPush.AlreadyExists(remoteExisting)

        val localRef = "refs/tags/$tag"
        val createdLocally = !git.refExists(root, localRef)
        if (!createdLocally && git.resolve(root, localRef) != releaseSha) {
            return@locked ProductionTagPush.Failed("本地同名 Tag 指向了其他提交：$tag")
        }
        if (createdLocally) git.run(root, "tag", tag, releaseSha)
        val push = git.run(root, "push", "origin", "refs/tags/$tag", check = false)
        if (!push.succeeded) {
            if (createdLocally) git.run(root, "tag", "-d", tag, check = false)
            return@locked if (isPermissionFailure(push)) {
                ProductionTagPush.NoPermission("无推送权限")
            } else {
                ProductionTagPush.Failed(push.stderr.ifBlank { push.stdout }.trim())
            }
        }
        val remoteSha = remoteTagCommit(root, tag)
        if (remoteSha == null) ProductionTagPush.Failed("Tag 推送后远端校验失败：$tag")
        else ProductionTagPush.Pushed(remoteSha)
    }

    override fun mergedTargetSha(
        repository: RepositoryConfig,
        targetBranch: String,
        expectedCommit: String,
    ): String? = locked(repository) { root ->
        git.fetch(root)
        val targetSha = remoteBranchCommit(root, targetBranch) ?: return@locked null
        targetSha.takeIf { git.isAncestor(root, expectedCommit, it) }
    }

    private fun pushBranchOrRequest(
        root: Path,
        repository: RepositoryConfig,
        sourceBranch: String,
        targetBranch: String,
        expectedCommit: String,
    ): ProductionBranchWrite {
        val direct = git.run(root, "push", "origin", "$expectedCommit:refs/heads/$targetBranch", check = false)
        return if (direct.succeeded) {
            ProductionBranchWrite.Direct(expectedCommit)
        } else if (isPermissionFailure(direct)) {
            ProductionBranchWrite.AwaitingRequest(
                pushSourceBranch(root, repository, sourceBranch, targetBranch, expectedCommit),
            )
        } else {
            throw GitException("生产基线推送失败：$targetBranch", direct)
        }
    }

    private fun pushSourceBranch(
        root: Path,
        repository: RepositoryConfig,
        sourceBranch: String,
        targetBranch: String,
        expectedCommit: String,
    ): ProductionMergeRequest {
        val push = git.run(root, "push", "origin", "$expectedCommit:refs/heads/$sourceBranch", check = false)
        if (!push.succeeded) throw GitException("无法推送用于合并请求的源分支：$sourceBranch", push)
        val origin = repository.originUrl ?: git.remoteUrl(root) ?: error("仓库未配置 origin 地址")
        val link = MergeRequestLinkBuilder.build(origin, sourceBranch, targetBranch)
            ?: error("当前代码托管平台无法自动生成合并请求链接")
        return ProductionMergeRequest(
            platform = link.platform.name,
            url = link.url,
            sourceBranch = sourceBranch,
            targetBranch = targetBranch,
            expectedCommit = expectedCommit,
        )
    }

    private fun conflictFiles(worktree: Path): List<String> =
        git.run(worktree, "diff", "--name-only", "--diff-filter=U", check = false)
            .stdout.lineSequence().map(String::trim).filter(String::isNotBlank).toList()

    private fun remoteTags(root: Path): List<String> =
        git.readOnly(root, "ls-remote", "--tags", "--refs", "origin")
            .stdout.lineSequence()
            .mapNotNull { it.substringAfter("refs/tags/", "").ifBlank { null } }
            .distinct()
            .toList()

    private fun remoteBranchCommit(root: Path, branch: String): String? =
        git.readOnly(root, "ls-remote", "--heads", "origin", "refs/heads/$branch", check = false)
            .takeIf { it.succeeded }
            ?.stdout
            ?.lineSequence()
            ?.firstOrNull()
            ?.substringBefore('\t')
            ?.trim()
            ?.ifBlank { null }

    private fun remoteTagCommit(root: Path, tag: String): String? {
        val result = git.readOnly(
            root,
            "ls-remote",
            "--tags",
            "origin",
            "refs/tags/$tag",
            "refs/tags/$tag^{}",
            check = false,
        )
        if (!result.succeeded) return null
        val lines = result.stdout.lineSequence().filter(String::isNotBlank).toList()
        val peeled = lines.firstOrNull { it.endsWith("refs/tags/$tag^{}") }
        return (peeled ?: lines.firstOrNull())?.substringBefore('\t')?.trim()?.ifBlank { null }
    }

    private fun <T> temporaryWorktree(
        root: Path,
        repository: RepositoryConfig,
        purpose: String,
        baseSha: String,
        block: (Path, String) -> T,
    ): T {
        val safeService = repository.name.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val suffix = operationId()
        val branch = "awm/production-tag/$safeService/$purpose-$suffix"
        val target = paths.temp.resolve("production-tag").resolve("$safeService-$purpose-$suffix")
        target.parent.createDirectories()
        git.addWorktree(root, target, branch, baseSha)
        return try {
            block(target, branch)
        } finally {
            runCatching { git.run(target, "merge", "--abort", check = false) }
            runCatching { git.removeWorktree(root, target, force = true) }
            git.run(root, "branch", "-D", branch, check = false)
            git.run(root, "worktree", "prune", check = false)
        }
    }

    private fun <T> locked(repository: RepositoryConfig, block: (Path) -> T): T {
        val root = Path(repository.rootPath).toAbsolutePath().normalize()
        return locks.withLock(root) { block(root) }
    }

    private fun isPermissionFailure(result: CommandResult): Boolean {
        val output = "${result.stderr}\n${result.stdout}".lowercase()
        return listOf(
            "permission denied",
            "not allowed",
            "not permitted",
            "protected branch",
            "pre-receive hook declined",
            "access denied",
            "http 403",
            "error: 403",
        ).any(output::contains)
    }

    private companion object {
        val FORMAL_TAG = Regex("""\d+\.\d+\.\d+""")
        val semanticVersionComparator = compareBy<String>(
            { it.substringBefore('.').toInt() },
            { it.substringAfter('.').substringBefore('.').toInt() },
            { it.substringAfterLast('.').toInt() },
        )
    }
}
