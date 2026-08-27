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
    override fun validateRepository(repository: RepositoryConfig) = locked(repository) { root ->
        git.fetch(root)
        check(remoteBranchCommit(root, "master") != null) { "服务配置不完整：origin/master 不存在" }
    }

    override fun operator(repository: RepositoryConfig): String? = locked(repository) { root ->
        val name = git.readOnly(root, "config", "user.name", check = false)
            .takeIf(CommandResult::succeeded)?.stdout?.trim().orEmpty()
        val email = git.readOnly(root, "config", "user.email", check = false)
            .takeIf(CommandResult::succeeded)?.stdout?.trim().orEmpty()
        listOf(name, email.takeIf(String::isNotBlank)?.let { "<$it>" }.orEmpty())
            .filter(String::isNotBlank).joinToString(" ").ifBlank { null }
    }

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

    override fun releaseHead(repository: RepositoryConfig, branch: String): String? = locked(repository) { root ->
        require(branch.startsWith("release/")) { "Release 分支必须以 release/ 开头：$branch" }
        requireValidBranch(root, branch, "Release")
        remoteBranchCommit(root, branch)
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
        val stableSourceBranch = pipeline.activeOperation?.sourceBranch
            ?: error("生产回灌操作缺少稳定的合并请求源分支")
        temporaryWorktree(root, repository, "production", remoteMaster, stableSourceBranch) { worktree, sourceBranch ->
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
                // The merge failed inside the temporary worktree, so no remote write happened
                // and the operation lease can be finalized instead of waiting for reconciliation.
                throw ProductionMergeConflictException(
                    if (files.isEmpty()) "生产 Tag 合并到 master 失败" else "生产 Tag 合并到 master 存在冲突：${files.joinToString()}",
                )
            }
            val expectedCommit = git.resolve(worktree, "HEAD")
            check(remoteTagCommit(root, pipeline.productionTag) == pipeline.productionTagSha) {
                "生产 Tag 已发生变化，请先刷新生产基线"
            }
            pushBranchOrRequest(root, repository, sourceBranch, "master", remoteMaster, expectedCommit)
        }
    }

    override fun recoverProductionWrite(
        repository: RepositoryConfig,
        targetBranch: String,
        beforeSha: String,
        productionSha: String,
        sourceBranch: String?,
    ): ProductionBranchWrite? = locked(repository) { root ->
        git.fetch(root)
        recoverExactMerge(root, targetBranch, beforeSha, productionSha)?.let { (_, targetHead) ->
            return@locked ProductionBranchWrite.Direct(targetHead)
        }
        val stableSource = sourceBranch ?: return@locked null
        val recoveredSource = recoverExactMerge(root, stableSource, beforeSha, productionSha)
            ?: return@locked null
        val (_, sourceHead) = recoveredSource
        val request = try {
            buildMergeRequest(repository, stableSource, targetBranch, sourceHead)
        } catch (_: ProductionMergeRequestUnavailableException) {
            return@locked null
        }
        ProductionBranchWrite.AwaitingRequest(
            request,
        )
    }

    override fun createRelease(
        repository: RepositoryConfig,
        pipeline: ProductionTagPipeline,
    ): String = locked(repository) { root ->
        val branch = pipeline.releaseBranch
        val masterSha = pipeline.masterSha
        requireValidBranch(root, branch, "Release")
        git.fetch(root)
        val remoteMaster = git.resolve(root, "refs/remotes/origin/master")
        check(remoteMaster == masterSha) { "master 已发生变化，请先刷新生产基线" }
        check(remoteTagCommit(root, pipeline.productionTag) == pipeline.productionTagSha) {
            "生产 Tag 已发生变化，请先刷新生产基线"
        }
        check(git.isAncestor(root, pipeline.productionTagSha, remoteMaster)) {
            "生产 Tag 尚未进入 master，不能创建 Release"
        }
        check(remoteBranchCommit(root, branch) == null) { "远端 Release 分支已存在：$branch" }
        check(remoteBranchCommit(root, "master") == masterSha) { "master 已发生变化，请先刷新生产基线" }
        check(remoteTagCommit(root, pipeline.productionTag) == pipeline.productionTagSha) {
            "生产 Tag 已发生变化，请先刷新生产基线"
        }
        val result = git.run(
            root,
            "push",
            "--porcelain",
            "origin",
            "$masterSha:refs/heads/$branch",
            check = false,
        )
        if (!result.succeeded) {
            if (remoteBranchCommit(root, branch) != null) error("远端 Release 分支已存在：$branch")
            if (isPermissionFailure(result)) throw ProductionNoPushPermissionException()
            throw GitException("创建远端 Release 分支失败：$branch", result)
        }
        check(result.stdout.contains("[new branch]") || result.stderr.contains("[new branch]")) {
            "Release 分支在推送竞态中已被其他操作创建：$branch"
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
        val stableSourceBranch = pipeline.activeOperation?.sourceBranch
            ?: error("Feature 合并操作缺少稳定的合并请求源分支")
        temporaryWorktree(root, repository, "features-write", releaseSha, stableSourceBranch) { worktree, sourceBranch ->
            val write = mergeFeatureBatch(worktree, pipeline.releaseBranch, features)
            write.conflict?.let { return@temporaryWorktree ProductionFeatureWrite.Conflict(listOf(it)) }
            val expectedCommit = git.resolve(worktree, "HEAD")
            revalidateFeatureHeads(root, pipeline, features, releaseSha)
            val push = git.run(
                worktree,
                "push",
                "--porcelain",
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

    override fun recoverFeatureWrite(
        repository: RepositoryConfig,
        releaseBranch: String,
        sourceBranch: String?,
        beforeSha: String,
        features: List<ProductionFeatureSelection>,
    ): ProductionFeatureWrite? = locked(repository) { root ->
        git.fetch(root)
        recoverFeatureMerges(root, releaseBranch, beforeSha, features)?.let { direct ->
            return@locked direct
        }
        val stableSource = sourceBranch ?: return@locked null
        val sourceWrite = recoverFeatureMerges(root, stableSource, beforeSha, features) ?: return@locked null
        val request = try {
            buildMergeRequest(repository, stableSource, releaseBranch, sourceWrite.releaseSha)
        } catch (_: ProductionMergeRequestUnavailableException) {
            return@locked null
        }
        ProductionFeatureWrite.AwaitingRequest(
            releaseSha = sourceWrite.releaseSha,
            merges = sourceWrite.merges,
            request = request,
        )
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
        val push = git.run(
            root,
            "push",
            "--porcelain",
            "origin",
            "$releaseSha:refs/tags/$tag",
            check = false,
        )
        if (!push.succeeded) {
            // A failed/aborted client response does not prove the server rejected the
            // write. If this verification is also unavailable, propagate the error so
            // the persisted operation lease remains recoverable.
            val racedTag = remoteTagCommit(root, tag)
            if (racedTag != null) {
                return@locked if (racedTag == releaseSha) ProductionTagPush.AlreadyExists(racedTag)
                else ProductionTagPush.Failed("远端同名 Tag 已指向其他提交：$tag")
            }
            return@locked if (isPermissionFailure(push)) {
                ProductionTagPush.NoPermission("无推送权限：${failureSummary(push)}")
            } else {
                ProductionTagPush.Failed(GitAuditSanitizer.summary(push))
            }
        }
        val remoteSha = remoteTagCommit(root, tag)
        if (remoteSha != releaseSha) ProductionTagPush.Failed("Tag 推送后远端校验失败：$tag")
        else if (push.stdout.contains("[new tag]") || push.stderr.contains("[new tag]")) ProductionTagPush.Pushed(remoteSha)
        else ProductionTagPush.AlreadyExists(remoteSha)
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
        check(remoteBranchCommit(root, targetBranch) == targetShaBeforeWrite) {
            "$targetBranch 已发生变化，请刷新后重试"
        }
        val direct = git.run(
            root,
            "push",
            "--porcelain",
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
        // Validate platform/link generation before creating any remote source branch.
        val request = buildMergeRequest(repository, sourceBranch, targetBranch, expectedCommit)
        val push = git.run(
            root,
            "push",
            "--porcelain",
            "origin",
            "$expectedCommit:refs/heads/$sourceBranch",
            check = false,
        )
        if (!push.succeeded) {
            if (isPermissionFailure(push)) throw ProductionNoPushPermissionException()
            throw GitException("无法推送用于合并请求的源分支：$sourceBranch", push)
        }
        check(push.stdout.contains("[new branch]") || push.stderr.contains("[new branch]")) {
            "用于合并请求的源分支已被其他操作占用：$sourceBranch"
        }
        check(remoteBranchCommit(root, sourceBranch) == expectedCommit) { "合并请求源分支推送后校验失败：$sourceBranch" }
        return request
    }

    private fun buildMergeRequest(
        repository: RepositoryConfig,
        sourceBranch: String,
        targetBranch: String,
        expectedCommit: String,
    ): ProductionMergeRequest {
        val origin = GitAuditSanitizer.remoteDisplay(repository.originUrl) ?: error("仓库未配置 origin 地址")
        val link = MergeRequestLinkBuilder.build(origin, sourceBranch, targetBranch)
            ?: throw ProductionMergeRequestUnavailableException()
        return ProductionMergeRequest(
            platform = link.platform.name,
            url = link.url,
            sourceBranch = sourceBranch,
            targetBranch = targetBranch,
            expectedCommit = expectedCommit,
        )
    }

    /** Finds the exact --no-ff merge produced from [beforeSha] with [sourceSha] as parent two. */
    private fun recoverExactMerge(
        root: Path,
        branch: String,
        beforeSha: String,
        sourceSha: String,
    ): Pair<String, String>? {
        val head = remoteBranchCommit(root, branch) ?: return null
        if (!git.isAncestor(root, beforeSha, head) || !git.isAncestor(root, sourceSha, head)) return null
        val mergeCommit = firstParentCommits(root, beforeSha, head).firstOrNull { commit ->
            parent(root, commit, 1) == beforeSha && parent(root, commit, 2) == sourceSha
        } ?: return null
        return mergeCommit to head
    }

    private fun recoverFeatureMerges(
        root: Path,
        branch: String,
        beforeSha: String,
        features: List<ProductionFeatureSelection>,
    ): ProductionFeatureWrite.Direct? {
        val head = remoteBranchCommit(root, branch) ?: return null
        if (!git.isAncestor(root, beforeSha, head) || features.any { !git.isAncestor(root, it.sha, head) }) return null
        val commits = firstParentCommits(root, beforeSha, head)
        var searchFrom = 0
        var integratedHead = beforeSha
        val records = mutableListOf<ProductionFeatureMergeRecord>()
        for (feature in features) {
            if (git.isAncestor(root, feature.sha, integratedHead)) continue
            val relativeIndex = commits.drop(searchFrom).indexOfFirst { commit ->
                parent(root, commit, 1) == integratedHead && parent(root, commit, 2) == feature.sha
            }
            if (relativeIndex < 0) return null
            val absoluteIndex = searchFrom + relativeIndex
            records += ProductionFeatureMergeRecord(feature.branch, feature.sha, commits[absoluteIndex], now())
            integratedHead = commits[absoluteIndex]
            searchFrom = absoluteIndex + 1
        }
        return ProductionFeatureWrite.Direct(head, records)
    }

    private fun firstParentCommits(root: Path, beforeSha: String, head: String): List<String> =
        git.readOnly(root, "rev-list", "--first-parent", "--reverse", "$beforeSha..$head")
            .stdout.lineSequence().map(String::trim).filter(String::isNotBlank).toList()

    private fun parent(root: Path, commit: String, number: Int): String? =
        git.readOnly(root, "rev-parse", "$commit^$number", check = false)
            .takeIf(CommandResult::succeeded)?.stdout?.trim()?.ifBlank { null }

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
        val refs = result.stdout.lineSequence().filter(String::isNotBlank).associate { line ->
            line.substringAfter('\t', "").trim() to line.substringBefore('\t').trim()
        }
        return (refs["refs/tags/$tag^{}"] ?: refs["refs/tags/$tag"])?.ifBlank { null }
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
        features.forEachIndexed { index, feature ->
            if (git.isAncestor(root, feature.sha, releaseSha)) {
                throw ProductionFeatureTopologyException(
                    "Feature 已包含在当前 Release 中，无法形成独立合并点，请移除：${feature.branch}",
                )
            }
            features.drop(index + 1)
                .firstOrNull { later -> git.isAncestor(root, later.sha, feature.sha) }
                ?.let { later ->
                    throw ProductionFeatureTopologyException(
                        "Feature 顺序无法形成独立合并点：请将依赖分支 ${later.branch} 排在 ${feature.branch} 之前",
                    )
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
            val before = git.resolve(worktree, "HEAD")
            if (git.isAncestor(worktree, feature.sha, before)) continue
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
            val after = git.resolve(worktree, "HEAD")
            if (after == before) continue
            merges += ProductionFeatureMergeRecord(
                branch = feature.branch,
                sourceSha = feature.sha,
                mergeCommit = after,
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
        stableBranch: String? = null,
        block: (Path, String) -> T,
    ): T {
        val safeService = repository.name.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val suffix = operationId()
        val branch = stableBranch ?: "awm/production-tag/$safeService/$purpose-$suffix"
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

    private fun normalizeOrigin(value: String): String {
        val clean = value.trim().replace('\\', '/').removeSuffix("/").removeSuffix(".git")
        if (clean.matches(Regex("^[A-Za-z]:/.*")) || clean.startsWith("//")) return clean.lowercase()
        val scheme = clean.indexOf("://")
        if (scheme >= 0) {
            val path = clean.indexOf('/', scheme + 3)
            return if (path < 0) clean.lowercase() else clean.substring(0, path).lowercase() + clean.substring(path)
        }
        val scpSeparator = clean.indexOf(':')
        return if (scpSeparator > 0 && '/' !in clean.substring(0, scpSeparator)) {
            clean.substring(0, scpSeparator).lowercase() + clean.substring(scpSeparator)
        } else clean
    }

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

    private fun failureSummary(result: CommandResult): String = GitAuditSanitizer.summary(result)

    private companion object {
        val FORMAL_TAG = Regex("""\d+\.\d+\.\d+""")
        val semanticVersionComparator = compareBy<String>(
            { it.substringBefore('.').toInt() },
            { it.substringAfter('.').substringBefore('.').toInt() },
            { it.substringAfterLast('.').toInt() },
        )
    }
}
