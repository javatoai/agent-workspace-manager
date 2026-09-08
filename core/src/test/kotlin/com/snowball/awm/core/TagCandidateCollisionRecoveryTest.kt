package com.snowball.awm.core

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Regression for a local-only candidate Tag that is removed by authoritative sync. */
class TagCandidateCollisionRecoveryTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `local-only candidate is pruned before the next tag is created`() {
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
        val operation = builder.build(config, taskDirectory, repository.id)

        assertEquals(TagOperationState.SUCCESS, operation.state, operation.message)
        assertEquals("1.0.0.beta-1", operation.tag)
        assertTrue(operation.targetSha != null)
        assertTrue(
            GitTestSupport.run(repositoryPath, "ls-remote", "origin", "refs/heads/release/test")
                .substringBefore('\t')
                .let { GitClient().isAncestor(repositoryPath, operation.sourceSha!!, it) },
        )

        assertEquals(
            operation.targetSha,
            GitTestSupport.run(repositoryPath, "rev-parse", "refs/tags/1.0.0.beta-1^{}").trim(),
        )
        assertEquals(
            operation.targetSha,
            GitTestSupport.run(repositoryPath, "ls-remote", "origin", "refs/tags/1.0.0.beta-1^{}")
                .substringBefore('\t')
                .trim(),
        )
    }
}
