package com.snowball.awm.core

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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
        val releaseSha = gateway.createRelease(repository, pipelineForRelease(repository, baseline, "1.0.1"))
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
            activeOperation = ProductionOperationLease(
                id = "feature-write",
                action = ProductionOperationAction.MERGE_FEATURES,
                startedAt = "now",
                expectedTargetSha = releaseSha,
                features = features,
                sourceBranch = "awm/production-tag/merge_features/feature-write",
            ),
        )
        val merged = gateway.mergeFeatures(repository, pipeline, features)

        assertEquals(ProductionBaselineState.ALREADY_CONTAINED, baseline.state)
        assertTrue("1.0.0" in gateway.formalTags(repository))
        val direct = assertIs<ProductionFeatureWrite.Direct>(merged)
        assertEquals(direct.releaseSha, gateway.mergedTargetSha(repository, "release/1.0.1", features.single().sha))
        assertEquals(features.single().sha, direct.merges.single().sourceSha)
        val recovered = gateway.recoverFeatureWrite(repository, "release/1.0.1", null, releaseSha, features)
        val recoveredDirect = assertIs<ProductionFeatureWrite.Direct>(recovered)
        assertEquals(direct.releaseSha, recoveredDirect.releaseSha)
        assertEquals(direct.merges.single().mergeCommit, recoveredDirect.merges.single().mergeCommit)
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
        val releaseSha = gateway.createRelease(repository, pipelineForRelease(repository, baseline, "1.0.1"))
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

    @Test
    fun `tag build rejects a stale release sha and creates no remote tag`() {
        val (remote, seed) = GitTestSupport.createRemoteWithSeed(temporary.resolve("stale-release-fixture"))
        GitTestSupport.run(seed, "tag", "1.0.0")
        GitTestSupport.run(seed, "push", "origin", "refs/tags/1.0.0")
        val repositoryPath = GitTestSupport.clone(remote, temporary.resolve("stale-release-repository"))
        val repository = repository(repositoryPath, remote, "stale-release")
        val gateway = GitProductionTagGateway(paths = ApplicationPaths(temporary.resolve("stale-release-home")))
        val baseline = gateway.inspectBaseline(repository, "1.0.0")
        val releaseSha = gateway.createRelease(repository, pipelineForRelease(repository, baseline, "1.0.1"))
        Files.writeString(seed.resolve("late.txt"), "late\n")
        GitTestSupport.run(seed, "add", "late.txt")
        GitTestSupport.run(seed, "commit", "-m", "late release change")
        GitTestSupport.run(seed, "push", "origin", "HEAD:refs/heads/release/1.0.1")

        val result = gateway.pushTag(repository, "release/1.0.1", "1.0.1", releaseSha)

        assertIs<ProductionTagPush.Failed>(result)
        assertTrue(GitClient().readOnly(repositoryPath, "ls-remote", "--tags", "origin", "refs/tags/1.0.1").stdout.isBlank())
    }

    @Test
    fun `remote deletion is not hidden by a stale local production tag`() {
        val (remote, seed) = GitTestSupport.createRemoteWithSeed(temporary.resolve("deleted-tag-fixture"))
        GitTestSupport.run(seed, "tag", "1.0.0")
        GitTestSupport.run(seed, "push", "origin", "refs/tags/1.0.0")
        val repositoryPath = GitTestSupport.clone(remote, temporary.resolve("deleted-tag-repository"))
        val repository = repository(repositoryPath, remote, "deleted-tag")
        val gateway = GitProductionTagGateway(paths = ApplicationPaths(temporary.resolve("deleted-tag-home")))
        gateway.inspectBaseline(repository, "1.0.0")
        GitTestSupport.run(seed, "push", "origin", ":refs/tags/1.0.0")

        assertFailsWith<IllegalStateException> { gateway.inspectBaseline(repository, "1.0.0") }
    }

    @Test
    fun `feature branch input must be an exact feature ref`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("exact-feature-fixture"))
        val repositoryPath = GitTestSupport.clone(remote, temporary.resolve("exact-feature-repository"))
        val repository = repository(repositoryPath, remote, "exact-feature")
        val gateway = GitProductionTagGateway(paths = ApplicationPaths(temporary.resolve("exact-feature-home")))

        assertFailsWith<IllegalArgumentException> { gateway.resolveFeatures(repository, listOf("feature/*")) }
        assertFailsWith<IllegalArgumentException> { gateway.resolveFeatures(repository, listOf("bugfix/a")) }
    }

    @Test
    fun `repository origin drift aborts before remote production access`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("origin-fixture"))
        val (otherRemote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("other-origin-fixture"))
        val repositoryPath = GitTestSupport.clone(remote, temporary.resolve("origin-repository"))
        val repository = repository(repositoryPath, remote, "origin")
        val gateway = GitProductionTagGateway(paths = ApplicationPaths(temporary.resolve("origin-home")))
        GitTestSupport.run(repositoryPath, "remote", "set-url", "origin", otherRemote.toString())

        val error = assertFailsWith<IllegalStateException> { gateway.formalTags(repository) }

        assertTrue(error.message.orEmpty().contains("origin 已发生变化"))
    }

    @Test
    fun `formal tag push ignores an annotated local tag and creates a lightweight remote tag`() {
        val (remote, seed) = GitTestSupport.createRemoteWithSeed(temporary.resolve("lightweight-fixture"))
        GitTestSupport.run(seed, "tag", "1.0.0")
        GitTestSupport.run(seed, "push", "origin", "refs/tags/1.0.0")
        val repositoryPath = GitTestSupport.clone(remote, temporary.resolve("lightweight-repository"))
        val repository = repository(repositoryPath, remote, "lightweight")
        val gateway = GitProductionTagGateway(paths = ApplicationPaths(temporary.resolve("lightweight-home")))
        val baseline = gateway.inspectBaseline(repository, "1.0.0")
        val releaseSha = gateway.createRelease(repository, pipelineForRelease(repository, baseline, "1.0.1"))
        GitTestSupport.run(repositoryPath, "tag", "-a", "1.0.1", releaseSha, "-m", "stale annotated local tag")

        val result = gateway.pushTag(repository, "release/1.0.1", "1.0.1", releaseSha)
        val remoteRefs = GitClient().readOnly(repositoryPath, "ls-remote", "--tags", "origin", "refs/tags/1.0.1", "refs/tags/1.0.1^{}").stdout

        assertIs<ProductionTagPush.Pushed>(result)
        assertTrue(remoteRefs.contains("refs/tags/1.0.1"))
        assertFalse(remoteRefs.contains("refs/tags/1.0.1^{}"))
    }

    private fun repository(repositoryPath: Path, remote: Path, suffix: String) = RepositoryConfig(
        "repo-$suffix",
        "service-$suffix",
        repositoryPath.toString(),
        repositoryPath.resolve(".git").toString(),
        remote.toString(),
        "master",
        "master",
    )

    private fun pipelineForRelease(
        repository: RepositoryConfig,
        baseline: ProductionBaselineEvidence,
        baseVersion: String,
    ) = ProductionTagPipeline(
        id = "release-${repository.id}",
        repositoryId = repository.id,
        serviceName = repository.name,
        productionTag = baseline.productionTag,
        productionTagSha = baseline.productionTagSha,
        masterSha = baseline.masterSha,
        baselineState = baseline.state,
        baseVersion = baseVersion,
        releaseBranch = "release/$baseVersion",
    )
}
