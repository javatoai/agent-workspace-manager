package com.snowball.awm.core

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Regression for a candidate Tag that already exists locally on another commit. */
class TagCandidateCollisionRecoveryTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `candidate collision persists tag and commit for same-operation resume`() {
        val (remote, seed) = GitTestSupport.createRemoteWithSeed(temporary.resolve("git"))
        GitTestSupport.run(seed, "branch", "release/test")
        GitTestSupport.run(seed, "push", "origin", "release/test")
        GitTestSupport.run(seed, "tag", "-a", "1.0.0.beta-0", "-m", "base tag")
        GitTestSupport.run(seed, "push", "origin", "--tags")

        val repositoryPath = GitTestSupport.clone(remote, temporary.resolve("repository"))
        GitTestSupport.run(repositoryPath, "checkout", "-b", "unrelated-tag-branch")
        Files.writeString(repositoryPath.resolve("unrelated.txt"), "different history")
        GitTestSupport.run(repositoryPath, "add", "unrelated.txt")
        GitTestSupport.run(repositoryPath, "commit", "-m", "unrelated")
        GitTestSupport.run(repositoryPath, "tag", "-a", "1.0.0.beta-1", "-m", "collision on unrelated history")
        GitTestSupport.run(repositoryPath, "checkout", "master")

        val repository = GitRepositoryInspector().inspect(repositoryPath)
        val taskRoot = temporary.resolve("tasks")
        val taskDirectory = Files.createDirectories(taskRoot.resolve("task-review"))
        val workspacePath = taskDirectory.resolve("service")
        val git = GitClient()
        git.addWorktree(repositoryPath, workspacePath, "feature/review", "origin/master")
        GitTestSupport.configureIdentity(workspacePath)
        Files.writeString(workspacePath.resolve("feature.txt"), "feature change")
        GitTestSupport.run(workspacePath, "add", "feature.txt")
        GitTestSupport.run(workspacePath, "commit", "-m", "feature")

        val workspace = ServiceWorkspace(
            repositoryId = repository.id,
            serviceName = "service",
            repositoryPath = repositoryPath.toString(),
            worktreePath = workspacePath.toString(),
            developmentTool = DevelopmentToolType.INTELLIJ_IDEA,
            branch = "feature/review",
            health = WorkspaceHealth.READY,
            groupServiceId = "service",
            tagEnabled = true,
            tagTargetRef = "origin/release/test",
        )
        val manifest = TaskManifest(
            folderName = "task-review",
            taskDirectoryName = "task-review",
            featureBranch = "feature/review",
            createdAt = "2026-09-08 12:00:00",
            updatedAt = "2026-09-08 12:00:00",
            services = listOf(workspace),
            groupId = DEFAULT_GROUP_ID,
        )
        ManifestStore().save(taskDirectory, manifest)
        val config = AppConfig(
            taskRoot = taskRoot.toString(),
            repositories = listOf(repository),
            groups = listOf(
                GroupConfig(
                    DEFAULT_GROUP_ID,
                    DEFAULT_GROUP_NAME,
                    services = listOf(GroupServiceConfig.standard("service", repository.id, "service")),
                ),
            ),
        )
        val builder = TagBuildService(paths = ApplicationPaths(temporary.resolve("home")))

        val preflight = builder.preflight(config, taskDirectory, repository.id)
        assertEquals("1.0.0.beta-1", preflight.estimatedTag)
        val partial = builder.build(config, taskDirectory, repository.id)

        assertEquals(TagOperationState.PARTIAL, partial.state, partial.message)
        assertEquals("1.0.0.beta-1", partial.tag)
        assertTrue(partial.targetSha != null)
        assertTrue(
            GitTestSupport.run(repositoryPath, "ls-remote", "origin", "refs/heads/release/test")
                .substringBefore('\t')
                .let { GitClient().isAncestor(repositoryPath, partial.sourceSha!!, it) },
        )

        // Resolve only the local name collision; the persisted candidate and
        // target SHA make this same operation resumable without recomputing it.
        GitTestSupport.run(repositoryPath, "tag", "-d", "1.0.0.beta-1")
        val resumed = builder.resumePartial(config, taskDirectory, partial.operationId)

        assertEquals(TagOperationState.SUCCESS, resumed.state, resumed.message)
        assertEquals(partial.operationId, resumed.operationId)
        assertEquals(partial.tag, resumed.tag)
        assertEquals(
            resumed.targetSha,
            GitTestSupport.run(repositoryPath, "rev-parse", "refs/tags/1.0.0.beta-1^{}").trim(),
        )
        assertTrue(
            GitTestSupport.run(repositoryPath, "ls-remote", "origin", "refs/tags/1.0.0.beta-1")
                .isNotBlank(),
        )
    }
}
