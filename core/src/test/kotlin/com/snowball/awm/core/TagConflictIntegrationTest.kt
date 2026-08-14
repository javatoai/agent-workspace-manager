package com.snowball.awm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class TagConflictIntegrationTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `reports conflicts and leaves feature worktree untouched`() {
        val (remote, seed) = GitTestSupport.createRemoteWithSeed(temporary.resolve("source"))
        GitTestSupport.run(seed, "branch", "release/test")
        GitTestSupport.run(seed, "push", "origin", "release/test")
        val repository = GitTestSupport.clone(remote, temporary.resolve("services").resolve("conflict-service"))
        val repositoryInfo = GitRepositoryInspector().inspect(repository)

        val featureWorktree = temporary.resolve("tasks").resolve("CONFLICT-1")
            .resolve("conflict-service")
        Files.createDirectories(featureWorktree.parent)
        GitClient().addWorktree(repository, featureWorktree, "feature/CONFLICT-1", "origin/master")
        GitTestSupport.configureIdentity(featureWorktree)
        Files.writeString(featureWorktree.resolve("README.md"), "feature version\n")
        GitTestSupport.run(featureWorktree, "add", "README.md")
        GitTestSupport.run(featureWorktree, "commit", "-m", "feature edit")
        val featureSha = GitTestSupport.run(featureWorktree, "rev-parse", "HEAD")

        GitTestSupport.run(repository, "switch", "release/test")
        Files.writeString(repository.resolve("README.md"), "test version\n")
        GitTestSupport.run(repository, "add", "README.md")
        GitTestSupport.run(repository, "commit", "-m", "test edit")
        GitTestSupport.run(repository, "push", "origin", "release/test")
        GitTestSupport.run(repository, "switch", "master")

        val taskDirectory = temporary.resolve("tasks").resolve("CONFLICT-1")
        val now = Instant.now().toString()
        ManifestStore().save(
            taskDirectory,
            TaskManifest(
                folderName = "CONFLICT-1",
                taskDirectoryName = "CONFLICT-1",
                featureBranch = "feature/CONFLICT-1",
                createdAt = now,
                updatedAt = now,
                lifecycleStatus = TaskLifecycleStatus.ACTIVE,
                services = listOf(
                    ServiceWorkspace(
                        repositoryId = repositoryInfo.id,
                        serviceName = "conflict-service",
                        repositoryPath = repository.toString(),
                        worktreePath = featureWorktree.toString(),
                        developmentTool = DevelopmentToolType.INTELLIJ_IDEA,
                        branch = "feature/CONFLICT-1",
                        health = WorkspaceHealth.READY,
                        groupServiceId = "conflict-service",
                        tagEnabled = true,
                        tagTargetRef = "origin/release/test",
                    ),
                ),
            ),
        )
        val config = AppConfig(
            taskRoot = temporary.resolve("tasks").toString(),
            repositories = listOf(repositoryInfo),
            groups = listOf(
                GroupConfig(DEFAULT_GROUP_ID, DEFAULT_GROUP_NAME, services = listOf(
                    GroupServiceConfig.standard(
                        id = "conflict-service",
                        repositoryId = repositoryInfo.id,
                        displayName = "conflict-service",
                    ).copy(modules = listOf(ServiceModuleConfig("default"))),
                )),
            ),
        )
        val builder = TagBuildService(
            paths = ApplicationPaths(temporary.resolve("app-home")),
        )

        val error = assertThrows(MergeConflictException::class.java) {
            builder.preflight(config, taskDirectory, repositoryInfo.id)
        }

        assertEquals(listOf("README.md"), error.files)
        assertEquals(featureSha, GitTestSupport.run(featureWorktree, "rev-parse", "HEAD"))
        assertEquals("feature version", Files.readString(featureWorktree.resolve("README.md")).trim())
        assertTrue(GitClient().worktrees(repository).none { it.path.startsWith(temporary.resolve("app-home")) })
    }
}
