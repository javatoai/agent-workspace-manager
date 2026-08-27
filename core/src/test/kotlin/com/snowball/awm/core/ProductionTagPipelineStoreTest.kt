package com.snowball.awm.core

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ProductionTagPipelineStoreTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `pipeline and tag records survive reopening the store`() {
        val paths = ApplicationPaths(temporary.resolve("home"))
        val store = ProductionTagPipelineStore(paths)
        val pipeline = ProductionTagPipeline(
            id = "pipeline-1",
            repositoryId = "repo-1",
            serviceName = "android-transit-service",
            productionTag = "3.11.70",
            productionTagSha = "prod-sha",
            masterSha = "prod-sha",
            baselineState = ProductionBaselineState.ALREADY_CONTAINED,
            baseVersion = "3.11.71",
            releaseBranch = "release/3.11.71",
            releaseSha = "release-sha",
            buildRecords = listOf(
                ProductionTagBuildRecord("3.11.71", "release-sha", ProductionTagBuildState.PUSHED, "tag-sha"),
            ),
        )

        val saved = store.create(pipeline)
        val reopened = ProductionTagPipelineStore(paths).get("pipeline-1")

        assertEquals(saved, reopened)
        assertFalse(reopened!!.closed)
        assertEquals(listOf(saved), ProductionTagPipelineStore(paths).activeFor("repo-1"))
    }

    @Test
    fun `two store instances cannot create duplicate active pipelines`() {
        val paths = ApplicationPaths(temporary.resolve("shared-home"))
        val first = ProductionTagPipelineStore(paths)
        val second = ProductionTagPipelineStore(paths)
        val pipeline = samplePipeline("first")

        first.create(pipeline)

        assertFailsWith<IllegalStateException> { second.create(samplePipeline("second")) }
        assertEquals(listOf("first"), second.all().map { it.id })
    }

    @Test
    fun `stale cross process save cannot overwrite a newer pipeline`() {
        val paths = ApplicationPaths(temporary.resolve("cas-home"))
        val first = ProductionTagPipelineStore(paths)
        val second = ProductionTagPipelineStore(paths)
        val created = first.create(samplePipeline("pipeline"))
        val stale = second.get(created.id)!!
        first.save(created.copy(masterSha = "new-master"))

        assertFailsWith<IllegalStateException> { second.save(stale.copy(masterSha = "stale-master")) }
        assertEquals("new-master", second.get(created.id)?.masterSha)
    }

    private fun samplePipeline(id: String) = ProductionTagPipeline(
        id = id,
        repositoryId = "repo",
        serviceName = "service",
        productionTag = "1.0.0",
        productionTagSha = "tag-sha",
        masterSha = "master-sha",
        baselineState = ProductionBaselineState.ALREADY_CONTAINED,
        baseVersion = "1.0.1",
        releaseBranch = "release/1.0.1",
    )
}
