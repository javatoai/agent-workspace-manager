package com.snowball.awm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class WorkspaceProvisionerIntegrationTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `standard strategy creates one worktree for each base branch module`() {
        val (remote, seed) = GitTestSupport.createRemoteWithSeed(temporary.resolve("standard"))
        GitTestSupport.run(seed, "switch", "-c", "development")
        Files.writeString(seed.resolve("development.txt"), "development\n")
        GitTestSupport.run(seed, "add", "development.txt")
        GitTestSupport.run(seed, "commit", "-m", "development")
        GitTestSupport.run(seed, "push", "-u", "origin", "development")
        val repositoryPath = GitTestSupport.clone(remote, temporary.resolve("standard").resolve("service"))
        val repository = GitRepositoryInspector().inspect(repositoryPath)
        val service = GroupServiceConfig(
            id = "service-standard",
            repositoryId = repository.id,
            displayName = "订单服务",
            modules = listOf(
                ServiceModuleConfig("master", "主线", "origin/master"),
                ServiceModuleConfig("development", "开发线", "origin/development"),
            ),
        )

        val workspaces = StandardWorktreeProvisioner().provision(
            WorkspaceProvisionRequest(
                taskDirectory = temporary.resolve("standard").resolve("task"),
                repository = repository,
                service = service,
                requestedFeatureBranch = "feature/OBT-123",
            ),
        )

        assertEquals(
            listOf("feature/OBT-123-master", "feature/OBT-123-development"),
            workspaces.map { it.branch },
        )
        assertTrue(workspaces.all { Files.isDirectory(Path.of(it.worktreePath)) })
    }

    @Test
    fun `clone strategy checks out the task branch override without creating a linked worktree`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("clone"))
        val repositoryPath = GitTestSupport.clone(remote, temporary.resolve("clone").resolve("source"))
        val repository = GitRepositoryInspector().inspect(repositoryPath)
        val service = GroupServiceConfig(
            id = "service-clone",
            repositoryId = repository.id,
            displayName = "复杂单仓",
            strategy = WorkspaceStrategy.INDEPENDENT_CLONE,
            modules = emptyList(),
            cloneModules = listOf(IndependentCloneModuleConfig("clone", branch = "origin/master", uatTagEnabled = true)),
        )

        val workspace = IndependentCloneProvisioner().provision(
            WorkspaceProvisionRequest(
                taskDirectory = temporary.resolve("clone").resolve("task"),
                repository = repository,
                service = service,
            ),
        ).single()

        assertEquals("master", workspace.branch)
        assertEquals("origin/master", workspace.baseRef)
        assertEquals(WorkspaceStrategy.INDEPENDENT_CLONE, workspace.strategy)
        assertEquals("master", GitClient().currentBranch(Path.of(workspace.worktreePath)))
        assertEquals(1, GitClient().worktrees(Path.of(workspace.worktreePath)).size)
    }

    @Test
    fun `standard strategy fetches and uses latest remote base without moving local master`() {
        val (remote, seed) = GitTestSupport.createRemoteWithSeed(temporary.resolve("latest-base"))
        val repositoryPath = GitTestSupport.clone(remote, temporary.resolve("latest-base").resolve("service"))
        val localMasterBefore = GitClient().resolve(repositoryPath, "refs/heads/master")
        Files.writeString(seed.resolve("latest.txt"), "latest\n")
        GitTestSupport.run(seed, "add", "latest.txt")
        GitTestSupport.run(seed, "commit", "-m", "latest")
        GitTestSupport.run(seed, "push", "origin", "master")
        val repository = GitRepositoryInspector().inspect(repositoryPath)
        val service = GroupServiceConfig.standard(
            id = "latest-service",
            repositoryId = repository.id,
            displayName = "latest",
            baseRef = "master",
        )

        val workspace = StandardWorktreeProvisioner().provision(
            WorkspaceProvisionRequest(
                temporary.resolve("latest-base").resolve("task"),
                repository,
                service,
                "feature/latest",
            ),
        ).single()

        assertTrue(Files.exists(Path.of(workspace.worktreePath).resolve("latest.txt")))
        assertEquals(localMasterBefore, GitClient().resolve(repositoryPath, "refs/heads/master"))
        assertEquals("origin/master", workspace.baseRef)
    }

    @Test
    fun `failed later module rolls back earlier worktree and task branches`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("rollback"))
        val repositoryPath = GitTestSupport.clone(remote, temporary.resolve("rollback").resolve("service"))
        val repository = GitRepositoryInspector().inspect(repositoryPath)
        val service = GroupServiceConfig(
            id = "rollback-service",
            repositoryId = repository.id,
            displayName = "rollback",
            modules = listOf(
                ServiceModuleConfig("master", "master", "origin/master"),
                ServiceModuleConfig("missing", "missing", "origin/does-not-exist"),
            ),
        )

        assertThrows(Throwable::class.java) {
            StandardWorktreeProvisioner().provision(
                WorkspaceProvisionRequest(
                    temporary.resolve("rollback").resolve("task"),
                    repository,
                    service,
                    "feature/rollback",
                ),
            )
        }

        assertEquals(1, GitClient().worktrees(repositoryPath).size)
        assertEquals(false, GitClient().refExists(repositoryPath, "refs/heads/feature/rollback-master"))
        assertEquals(false, GitClient().refExists(repositoryPath, "refs/heads/feature/rollback-does-not-exist"))
    }

    @Test
    fun `add worktree failure never deletes a branch created by another operation`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("existing-branch"))
        val repository = GitTestSupport.clone(remote, temporary.resolve("existing-branch").resolve("service"))
        GitTestSupport.run(repository, "branch", "feature/existing", "origin/master")

        assertThrows(Throwable::class.java) {
            GitClient().addWorktree(
                repository,
                temporary.resolve("existing-branch").resolve("task").resolve("service"),
                "feature/existing",
                "origin/master",
            )
        }

        assertTrue(GitClient().refExists(repository, "refs/heads/feature/existing"))
    }
}
