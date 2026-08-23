package com.snowball.awm.core

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProductionTagGitIntegrationTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `gateway inspects production baseline creates release and merges ordered features`() {
        val (remote, seed) = GitTestSupport.createRemoteWithSeed(temporary.resolve("fixture"))
        GitTestSupport.run(seed, "tag", "1.0.0")
        GitTestSupport.run(seed, "push", "origin", "refs/tags/1.0.0")
        GitTestSupport.run(seed, "switch", "-c", "feature/a")
        Files.writeString(seed.resolve("feature-a.txt"), "a\n")
        GitTestSupport.run(seed, "add", "feature-a.txt")
        GitTestSupport.run(seed, "commit", "-m", "feature a")
        GitTestSupport.run(seed, "push", "origin", "feature/a")
        val repositoryPath = GitTestSupport.clone(remote, temporary.resolve("repository"))
        val repository = RepositoryConfig(
            "repo",
            "service",
            repositoryPath.toString(),
            repositoryPath.resolve(".git").toString(),
            remote.toString(),
            "master",
            "master",
        )
        val paths = ApplicationPaths(temporary.resolve("awm-home"))
        val gateway = GitProductionTagGateway(paths = paths)

        val baseline = gateway.inspectBaseline(repository, "1.0.0")
        val releaseSha = gateway.createRelease(repository, "release/1.0.1", baseline.masterSha)
        val features = gateway.resolveFeatures(repository, listOf("feature/a"))
        val pipeline = ProductionTagPipeline(
            "pipeline",
            repository.id,
            repository.name,
            "1.0.0",
            baseline.productionTagSha,
            baseline.masterSha,
            baseline.state,
            "1.0.1",
            "release/1.0.1",
            releaseSha,
        )
        val merged = gateway.mergeFeatures(repository, pipeline, features)

        assertEquals(ProductionBaselineState.ALREADY_CONTAINED, baseline.state)
        assertTrue("1.0.0" in gateway.formalTags(repository))
        assertTrue(merged is ProductionFeatureWrite.Direct)
        merged as ProductionFeatureWrite.Direct
        assertEquals(merged.releaseSha, gateway.mergedTargetSha(repository, "release/1.0.1", features.single().sha))
        assertEquals(features.single().sha, merged.merges.single().sourceSha)
    }

    @Test
    fun `conflicting feature batch stops before any release branch push`() {
        val (remote, seed) = GitTestSupport.createRemoteWithSeed(temporary.resolve("conflict-fixture"))
        GitTestSupport.run(seed, "tag", "1.0.0")
        GitTestSupport.run(seed, "push", "origin", "refs/tags/1.0.0")
        GitTestSupport.run(seed, "switch", "-c", "feature/a")
        Files.writeString(seed.resolve("README.md"), "feature a\n")
        GitTestSupport.run(seed, "commit", "-am", "feature a")
        GitTestSupport.run(seed, "push", "origin", "feature/a")
        GitTestSupport.run(seed, "switch", "master")
        GitTestSupport.run(seed, "switch", "-c", "feature/b")
        Files.writeString(seed.resolve("README.md"), "feature b\n")
        GitTestSupport.run(seed, "commit", "-am", "feature b")
        GitTestSupport.run(seed, "push", "origin", "feature/b")
        val repositoryPath = GitTestSupport.clone(remote, temporary.resolve("conflict-repository"))
        val repository = RepositoryConfig(
            "repo-conflict",
            "service",
            repositoryPath.toString(),
            repositoryPath.resolve(".git").toString(),
            remote.toString(),
            "master",
            "master",
        )
        val gateway = GitProductionTagGateway(paths = ApplicationPaths(temporary.resolve("conflict-awm-home")))
        val baseline = gateway.inspectBaseline(repository, "1.0.0")
        val releaseSha = gateway.createRelease(repository, "release/1.0.1", baseline.masterSha)
        val features = gateway.resolveFeatures(repository, listOf("feature/a", "feature/b"))
        val pipeline = ProductionTagPipeline(
            "pipeline-conflict",
            repository.id,
            repository.name,
            "1.0.0",
            baseline.productionTagSha,
            baseline.masterSha,
            baseline.state,
            "1.0.1",
            "release/1.0.1",
            releaseSha,
        )

        val result = gateway.mergeFeatures(repository, pipeline, features)

        assertTrue(result is ProductionFeatureWrite.Conflict)
        assertEquals("feature/b", result.conflicts.single().branch)
        assertEquals(listOf("README.md"), result.conflicts.single().files)
        assertFalse(gateway.mergedTargetSha(repository, "release/1.0.1", features.first().sha) != null)
        assertEquals(releaseSha, gateway.mergedTargetSha(repository, "release/1.0.1", releaseSha))
    }
}
