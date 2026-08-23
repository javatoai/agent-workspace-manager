package com.snowball.awm.core

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
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

        store.save(pipeline)
        val reopened = ProductionTagPipelineStore(paths).get("pipeline-1")

        assertEquals(pipeline, reopened)
        assertFalse(reopened!!.closed)
        assertEquals(listOf(pipeline), ProductionTagPipelineStore(paths).activeFor("repo-1"))
    }
}
