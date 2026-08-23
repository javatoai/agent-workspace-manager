package com.snowball.awm.core

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProductionTagServiceTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `pipeline creates release merges an ordered feature batch and builds the formal tag`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val config = managedConfig(repository)
        val git = FakeProductionGitGateway()
        val service = ProductionTagService(
            store = ProductionTagPipelineStore(ApplicationPaths(temporary.resolve("home"))),
            versions = ProductionVersionProvider {
                ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
            },
            git = git,
            now = { "2026-08-24T10:00:00+08:00" },
            id = { "pipeline-1" },
        )

        val created = service.create(config, repository.id)
        val released = service.createRelease(config, created.id)
        val selected = service.selectFeatures(config, created.id, listOf("feature/a", "feature/b"))
        val merged = service.mergeFeatures(config, created.id)
        val built = service.buildTag(config, created.id)

        assertEquals(ProductionBaselineState.ALREADY_CONTAINED, created.baselineState)
        assertEquals("3.11.71", created.baseVersion)
        assertEquals("master-sha", released.releaseSha)
        assertEquals(listOf("feature/a", "feature/b"), selected.selectedFeatures.map { it.branch })
        assertEquals(ProductionFeatureBatchState.MERGED, merged.featureState)
        assertEquals(listOf("feature/a", "feature/b"), merged.mergedFeatures.map { it.branch })
        assertEquals(ProductionTagBuildState.PUSHED, built.buildRecords.single().state)
        assertEquals("3.11.71", built.buildRecords.single().expectedTag)
        assertEquals(listOf("feature/a", "feature/b"), git.mergedBranches)
    }

    @Test
    fun `feature conflict stops the batch without recording successful merges`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git")
        val config = managedConfig(repository)
        val git = FakeProductionGitGateway(conflict = ProductionConflict("feature/b", listOf("src/App.kt"), "sha-b"))
        val service = ProductionTagService(
            store = ProductionTagPipelineStore(ApplicationPaths(temporary.resolve("conflict-home"))),
            versions = ProductionVersionProvider {
                ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
            },
            git = git,
            now = { "now" },
            id = { "pipeline-conflict" },
        )
        val pipeline = service.create(config, repository.id)
        service.createRelease(config, pipeline.id)
        service.selectFeatures(config, pipeline.id, listOf("feature/a", "feature/b"))

        val result = service.mergeFeatures(config, pipeline.id)

        assertEquals(ProductionFeatureBatchState.CONFLICT, result.featureState)
        assertEquals("feature/b", result.conflicts.single().branch)
        assertTrue(result.mergedFeatures.isEmpty())
    }

    @Test
    fun `a later feature batch excludes a previously merged unchanged branch`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val config = managedConfig(repository)
        val git = FakeProductionGitGateway()
        val service = ProductionTagService(
            store = ProductionTagPipelineStore(ApplicationPaths(temporary.resolve("incremental-feature-home"))),
            versions = ProductionVersionProvider {
                ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
            },
            git = git,
            now = { "now" },
            id = { "incremental" },
        )
        val pipeline = service.create(config, repository.id)
        service.createRelease(config, pipeline.id)
        service.selectFeatures(config, pipeline.id, listOf("feature/a"))
        service.mergeFeatures(config, pipeline.id)

        val selected = service.selectFeatures(config, pipeline.id, listOf("feature/a", "feature/b"))
        val merged = service.mergeFeatures(config, pipeline.id)

        assertEquals(listOf("feature/b"), selected.selectedFeatures.map { it.branch })
        assertEquals(listOf("feature/a", "feature/b"), merged.mergedFeatures.map { it.branch })
        assertEquals(listOf("feature/b"), git.mergedBranches)
    }

    @Test
    fun `merge request refresh records the actual target head instead of the source commit`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git")
        val config = managedConfig(repository)
        val git = FakeProductionGitGateway(
            baselineState = ProductionBaselineState.MERGE_REQUIRED,
            mergedTargetSha = "master-with-later-commits",
        )
        val service = ProductionTagService(
            store = ProductionTagPipelineStore(ApplicationPaths(temporary.resolve("mr-home"))),
            versions = ProductionVersionProvider {
                ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
            },
            git = git,
            now = { "now" },
            id = { "pipeline-mr" },
        )

        val created = service.create(config, repository.id)
        val awaiting = service.mergeProduction(config, created.id)
        val refreshed = service.refreshMergeRequest(config, created.id)

        assertEquals(ProductionBaselineState.AWAITING_MERGE_REQUEST, awaiting.baselineState)
        assertEquals(ProductionBaselineState.ALREADY_CONTAINED, refreshed.baselineState)
        assertEquals("master-with-later-commits", refreshed.masterSha)
    }

    @Test
    fun `production drift cancels merge and persists the refreshed baseline`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val config = managedConfig(repository)
        var runtimeTag = "3.11.70"
        val git = FakeProductionGitGateway(baselineState = ProductionBaselineState.MERGE_REQUIRED)
        val store = ProductionTagPipelineStore(ApplicationPaths(temporary.resolve("drift-home")))
        val service = ProductionTagService(
            store = store,
            versions = ProductionVersionProvider {
                ProductionRuntimeSnapshot("svc", "PRD", runtimeTag, listOf(ProductionPodSnapshot("pod", runtimeTag, "Running", true, 0)))
            },
            git = git,
            now = { "now" },
            id = { "pipeline-drift" },
        )
        val created = service.create(config, repository.id)
        runtimeTag = "3.11.71"
        git.formalTagValues = listOf("3.11.70", "3.11.71")

        val error = assertFailsWith<ProductionBaselineChangedException> {
            service.mergeProduction(config, created.id)
        }

        assertEquals("3.11.71", error.refreshed.productionTag)
        assertEquals("3.11.72", error.refreshed.baseVersion)
        assertEquals("release/3.11.72", error.refreshed.releaseBranch)
        assertEquals("3.11.71", store.get(created.id)?.productionTag)
        assertEquals(0, git.productionMergeCalls)
    }

    @Test
    fun `tag gateway interruption leaves a lease that resume reconciles safely`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val config = managedConfig(repository)
        val git = FakeProductionGitGateway(throwDuringTagPush = true)
        val store = ProductionTagPipelineStore(ApplicationPaths(temporary.resolve("tag-crash-home")))
        val service = ProductionTagService(
            store = store,
            versions = ProductionVersionProvider {
                ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
            },
            git = git,
            now = { "now" },
            id = { "pipeline-tag-crash" },
        )
        val pipeline = service.create(config, repository.id)
        service.createRelease(config, pipeline.id)
        service.selectFeatures(config, pipeline.id, listOf("feature/a"))
        service.mergeFeatures(config, pipeline.id)

        assertFailsWith<IllegalStateException> { service.buildTag(config, pipeline.id) }

        val interrupted = store.get(pipeline.id)!!
        assertEquals(ProductionTagBuildState.PUSHING, interrupted.buildRecords.single().state)
        assertEquals(ProductionOperationAction.BUILD_TAG, interrupted.activeOperation?.action)
        assertFailsWith<IllegalStateException> { service.close(pipeline.id) }

        val recovered = service.resume(config, pipeline.id)
        val record = recovered.buildRecords.single()
        assertEquals(ProductionTagBuildState.FAILED, record.state)
        assertEquals("3.11.71", record.expectedTag)
        assertTrue(record.failureReason.orEmpty().contains("远端 Tag 不存在"))
        assertEquals(null, recovered.activeOperation)
    }

    @Test
    fun `process exit after tag push is reconciled as pushed on resume`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val config = managedConfig(repository)
        val git = FakeProductionGitGateway()
        var interrupt = true
        val store = ProductionTagPipelineStore(ApplicationPaths(temporary.resolve("tag-post-push-crash-home")))
        val service = ProductionTagService(
            store = store,
            versions = ProductionVersionProvider {
                ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
            },
            git = git,
            now = { "now" },
            id = { "pipeline-post-push-crash" },
            afterRemoteWrite = { action ->
                if (interrupt && action == ProductionOperationAction.BUILD_TAG) error("simulated process exit")
            },
        )
        val pipeline = service.create(config, repository.id)
        service.createRelease(config, pipeline.id)
        service.selectFeatures(config, pipeline.id, listOf("feature/a"))
        service.mergeFeatures(config, pipeline.id)

        assertFailsWith<IllegalStateException> { service.buildTag(config, pipeline.id) }
        interrupt = false
        val recovered = service.resume(config, pipeline.id)

        assertEquals(ProductionTagBuildState.PUSHED, recovered.buildRecords.single().state)
        assertEquals("release-with-features", recovered.buildRecords.single().remoteTagSha)
        assertEquals(ProductionAuditState.RECOVERED, recovered.auditEvents.last().state)
        assertEquals(null, recovered.activeOperation)
    }

    @Test
    fun `a live remote operation cannot be reconciled by another process`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val config = managedConfig(repository)
        val paths = ApplicationPaths(temporary.resolve("live-operation-home"))
        val enteredPush = CountDownLatch(1)
        val allowPush = CountDownLatch(1)
        val git = FakeProductionGitGateway(onTagPush = {
            enteredPush.countDown()
            check(allowPush.await(10, TimeUnit.SECONDS)) { "test did not release tag push" }
        })
        val provider = ProductionVersionProvider {
            ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
        }
        val first = ProductionTagService(ProductionTagPipelineStore(paths), provider, git, now = { "now" }, id = { "live-operation" })
        val pipeline = first.create(config, repository.id)
        first.createRelease(config, pipeline.id)
        first.selectFeatures(config, pipeline.id, listOf("feature/a"))
        first.mergeFeatures(config, pipeline.id)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val build = executor.submit<ProductionTagPipeline> { first.buildTag(config, pipeline.id) }
            assertTrue(enteredPush.await(10, TimeUnit.SECONDS))
            val second = ProductionTagService(ProductionTagPipelineStore(paths), provider, git)

            val error = assertFailsWith<IllegalStateException> { second.resume(config, pipeline.id) }

            assertTrue(error.message.orEmpty().contains("另一个 AWM 实例执行"))
            allowPush.countDown()
            assertEquals(ProductionTagBuildState.PUSHED, build.get(10, TimeUnit.SECONDS).buildRecords.single().state)
        } finally {
            allowPush.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `release write lease blocks another instance and recovers the remote branch`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val config = managedConfig(repository)
        val git = FakeProductionGitGateway()
        val paths = ApplicationPaths(temporary.resolve("release-post-push-crash-home"))
        val provider = ProductionVersionProvider {
            ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
        }
        var interrupt = true
        val service = ProductionTagService(
            store = ProductionTagPipelineStore(paths),
            versions = provider,
            git = git,
            now = { "now" },
            id = { "pipeline-release-crash" },
            afterRemoteWrite = { if (interrupt && it == ProductionOperationAction.CREATE_RELEASE) error("simulated process exit") },
        )
        val pipeline = service.create(config, repository.id)

        assertFailsWith<IllegalStateException> { service.createRelease(config, pipeline.id) }
        val secondInstance = ProductionTagService(ProductionTagPipelineStore(paths), provider, git)
        assertFailsWith<IllegalStateException> { secondInstance.close(pipeline.id) }

        interrupt = false
        val recovered = secondInstance.resume(config, pipeline.id)
        assertEquals("master-sha", recovered.releaseSha)
        assertEquals(ProductionAuditState.RECOVERED, recovered.auditEvents.last().state)
        assertEquals(null, recovered.activeOperation)
    }

    @Test
    fun `release permission denial is finalized and shown as no push permission`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val config = managedConfig(repository)
        val service = ProductionTagService(
            store = ProductionTagPipelineStore(ApplicationPaths(temporary.resolve("release-no-permission-home"))),
            versions = ProductionVersionProvider {
                ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
            },
            git = FakeProductionGitGateway(releaseNoPushPermission = true),
            now = { "now" },
            id = { "release-no-permission" },
        )
        val pipeline = service.create(config, repository.id)

        val error = assertFailsWith<ProductionNoPushPermissionException> {
            service.createRelease(config, pipeline.id)
        }
        val failed = service.get(pipeline.id)

        assertEquals("无推送权限", error.message)
        assertEquals(null, failed.activeOperation)
        assertEquals(ProductionAuditState.FAILED, failed.auditEvents.last().state)
        assertEquals("无推送权限", failed.auditEvents.last().reason)
    }

    @Test
    fun `master merge is reconciled after process exit following the remote write`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val config = managedConfig(repository)
        val git = FakeProductionGitGateway(
            baselineState = ProductionBaselineState.MERGE_REQUIRED,
            directProductionMerge = true,
        )
        var interrupt = true
        val service = ProductionTagService(
            store = ProductionTagPipelineStore(ApplicationPaths(temporary.resolve("master-post-push-crash-home"))),
            versions = ProductionVersionProvider {
                ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
            },
            git = git,
            now = { "now" },
            id = { "pipeline-master-crash" },
            afterRemoteWrite = { if (interrupt && it == ProductionOperationAction.MERGE_PRODUCTION) error("simulated process exit") },
        )
        val pipeline = service.create(config, repository.id)

        assertFailsWith<IllegalStateException> { service.mergeProduction(config, pipeline.id) }
        interrupt = false
        val recovered = service.resume(config, pipeline.id)

        assertEquals(ProductionBaselineState.ALREADY_CONTAINED, recovered.baselineState)
        assertEquals(ProductionAuditState.SUCCEEDED, recovered.auditEvents.last().state)
        assertEquals(null, recovered.activeOperation)
    }

    @Test
    fun `master merge request source branch is recovered after process exit`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val config = managedConfig(repository)
        val git = FakeProductionGitGateway(baselineState = ProductionBaselineState.MERGE_REQUIRED)
        var interrupt = true
        val service = ProductionTagService(
            store = ProductionTagPipelineStore(ApplicationPaths(temporary.resolve("master-mr-crash-home"))),
            versions = ProductionVersionProvider {
                ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
            },
            git = git,
            now = { "now" },
            id = { "master-mr-crash" },
            afterRemoteWrite = { if (interrupt && it == ProductionOperationAction.MERGE_PRODUCTION) error("simulated process exit") },
        )
        val pipeline = service.create(config, repository.id)

        assertFailsWith<IllegalStateException> { service.mergeProduction(config, pipeline.id) }
        assertTrue(service.get(pipeline.id).activeOperation?.sourceBranch.orEmpty().startsWith("awm/production-tag/"))
        interrupt = false
        val recovered = service.resume(config, pipeline.id)

        assertEquals(ProductionBaselineState.AWAITING_MERGE_REQUEST, recovered.baselineState)
        assertEquals("https://gitlab.example/mr", recovered.mergeRequest?.url)
        assertEquals(ProductionAuditState.AWAITING_MERGE_REQUEST, recovered.auditEvents.last().state)
        assertEquals(null, recovered.activeOperation)
    }

    @Test
    fun `feature merge is reconciled with merge evidence after process exit`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val config = managedConfig(repository)
        val git = FakeProductionGitGateway()
        var interrupt = true
        val service = ProductionTagService(
            store = ProductionTagPipelineStore(ApplicationPaths(temporary.resolve("feature-post-push-crash-home"))),
            versions = ProductionVersionProvider {
                ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
            },
            git = git,
            now = { "now" },
            id = { "pipeline-feature-crash" },
            afterRemoteWrite = { if (interrupt && it == ProductionOperationAction.MERGE_FEATURES) error("simulated process exit") },
        )
        val pipeline = service.create(config, repository.id)
        service.createRelease(config, pipeline.id)
        service.selectFeatures(config, pipeline.id, listOf("feature/a"))

        assertFailsWith<IllegalStateException> { service.mergeFeatures(config, pipeline.id) }
        interrupt = false
        val recovered = service.resume(config, pipeline.id)

        assertEquals("release-with-features", recovered.releaseSha)
        assertEquals("recovered-sha-a", recovered.mergedFeatures.single().mergeCommit)
        assertEquals(ProductionAuditState.RECOVERED, recovered.auditEvents.last().state)
        assertEquals(null, recovered.activeOperation)
    }

    @Test
    fun `feature merge request source branch is recovered after process exit`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val config = managedConfig(repository)
        val git = FakeProductionGitGateway(featureMergeAwaiting = true)
        var interrupt = true
        val service = ProductionTagService(
            store = ProductionTagPipelineStore(ApplicationPaths(temporary.resolve("feature-mr-crash-home"))),
            versions = ProductionVersionProvider {
                ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
            },
            git = git,
            now = { "now" },
            id = { "feature-mr-crash" },
            afterRemoteWrite = { if (interrupt && it == ProductionOperationAction.MERGE_FEATURES) error("simulated process exit") },
        )
        val pipeline = service.create(config, repository.id)
        service.createRelease(config, pipeline.id)
        service.selectFeatures(config, pipeline.id, listOf("feature/a"))

        assertFailsWith<IllegalStateException> { service.mergeFeatures(config, pipeline.id) }
        interrupt = false
        val recovered = service.resume(config, pipeline.id)

        assertEquals(ProductionFeatureBatchState.AWAITING_MERGE_REQUEST, recovered.featureState)
        assertEquals("https://gitlab.example/feature-mr", recovered.mergeRequest?.url)
        assertEquals("recovered-sha-a", recovered.mergedFeatures.single().mergeCommit)
        assertEquals(ProductionAuditState.AWAITING_MERGE_REQUEST, recovered.auditEvents.last().state)
        assertEquals(null, recovered.activeOperation)
    }

    @Test
    fun `invalid repository configuration blocks Genbu before it is queried`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        var genbuCalls = 0
        val service = ProductionTagService(
            store = ProductionTagPipelineStore(ApplicationPaths(temporary.resolve("invalid-repo-home"))),
            versions = ProductionVersionProvider {
                genbuCalls += 1
                error("Genbu must not be called")
            },
            git = FakeProductionGitGateway(validationError = "origin/master missing"),
        )

        assertFailsWith<IllegalStateException> { service.create(managedConfig(repository), repository.id) }
        assertEquals(0, genbuCalls)
    }

    @Test
    fun `unadopted remote release blocks local pipeline creation`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val service = ProductionTagService(
            store = ProductionTagPipelineStore(ApplicationPaths(temporary.resolve("unadopted-home"))),
            versions = ProductionVersionProvider {
                ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
            },
            git = FakeProductionGitGateway(unadoptedReleaseSha = "someone-elses-release"),
        )

        val blocked = service.create(managedConfig(repository), repository.id)

        assertEquals("someone-elses-release", blocked.unmanagedReleaseSha)
        assertFailsWith<IllegalStateException> { service.createRelease(managedConfig(repository), blocked.id) }
    }

    private class FakeProductionGitGateway(
        private val conflict: ProductionConflict? = null,
        private val baselineState: ProductionBaselineState = ProductionBaselineState.ALREADY_CONTAINED,
        private val mergedTargetSha: String = "production-sha",
        private val throwDuringTagPush: Boolean = false,
        private val validationError: String? = null,
        private val unadoptedReleaseSha: String? = null,
        private val directProductionMerge: Boolean = false,
        private val featureMergeAwaiting: Boolean = false,
        private val releaseNoPushPermission: Boolean = false,
        private val onTagPush: () -> Unit = {},
    ) : ProductionGitGateway {
        var mergedBranches: List<String> = emptyList()
        var productionMergeCalls: Int = 0
        var formalTagValues: List<String> = listOf("3.11.70", "3.11.71.beta-1")
        private var remoteReleaseSha: String? = unadoptedReleaseSha
        private val remoteTags = mutableListOf<ProductionRemoteTag>()
        private var currentBaselineState: ProductionBaselineState = baselineState
        private var currentMasterSha: String = "master-sha"
        private var pendingProductionRequest: ProductionMergeRequest? = null
        private var pendingFeatureRequest: ProductionMergeRequest? = null

        override fun validateRepository(repository: RepositoryConfig) {
            validationError?.let(::error)
        }

        override fun operator(repository: RepositoryConfig): String? = "Test User <test@example.com>"

        override fun inspectBaseline(repository: RepositoryConfig, productionTag: String) =
            ProductionBaselineEvidence(productionTag, "production-sha", currentMasterSha, currentBaselineState)

        override fun formalTags(repository: RepositoryConfig) = formalTagValues

        override fun releaseHead(repository: RepositoryConfig, branch: String): String? = remoteReleaseSha

        override fun mergeProduction(repository: RepositoryConfig, pipeline: ProductionTagPipeline): ProductionBranchWrite {
            productionMergeCalls += 1
            return if (currentBaselineState == ProductionBaselineState.MERGE_REQUIRED && !directProductionMerge) {
                val request = ProductionMergeRequest(
                    "GITLAB",
                    "https://gitlab.example/mr",
                    pipeline.activeOperation?.sourceBranch ?: "source",
                    "master",
                    "production-merge",
                )
                pendingProductionRequest = request
                ProductionBranchWrite.AwaitingRequest(request)
            } else {
                currentBaselineState = ProductionBaselineState.ALREADY_CONTAINED
                currentMasterSha = "production-merge"
                ProductionBranchWrite.Direct(currentMasterSha)
            }
        }

        override fun recoverProductionWrite(
            repository: RepositoryConfig,
            targetBranch: String,
            beforeSha: String,
            productionSha: String,
            sourceBranch: String?,
        ): ProductionBranchWrite? = when {
            currentMasterSha != beforeSha -> ProductionBranchWrite.Direct(currentMasterSha)
            pendingProductionRequest?.sourceBranch == sourceBranch -> ProductionBranchWrite.AwaitingRequest(pendingProductionRequest!!)
            else -> null
        }

        override fun createRelease(repository: RepositoryConfig, pipeline: ProductionTagPipeline): String {
            if (releaseNoPushPermission) throw ProductionNoPushPermissionException()
            return pipeline.masterSha.also { remoteReleaseSha = it }
        }

        override fun resolveFeatures(repository: RepositoryConfig, branches: List<String>) = branches.mapIndexed { index, branch ->
            ProductionFeatureSelection(branch, "sha-${('a'.code + index).toChar()}")
        }

        override fun mergeFeatures(
            repository: RepositoryConfig,
            pipeline: ProductionTagPipeline,
            features: List<ProductionFeatureSelection>,
        ): ProductionFeatureWrite {
            conflict?.let { return ProductionFeatureWrite.Conflict(listOf(it)) }
            mergedBranches = features.map { it.branch }
            if (featureMergeAwaiting) {
                val request = ProductionMergeRequest(
                    "GITLAB",
                    "https://gitlab.example/feature-mr",
                    pipeline.activeOperation?.sourceBranch ?: "feature-source",
                    pipeline.releaseBranch,
                    "release-with-features",
                )
                pendingFeatureRequest = request
                return ProductionFeatureWrite.AwaitingRequest(
                    "release-with-features",
                    features.map { ProductionFeatureMergeRecord(it.branch, it.sha, "merge-${it.sha}", "now") },
                    request,
                )
            }
            remoteReleaseSha = "release-with-features"
            return ProductionFeatureWrite.Direct(
                releaseSha = "release-with-features",
                merges = features.map { ProductionFeatureMergeRecord(it.branch, it.sha, "merge-${it.sha}", "now") },
            )
        }

        override fun tagsForBase(repository: RepositoryConfig, baseVersion: String) = remoteTags.toList()

        override fun recoverFeatureWrite(
            repository: RepositoryConfig,
            releaseBranch: String,
            sourceBranch: String?,
            beforeSha: String,
            features: List<ProductionFeatureSelection>,
        ): ProductionFeatureWrite? {
            val merges = features.map { ProductionFeatureMergeRecord(it.branch, it.sha, "recovered-${it.sha}", "now") }
            if (pendingFeatureRequest?.sourceBranch == sourceBranch) {
                return ProductionFeatureWrite.AwaitingRequest("release-with-features", merges, pendingFeatureRequest!!)
            }
            val head = remoteReleaseSha?.takeIf { it != beforeSha } ?: return null
            return ProductionFeatureWrite.Direct(head, merges)
        }

        override fun pushTag(repository: RepositoryConfig, releaseBranch: String, tag: String, releaseSha: String) =
            if (throwDuringTagPush) error("simulated process interruption") else ProductionTagPush.Pushed(releaseSha).also {
                onTagPush()
                remoteTags += ProductionRemoteTag(tag, releaseSha)
            }

        override fun mergedTargetSha(repository: RepositoryConfig, targetBranch: String, expectedCommit: String) = mergedTargetSha
    }

    private fun managedConfig(repository: RepositoryConfig) = AppConfig(
        repositories = listOf(repository),
        groups = listOf(GroupConfig(
            id = "group",
            name = "Group",
            services = listOf(GroupServiceConfig.standard("service", repository.id, repository.name)),
        )),
    )
}
