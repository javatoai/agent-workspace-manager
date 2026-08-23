package com.snowball.awm.core

import java.nio.file.Path
import java.nio.file.Files
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
        val remoteTagSha = remoteTagCommit(root, productionTag)
            ?: error("生产 Tag 在远端仓库中不存在：$productionTag")
        require(git.refExists(root, tagRef)) { "生产 Tag 对象未同步到本地：$productionTag" }
        val productionTagSha = git.resolve(root, tagRef)
        check(productionTagSha == remoteTagSha) { "生产 Tag 本地对象与远端不一致：$productionTag" }
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
        check(remoteTagCommit(root, pipeline.productionTag) == pipeline.productionTagSha) {
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
            pushBranchOrRequest(root, repository, sourceBranch, "master", remoteMaster, expectedCommit)
        }
    }

    override fun createRelease(
        repository: RepositoryConfig,
        branch: String,
        masterSha: String,
    ): String = locked(repository) { root ->
        requireValidBranch(root, branch, "Release")
        git.fetch(root)
        val remoteMaster = git.resolve(root, "refs/remotes/origin/master")
        check(remoteMaster == masterSha) { "master 已发生变化，请先刷新生产基线" }
        check(remoteBranchCommit(root, branch) == null) { "远端 Release 分支已存在：$branch" }
        val result = git.run(
            root,
            "push",
            "--force-with-lease=refs/heads/$branch:",
            "origin",
            "$masterSha:refs/heads/$branch",
            check = false,
        )
        if (!result.succeeded) {
            if (remoteBranchCommit(root, branch) != null) error("远端 Release 分支已存在：$branch")
            throw GitException("创建远端 Release 分支失败：$branch", result)
        }
        val remoteSha = remoteBranchCommit(root, branch)
        check(remoteSha == masterSha) { "Release 分支推送后校验失败：$branch" }
        remoteSha
    }

    override fun resolveFeatures(
        repository: RepositoryConfig,
        branches: List<String>,
    ): List<ProductionFeatureSelection> = locked(repository) { root ->
        git.fetch(root)
        branches.map { branch ->
            require(branch.startsWith("feature/")) { "Feature 分支必须以 feature/ 开头：$branch" }
            requireValidBranch(root, branch, "Feature")
            val sha = remoteBranchCommit(root, branch) ?: error("远端 Feature 分支不存在：$branch")
            ProductionFeatureSelection(branch, sha)
        }
    }

    override fun mergeFeatures(
        repository: RepositoryConfig,
        pipeline: ProductionTagPipeline,
        features: List<ProductionFeatureSelection>,
    ): ProductionFeatureWrite = locked(repository) { root ->
        val releaseSha = pipeline.releaseSha ?: error("Release 尚未创建")
        revalidateFeatureHeads(root, pipeline, features, releaseSha)
        val preflight = temporaryWorktree(root, repository, "features-check", releaseSha) { worktree, _ ->
            mergeFeatureBatch(worktree, pipeline.releaseBranch, features)
        }
        preflight.conflict?.let { return@locked ProductionFeatureWrite.Conflict(listOf(it)) }

        // The conflict simulation never authorizes a write by itself. Refresh every
        // remote head and repeat the merge from the exact validated Release commit.
        revalidateFeatureHeads(root, pipeline, features, releaseSha)
        temporaryWorktree(root, repository, "features-write", releaseSha) { worktree, sourceBranch ->
            val write = mergeFeatureBatch(worktree, pipeline.releaseBranch, features)
            write.conflict?.let { return@temporaryWorktree ProductionFeatureWrite.Conflict(listOf(it)) }
            val expectedCommit = git.resolve(worktree, "HEAD")
            val push = git.run(
                worktree,
                "push",
                "--force-with-lease=refs/heads/${pipeline.releaseBranch}:$releaseSha",
                "origin",
                "HEAD:refs/heads/${pipeline.releaseBranch}",
                check = false,
            )
            if (push.succeeded) {
                check(remoteBranchCommit(root, pipeline.releaseBranch) == expectedCommit) {
                    "Release 分支推送后远端校验失败：${pipeline.releaseBranch}"
                }
                ProductionFeatureWrite.Direct(expectedCommit, write.merges)
            } else if (isPermissionFailure(push)) {
                val request = pushSourceBranch(root, repository, sourceBranch, pipeline.releaseBranch, expectedCommit)
                ProductionFeatureWrite.AwaitingRequest(expectedCommit, write.merges, request)
            } else {
                throw GitException("Feature 合并结果推送失败：${pipeline.releaseBranch}", push)
            }
        }
    }

    override fun tagsForBase(
        repository: RepositoryConfig,
        baseVersion: String,
    ): List<ProductionRemoteTag> = locked(repository) { root ->
        remoteTagRefs(root)
            .filterKeys { it == baseVersion || it.matches(Regex("${Regex.escape(baseVersion)}\\.\\d+")) }
            .map { (tag, sha) -> ProductionRemoteTag(tag, sha) }
    }

    override fun pushTag(
        repository: RepositoryConfig,
        releaseBranch: String,
        tag: String,
        releaseSha: String,
    ): ProductionTagPush = locked(repository) { root ->
        git.fetch(root)
        val remoteReleaseSha = remoteBranchCommit(root, releaseBranch)
            ?: return@locked ProductionTagPush.Failed("远端 Release 分支不存在：$releaseBranch")
        if (remoteReleaseSha != releaseSha) {
            return@locked ProductionTagPush.Failed("Release 分支已发生变化，请刷新后重试")
        }
        val remoteExisting = remoteTagCommit(root, tag)
        if (remoteExisting != null) {
            return@locked if (remoteExisting == releaseSha) {
                ProductionTagPush.AlreadyExists(remoteExisting)
            } else {
                ProductionTagPush.Failed("远端同名 Tag 已指向其他提交：$tag")
            }
        }
        // Push the commit object directly so a stale local annotated tag can never
        // be reused as the formal lightweight production tag.
        val push = git.run(root, "push", "origin", "$releaseSha:refs/tags/$tag", check = false)
        if (!push.succeeded) {
            return@locked if (isPermissionFailure(push)) {
                ProductionTagPush.NoPermission("无推送权限")
            } else {
                ProductionTagPush.Failed(push.stderr.ifBlank { push.stdout }.trim())
            }
        }
        val remoteSha = remoteTagCommit(root, tag)
        if (remoteSha != releaseSha) ProductionTagPush.Failed("Tag 推送后远端校验失败：$tag")
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
        targetShaBeforeWrite: String,
        expectedCommit: String,
    ): ProductionBranchWrite {
        val direct = git.run(
            root,
            "push",
            "--force-with-lease=refs/heads/$targetBranch:$targetShaBeforeWrite",
            "origin",
            "$expectedCommit:refs/heads/$targetBranch",
            check = false,
        )
        return if (direct.succeeded) {
            check(remoteBranchCommit(root, targetBranch) == expectedCommit) {
                "生产基线推送后远端校验失败：$targetBranch"
            }
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
        check(remoteBranchCommit(root, sourceBranch) == expectedCommit) { "合并请求源分支推送后校验失败：$sourceBranch" }
        val origin = repository.originUrl ?: error("仓库未配置 origin 地址")
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

    private fun remoteTags(root: Path): List<String> = remoteTagRefs(root).keys.toList()

    private fun remoteBranchCommit(root: Path, branch: String): String? {
        val expectedRef = "refs/heads/$branch"
        val matches = git.readOnly(root, "ls-remote", "--heads", "origin", expectedRef)
            .stdout.lineSequence()
            .filter(String::isNotBlank)
            .map { line -> line.substringBefore('\t').trim() to line.substringAfter('\t', "").trim() }
            .filter { (_, ref) -> ref == expectedRef }
            .toList()
        check(matches.size <= 1) { "远端分支解析结果不唯一：$branch" }
        return matches.singleOrNull()?.first?.ifBlank { null }
    }

    private fun remoteTagCommit(root: Path, tag: String): String? {
        val result = git.readOnly(
            root,
            "ls-remote",
            "--tags",
            "origin",
            "refs/tags/$tag",
            "refs/tags/$tag^{}",
            check = true,
        )
        val lines = result.stdout.lineSequence().filter(String::isNotBlank).toList()
        val peeled = lines.firstOrNull { it.endsWith("refs/tags/$tag^{}") }
        return (peeled ?: lines.firstOrNull())?.substringBefore('\t')?.trim()?.ifBlank { null }
    }

    private fun remoteTagRefs(root: Path): Map<String, String> {
        val result = git.readOnly(root, "ls-remote", "--tags", "origin")
        val direct = linkedMapOf<String, String>()
        val peeled = linkedMapOf<String, String>()
        result.stdout.lineSequence().filter(String::isNotBlank).forEach { line ->
            val sha = line.substringBefore('\t').trim()
            val ref = line.substringAfter('\t', "").trim()
            if (!ref.startsWith("refs/tags/")) return@forEach
            val raw = ref.removePrefix("refs/tags/")
            if (raw.endsWith("^{}")) peeled[raw.removeSuffix("^{}")] = sha else direct[raw] = sha
        }
        return direct.mapValues { (name, sha) -> peeled[name] ?: sha }
    }

    private fun revalidateFeatureHeads(
        root: Path,
        pipeline: ProductionTagPipeline,
        features: List<ProductionFeatureSelection>,
        releaseSha: String,
    ) {
        git.fetch(root)
        val currentReleaseSha = remoteBranchCommit(root, pipeline.releaseBranch)
            ?: error("远端 Release 分支不存在：${pipeline.releaseBranch}")
        check(currentReleaseSha == releaseSha) { "Release 分支已发生变化，请刷新后重试" }
        features.forEach { feature ->
            require(feature.branch.startsWith("feature/")) { "Feature 分支必须以 feature/ 开头：${feature.branch}" }
            requireValidBranch(root, feature.branch, "Feature")
            check(remoteBranchCommit(root, feature.branch) == feature.sha) {
                "Feature 分支已发生变化，请重新选择：${feature.branch}"
            }
        }
    }

    private data class FeatureMergeAttempt(
        val merges: List<ProductionFeatureMergeRecord>,
        val conflict: ProductionConflict? = null,
    )

    private fun mergeFeatureBatch(
        worktree: Path,
        releaseBranch: String,
        features: List<ProductionFeatureSelection>,
    ): FeatureMergeAttempt {
        val merges = mutableListOf<ProductionFeatureMergeRecord>()
        for (feature in features) {
            val result = git.run(
                worktree,
                "merge",
                "--no-ff",
                feature.sha,
                "-m",
                "Merge ${feature.branch} into $releaseBranch",
                check = false,
            )
            if (!result.succeeded) {
                return FeatureMergeAttempt(
                    emptyList(),
                    ProductionConflict(feature.branch, conflictFiles(worktree), feature.sha),
                )
            }
            merges += ProductionFeatureMergeRecord(
                branch = feature.branch,
                sourceSha = feature.sha,
                mergeCommit = git.resolve(worktree, "HEAD"),
                completedAt = now(),
            )
        }
        return FeatureMergeAttempt(merges)
    }

    private fun requireValidBranch(root: Path, branch: String, kind: String) {
        val result = git.readOnly(root, "check-ref-format", "--branch", branch, check = false)
        require(result.succeeded && result.stdout.trim() == branch) { "$kind 分支名不合法：$branch" }
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
        val expectedCommon = Path(repository.gitCommonDirectory).toAbsolutePath().normalize()
        return locks.withLock(expectedCommon) {
            validateRepositoryIdentity(repository, root, expectedCommon)
            block(root)
        }
    }

    private fun validateRepositoryIdentity(repository: RepositoryConfig, root: Path, expectedCommon: Path) {
        require(Files.isDirectory(root)) { "仓库路径不存在：$root" }
        check(git.topLevel(root) == root) { "仓库根目录已发生变化：$root" }
        check(git.commonDirectory(root) == expectedCommon) { "仓库 git-common-dir 已发生变化：$root" }
        val expectedOrigin = repository.originUrl?.takeIf(String::isNotBlank)
            ?: error("生产 Tag 服务必须配置 origin 地址：${repository.name}")
        val actualOrigin = git.remoteUrl(root) ?: error("仓库缺少 origin：${repository.name}")
        check(normalizeOrigin(actualOrigin) == normalizeOrigin(expectedOrigin)) {
            "仓库 origin 已发生变化，请重新扫描服务：${repository.name}"
        }
    }

    private fun normalizeOrigin(value: String): String = value.trim()
        .replace('\\', '/')
        .removeSuffix("/")
        .removeSuffix(".git")
        .lowercase()

    private fun isPermissionFailure(result: CommandResult): Boolean {
        val output = "${result.stderr}\n${result.stdout}".lowercase()
        return listOf(
            "permission denied",
            "not allowed",
            "not permitted",
            "protected branch",
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
