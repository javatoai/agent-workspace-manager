package com.snowball.awm.core

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
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
        assertEquals("release-sha", released.releaseSha)
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

        val error = assertFailsWith<ProductionBaselineChangedException> {
            service.mergeProduction(config, created.id)
        }

        assertEquals("3.11.71", error.refreshed.productionTag)
        assertEquals("3.11.71", store.get(created.id)?.productionTag)
        assertEquals(0, git.productionMergeCalls)
    }

    @Test
    fun `tag push crash leaves a durable pushing record for recovery`() {
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

        val record = store.get(pipeline.id)!!.buildRecords.single()
        assertEquals(ProductionTagBuildState.PUSHING, record.state)
        assertEquals("3.11.71", record.expectedTag)
    }

    private class FakeProductionGitGateway(
        private val conflict: ProductionConflict? = null,
        private val baselineState: ProductionBaselineState = ProductionBaselineState.ALREADY_CONTAINED,
        private val mergedTargetSha: String = "production-sha",
        private val throwDuringTagPush: Boolean = false,
    ) : ProductionGitGateway {
        var mergedBranches: List<String> = emptyList()
        var productionMergeCalls: Int = 0

        override fun inspectBaseline(repository: RepositoryConfig, productionTag: String) =
            ProductionBaselineEvidence(productionTag, "production-sha", "master-sha", baselineState)

        override fun formalTags(repository: RepositoryConfig) = listOf("3.11.70", "3.11.71.beta-1")

        override fun mergeProduction(repository: RepositoryConfig, pipeline: ProductionTagPipeline): ProductionBranchWrite {
            productionMergeCalls += 1
            return if (baselineState == ProductionBaselineState.MERGE_REQUIRED) {
                ProductionBranchWrite.AwaitingRequest(
                    ProductionMergeRequest("GITLAB", "https://gitlab.example/mr", "source", "master", "production-merge"),
                )
            } else ProductionBranchWrite.Direct("production-sha")
        }

        override fun createRelease(repository: RepositoryConfig, branch: String, masterSha: String) = "release-sha"

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
            return ProductionFeatureWrite.Direct(
                releaseSha = "release-with-features",
                merges = features.map { ProductionFeatureMergeRecord(it.branch, it.sha, "merge-${it.sha}", "now") },
            )
        }

        override fun tagsForBase(repository: RepositoryConfig, baseVersion: String) = emptyList<ProductionRemoteTag>()

        override fun pushTag(repository: RepositoryConfig, releaseBranch: String, tag: String, releaseSha: String) =
            if (throwDuringTagPush) error("simulated process interruption") else ProductionTagPush.Pushed(releaseSha)

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
