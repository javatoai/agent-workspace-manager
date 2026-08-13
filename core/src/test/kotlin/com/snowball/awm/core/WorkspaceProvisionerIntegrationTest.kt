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
    fun `ordinary workspace provisioning ignores conflicting local and remote tags`() {
        val (remote, seed) = GitTestSupport.createRemoteWithSeed(temporary.resolve("tag-conflict-create"))
        GitTestSupport.run(seed, "tag", "same-tag")
        GitTestSupport.run(seed, "push", "origin", "same-tag")
        val repositoryPath = GitTestSupport.clone(remote, temporary.resolve("tag-conflict-create").resolve("service"))
        Files.writeString(seed.resolve("tag-move.txt"), "new\n")
        GitTestSupport.run(seed, "add", "tag-move.txt")
        GitTestSupport.run(seed, "commit", "-m", "move tag")
        GitTestSupport.run(seed, "tag", "-f", "same-tag")
        GitTestSupport.run(seed, "push", "--force", "origin", "refs/tags/same-tag")
        val repository = GitRepositoryInspector().inspect(repositoryPath)
        val service = GroupServiceConfig.standard("tag-service", repository.id, "tag", baseRef = "origin/master")

        val workspace = StandardWorktreeProvisioner().provision(
            WorkspaceProvisionRequest(temporary.resolve("tag-conflict-create").resolve("task"), repository, service, "feature/tag-safe"),
        ).single()

        assertEquals("feature/tag-safe", workspace.branch)
    }

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
                ServiceModuleConfig("master", "master", "origin/master"),
                ServiceModuleConfig("development", "development", "origin/development"),
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
    fun `standard strategy creates independent worktrees for modules on the same base branch`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("same-base"))
        val repositoryPath = GitTestSupport.clone(remote, temporary.resolve("same-base/service"))
        val repository = GitRepositoryInspector().inspect(repositoryPath)
        val service = GroupServiceConfig(
            id = "same-base-service",
            repositoryId = repository.id,
            displayName = "same-base",
            modules = listOf(
                ServiceModuleConfig("api", "api", "origin/master"),
                ServiceModuleConfig("job", "job", "origin/master"),
            ),
        )

        val workspaces = StandardWorktreeProvisioner().provision(
            WorkspaceProvisionRequest(
                taskDirectory = temporary.resolve("same-base/task"),
                repository = repository,
                service = service,
                requestedFeatureBranch = "feature/OBT-123",
                moduleBranches = mapOf("api" to "feature/custom-api"),
            ),
        )

        assertEquals(listOf("feature/custom-api", "feature/OBT-123-job"), workspaces.map { it.branch })
        assertEquals(2, workspaces.map { Path.of(it.worktreePath) }.distinct().size)
        assertTrue(workspaces.all { Files.isDirectory(Path.of(it.worktreePath)) })
    }

    @Test
    fun `standard provisioning uses the shared common directory repository lock`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("provision-lock"))
        val repositoryPath = GitTestSupport.clone(remote, temporary.resolve("provision-lock/service"))
        val repository = GitRepositoryInspector().inspect(repositoryPath)
        val service = GroupServiceConfig.standard("service", repository.id, "Service", baseRef = "origin/master")
        val taskDirectory = temporary.resolve("provision-lock/task")
        val lock = RepositoryOperationLock(ApplicationPaths(temporary.resolve("provision-lock-home")))
        val provisioner = StandardWorktreeProvisioner(repositoryLock = lock)

        lock.withLock(GitClient().commonDirectory(repositoryPath)) {
            assertThrows(IllegalStateException::class.java) {
                provisioner.provision(WorkspaceProvisionRequest(taskDirectory, repository, service, "feature/locked"))
            }
        }

        assertTrue(Files.notExists(taskDirectory))
        assertEquals(false, GitClient().refExists(repositoryPath, "refs/heads/feature/locked"))
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
            cloneModules = listOf(IndependentCloneModuleConfig("clone", branch = "origin/master", tagEnabled = true)),
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

    @Test
    fun `existing local branch needs confirmation then attaches without changing ownership`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("reuse-local"))
        val repositoryPath = GitTestSupport.clone(remote, temporary.resolve("reuse-local").resolve("service"))
        val repository = GitRepositoryInspector().inspect(repositoryPath)
        val branch = "feature/reuse-local"
        GitTestSupport.run(repositoryPath, "branch", branch, "origin/master")
        val service = GroupServiceConfig.standard("reuse-local", repository.id, "reuse-local", baseRef = "master")
        val provisioner = StandardWorktreeProvisioner()

        assertThrows(Throwable::class.java) {
            provisioner.provision(
                WorkspaceProvisionRequest(temporary.resolve("reuse-local").resolve("unconfirmed"), repository, service, branch),
            )
        }
        assertTrue(GitClient().refExists(repositoryPath, "refs/heads/$branch"))

        val workspace = provisioner.provision(
            WorkspaceProvisionRequest(
                temporary.resolve("reuse-local").resolve("confirmed"),
                repository,
                service,
                branch,
                setOf(BranchReuseKey(repository.id, branch)),
            ),
        ).single()

        assertEquals(false, workspace.branchCreatedByTask)
        assertEquals(false, workspace.forceWorktreeAttach)
        assertEquals(branch, GitClient().currentBranch(Path.of(workspace.worktreePath)))
    }

    @Test
    fun `occupied branch requires force attach and can be attached again after removal`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("reuse-occupied"))
        val repositoryPath = GitTestSupport.clone(remote, temporary.resolve("reuse-occupied").resolve("service"))
        val repository = GitRepositoryInspector().inspect(repositoryPath)
        val branch = "feature/reuse-occupied"
        GitTestSupport.run(repositoryPath, "branch", branch, "origin/master")
        val git = GitClient()
        git.addExistingWorktree(repositoryPath, temporary.resolve("reuse-occupied").resolve("existing"), branch)
        val service = GroupServiceConfig.standard("reuse-occupied", repository.id, "reuse-occupied", baseRef = "master")

        val workspace = StandardWorktreeProvisioner().provision(
            WorkspaceProvisionRequest(
                temporary.resolve("reuse-occupied").resolve("task"),
                repository,
                service,
                branch,
                setOf(BranchReuseKey(repository.id, branch)),
            ),
        ).single()

        assertTrue(workspace.forceWorktreeAttach)
        val target = Path.of(workspace.worktreePath)
        git.removeWorktree(repositoryPath, target, force = true)
        git.addExistingWorktree(repositoryPath, target, branch, force = workspace.forceWorktreeAttach)
        assertEquals(branch, git.currentBranch(target))
    }

    @Test
    fun `stale worktree registration is pruned before branch reuse inspection`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("reuse-stale"))
        val repositoryPath = GitTestSupport.clone(remote, temporary.resolve("reuse-stale").resolve("service"))
        val repository = GitRepositoryInspector().inspect(repositoryPath)
        val branch = "feature/reuse-stale"
        GitTestSupport.run(repositoryPath, "branch", branch, "origin/master")
        val stale = temporary.resolve("reuse-stale").resolve("deleted-worktree")
        GitClient().addExistingWorktree(repositoryPath, stale, branch)
        Files.walk(stale).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }

        val service = GroupServiceConfig.standard("reuse-stale", repository.id, "reuse-stale", baseRef = "master")
        val conflicts = WorkspaceBranchReuseInspector().inspect(repository, service, branch)

        assertEquals(listOf(branch), conflicts.map { it.key.branch })
        assertTrue(conflicts.single().occupiedWorktreePaths.isEmpty())
        assertTrue(GitClient().worktrees(repositoryPath).none { it.branch == branch })
    }

    @Test
    fun `locked worktree is never force attached`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("reuse-locked"))
        val repositoryPath = GitTestSupport.clone(remote, temporary.resolve("reuse-locked").resolve("service"))
        val repository = GitRepositoryInspector().inspect(repositoryPath)
        val branch = "feature/reuse-locked"
        GitTestSupport.run(repositoryPath, "branch", branch, "origin/master")
        val locked = temporary.resolve("reuse-locked").resolve("locked-worktree")
        GitClient().addExistingWorktree(repositoryPath, locked, branch)
        GitTestSupport.run(repositoryPath, "worktree", "lock", "--reason", "protected", locked.toString())
        val service = GroupServiceConfig.standard("reuse-locked", repository.id, "reuse-locked", baseRef = "master")

        val conflict = WorkspaceBranchReuseInspector().inspect(repository, service, branch).single()
        assertEquals(listOf(locked.toString()), conflict.lockedWorktreePaths)
        assertThrows(IllegalArgumentException::class.java) {
            StandardWorktreeProvisioner().provision(
                WorkspaceProvisionRequest(
                    temporary.resolve("reuse-locked").resolve("task"),
                    repository,
                    service,
                    branch,
                    setOf(BranchReuseKey(repository.id, branch)),
                ),
            )
        }
    }

    @Test
    fun `branch reuse confirmation is rejected when worktree state changes`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("reuse-changed"))
        val repositoryPath = GitTestSupport.clone(remote, temporary.resolve("reuse-changed").resolve("service"))
        val repository = GitRepositoryInspector().inspect(repositoryPath)
        val branch = "feature/reuse-changed"
        GitTestSupport.run(repositoryPath, "branch", branch, "origin/master")
        val service = GroupServiceConfig.standard("reuse-changed", repository.id, "reuse-changed", baseRef = "master")
        val confirmation = WorkspaceBranchReuseInspector().inspect(repository, service, branch).single().key
        GitClient().addExistingWorktree(repositoryPath, temporary.resolve("reuse-changed").resolve("other"), branch)

        assertThrows(IllegalArgumentException::class.java) {
            StandardWorktreeProvisioner().provision(
                WorkspaceProvisionRequest(
                    temporary.resolve("reuse-changed").resolve("task"),
                    repository,
                    service,
                    branch,
                    setOf(confirmation),
                ),
            )
        }
    }

    @Test
    fun `remote-only branch needs confirmation and becomes a tracked local worktree`() {
        val (remote, seed) = GitTestSupport.createRemoteWithSeed(temporary.resolve("reuse-remote"))
        val branch = "feature/reuse-remote"
        GitTestSupport.run(seed, "switch", "-c", branch)
        GitTestSupport.run(seed, "push", "-u", "origin", branch)
        val repositoryPath = GitTestSupport.clone(remote, temporary.resolve("reuse-remote").resolve("service"))
        val repository = GitRepositoryInspector().inspect(repositoryPath)
        val service = GroupServiceConfig.standard("reuse-remote", repository.id, "reuse-remote", baseRef = "master")

        val workspace = StandardWorktreeProvisioner().provision(
            WorkspaceProvisionRequest(
                temporary.resolve("reuse-remote").resolve("task"),
                repository,
                service,
                branch,
                setOf(BranchReuseKey(repository.id, branch)),
            ),
        ).single()

        assertTrue(workspace.branchCreatedByTask)
        assertEquals("origin", GitTestSupport.run(repositoryPath, "config", "branch.$branch.remote"))
        assertEquals("refs/heads/$branch", GitTestSupport.run(repositoryPath, "config", "branch.$branch.merge"))
    }

    @Test
    fun `failed provisioning never deletes a reused local branch`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("reuse-rollback"))
        val repositoryPath = GitTestSupport.clone(remote, temporary.resolve("reuse-rollback").resolve("service"))
        val repository = GitRepositoryInspector().inspect(repositoryPath)
        val requestedBranch = "feature/reuse-rollback"
        val reusedBranch = "$requestedBranch-master"
        GitTestSupport.run(repositoryPath, "branch", reusedBranch, "origin/master")
        val service = GroupServiceConfig(
            id = "reuse-rollback",
            repositoryId = repository.id,
            displayName = "reuse-rollback",
            modules = listOf(
                ServiceModuleConfig("master", "master", "origin/master"),
                ServiceModuleConfig("missing", "missing", "origin/does-not-exist"),
            ),
        )

        assertThrows(Throwable::class.java) {
            StandardWorktreeProvisioner().provision(
                WorkspaceProvisionRequest(
                    temporary.resolve("reuse-rollback").resolve("task"),
                    repository,
                    service,
                    requestedBranch,
                    setOf(BranchReuseKey(repository.id, reusedBranch)),
                ),
            )
        }

        assertTrue(GitClient().refExists(repositoryPath, "refs/heads/$reusedBranch"))
        assertEquals(1, GitClient().worktrees(repositoryPath).size)
    }
}
