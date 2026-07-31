package com.snowball.taskwt.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class TaskManagerIntegrationTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `creates aggregates archives safely and restores existing branch`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("source"))
        val repository = GitTestSupport.clone(remote, temporary.resolve("services").resolve("job-manager"))
        Files.writeString(repository.resolve("local.template"), "local")
        Files.writeString(repository.resolve(".gitignore"), "local.conf\n")
        GitTestSupport.run(repository, "add", "local.template", ".gitignore")
        GitTestSupport.run(repository, "commit", "-m", "add bootstrap template")
        GitTestSupport.run(repository, "push", "origin", "master")
        val repositoryInfo = RepositoryScanner().scan(listOf(repository.parent), null).single()
        val taskRoot = temporary.resolve("tasks")
        val service = ServiceConfig(
            repositoryId = repositoryInfo.id,
            displayName = repositoryInfo.name,
            bootstrap = BootstrapConfig(
                copyRules = listOf(BootstrapCopyRule("local.template", "local.conf")),
            ),
        )
        val config = AppConfig(
            scanRoots = listOf(repository.parent.toString()),
            taskRoot = taskRoot.toString(),
            services = mapOf(repositoryInfo.id to service),
        )
        val manager = TaskManager()

        val created = manager.create(
            config,
            listOf(repositoryInfo),
            CreateTaskRequest("OBT/123 支付", "feature/OBT-123", listOf(repositoryInfo.id)),
        )

        assertEquals(WorkspaceStatus.READY, created.status)
        val worktree = Path.of(created.services.single().worktreePath)
        assertTrue(Files.exists(worktree.resolve("local.conf")))
        val ideaProject = taskRoot.resolve(created.taskDirectoryName).resolve("idea-${created.taskDirectoryName}")
        assertTrue(Files.exists(ideaProject.resolve("pom.xml")))
        assertEquals(
            "TaskWT - OBT/123 支付 - IDEA",
            Files.readString(ideaProject.resolve(".idea").resolve(".name")),
        )

        val archived = manager.archive(taskRoot.resolve(created.taskDirectoryName))
        assertEquals(WorkspaceStatus.ARCHIVED, archived.status)
        assertFalse(Files.exists(worktree))
        assertTrue(GitClient().refExists(repository, "refs/heads/feature/OBT-123"))

        val restored = manager.restore(config, taskRoot.resolve(created.taskDirectoryName))
        assertEquals(WorkspaceStatus.READY, restored.status)
        assertTrue(Files.exists(worktree))

        Files.writeString(worktree.resolve("uncommitted.txt"), "dirty")
        assertThrows(IllegalStateException::class.java) {
            manager.archive(taskRoot.resolve(created.taskDirectoryName))
        }
        assertTrue(Files.exists(worktree))
    }

    @Test
    fun `addServices appends worktree and updates idea pom`() {
        val servicesRoot = temporary.resolve("services")
        cloneNamedService(servicesRoot, "alpha-service")
        cloneNamedService(servicesRoot, "beta-service")
        val repositories = RepositoryScanner().scan(listOf(servicesRoot), null)
        val firstInfo = repositories.first { it.name == "alpha-service" }
        val secondInfo = repositories.first { it.name == "beta-service" }
        val taskRoot = temporary.resolve("tasks")
        val config = AppConfig(
            scanRoots = listOf(servicesRoot.toString()),
            taskRoot = taskRoot.toString(),
            services = mapOf(
                firstInfo.id to ServiceConfig(repositoryId = firstInfo.id, displayName = firstInfo.name),
                secondInfo.id to ServiceConfig(repositoryId = secondInfo.id, displayName = secondInfo.name),
            ),
        )
        val manager = TaskManager()
        val created = manager.create(
            config,
            repositories,
            CreateTaskRequest("OBT-add", "feature/OBT-add", listOf(firstInfo.id)),
        )
        assertEquals(1, created.services.size)

        val added = manager.addServices(
            config,
            repositories,
            taskRoot.resolve(created.taskDirectoryName),
            AddServicesRequest(listOf(secondInfo.id)),
        )
        assertEquals(2, added.services.size)
        assertTrue(added.services.all { it.status == WorkspaceStatus.READY })
        assertTrue(Files.isDirectory(Path.of(added.services.first { it.repositoryId == secondInfo.id }.worktreePath)))

        val pom = Files.readString(
            taskRoot.resolve(created.taskDirectoryName)
                .resolve("idea-${created.taskDirectoryName}")
                .resolve("pom.xml"),
        )
        assertTrue(pom.contains("alpha-service"))
        assertTrue(pom.contains("beta-service"))

        assertThrows(IllegalArgumentException::class.java) {
            manager.addServices(
                config,
                repositories,
                taskRoot.resolve(created.taskDirectoryName),
                AddServicesRequest(listOf(secondInfo.id)),
            )
        }

        manager.archive(taskRoot.resolve(created.taskDirectoryName))
        val archivedError = assertThrows(IllegalArgumentException::class.java) {
            manager.addServices(
                config,
                repositories,
                taskRoot.resolve(created.taskDirectoryName),
                AddServicesRequest(listOf(secondInfo.id)),
            )
        }
        assertTrue(archivedError.message!!.contains("归档"))
    }

    @Test
    fun `initialize does not revive FAILED when worktree missing`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("source"))
        val repository = GitTestSupport.clone(remote, temporary.resolve("services").resolve("svc"))
        val repositoryInfo = RepositoryScanner().scan(listOf(repository.parent), null).single()
        val taskRoot = temporary.resolve("tasks")
        val config = AppConfig(
            scanRoots = listOf(repository.parent.toString()),
            taskRoot = taskRoot.toString(),
            services = mapOf(
                repositoryInfo.id to ServiceConfig(
                    repositoryId = repositoryInfo.id,
                    displayName = repositoryInfo.name,
                    bootstrap = BootstrapPresets.empty(),
                ),
            ),
        )
        val manager = TaskManager()
        val created = manager.create(
            config,
            listOf(repositoryInfo),
            CreateTaskRequest("OBT-fail", "feature/OBT-fail", listOf(repositoryInfo.id)),
        )
        val taskDirectory = taskRoot.resolve(created.taskDirectoryName)
        val worktree = Path.of(created.services.single().worktreePath)
        GitClient().removeWorktree(repository, worktree, force = true)
        assertFalse(Files.exists(worktree))

        val store = ManifestStore()
        val failed = created.copy(
            status = WorkspaceStatus.FAILED,
            services = created.services.map {
                it.copy(status = WorkspaceStatus.FAILED, warnings = listOf("checkout failed"))
            },
        )
        store.save(taskDirectory, failed)

        val afterInit = manager.initialize(config, taskDirectory, failedOnly = false)
        assertEquals(WorkspaceStatus.FAILED, afterInit.status)
        assertEquals(WorkspaceStatus.FAILED, afterInit.services.single().status)
        assertFalse(Files.exists(worktree))

        // Residual directory that is not a registered worktree must not become READY.
        Files.createDirectories(worktree)
        Files.writeString(worktree.resolve("junk.txt"), "leftover")
        val afterResidual = manager.initialize(config, taskDirectory, failedOnly = false)
        assertEquals(WorkspaceStatus.FAILED, afterResidual.status)
        assertEquals(WorkspaceStatus.FAILED, afterResidual.services.single().status)
        assertEquals(listOf("checkout failed"), afterResidual.services.single().warnings)
    }

    @Test
    fun `addServices succeeds when feature branch already exists on target repo`() {
        val servicesRoot = temporary.resolve("services-existing-branch")
        cloneNamedService(servicesRoot, "alpha-svc")
        cloneNamedService(servicesRoot, "beta-svc")
        val repositories = RepositoryScanner().scan(listOf(servicesRoot), null)
        val firstInfo = repositories.first { it.name == "alpha-svc" }
        val secondInfo = repositories.first { it.name == "beta-svc" }
        val betaRepo = Path.of(secondInfo.rootPath)
        val branch = "feature/OBT-shared"
        GitTestSupport.run(betaRepo, "checkout", "-b", branch)
        GitTestSupport.run(betaRepo, "push", "-u", "origin", branch)
        GitTestSupport.run(betaRepo, "checkout", "master")

        val taskRoot = temporary.resolve("tasks-existing-branch")
        val config = AppConfig(
            scanRoots = listOf(servicesRoot.toString()),
            taskRoot = taskRoot.toString(),
            services = mapOf(
                firstInfo.id to ServiceConfig(repositoryId = firstInfo.id, displayName = firstInfo.name),
                secondInfo.id to ServiceConfig(repositoryId = secondInfo.id, displayName = secondInfo.name),
            ),
        )
        val manager = TaskManager()
        val created = manager.create(
            config,
            repositories,
            CreateTaskRequest("OBT-shared", branch, listOf(firstInfo.id)),
        )
        val added = manager.addServices(
            config,
            repositories,
            taskRoot.resolve(created.taskDirectoryName),
            AddServicesRequest(listOf(secondInfo.id)),
        )
        assertEquals(2, added.services.size)
        val beta = added.services.single { it.repositoryId == secondInfo.id }
        assertEquals(WorkspaceStatus.READY, beta.status)
        assertTrue(Files.isDirectory(Path.of(beta.worktreePath)))
        assertTrue(GitClient().refExists(betaRepo, "refs/heads/$branch"))
    }

    @Test
    fun `retryFailedServices restores missing worktree when branch already exists`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("source"))
        val repository = GitTestSupport.clone(remote, temporary.resolve("services").resolve("retry-svc"))
        val repositoryInfo = RepositoryScanner().scan(listOf(repository.parent), null).single()
        val taskRoot = temporary.resolve("tasks")
        val config = AppConfig(
            scanRoots = listOf(repository.parent.toString()),
            taskRoot = taskRoot.toString(),
            services = mapOf(
                repositoryInfo.id to ServiceConfig(
                    repositoryId = repositoryInfo.id,
                    displayName = repositoryInfo.name,
                ),
            ),
        )
        val manager = TaskManager()
        val created = manager.create(
            config,
            listOf(repositoryInfo),
            CreateTaskRequest("OBT-retry", "feature/OBT-retry", listOf(repositoryInfo.id)),
        )
        val taskDirectory = taskRoot.resolve(created.taskDirectoryName)
        val worktree = Path.of(created.services.single().worktreePath)
        GitClient().removeWorktree(repository, worktree, force = true)
        assertTrue(GitClient().refExists(repository, "refs/heads/feature/OBT-retry"))

        ManifestStore().save(
            taskDirectory,
            created.copy(
                status = WorkspaceStatus.FAILED,
                services = created.services.map {
                    it.copy(status = WorkspaceStatus.FAILED, warnings = listOf("checkout failed"))
                },
            ),
        )

        val retried = manager.retryFailedServices(config, taskDirectory)
        assertEquals(WorkspaceStatus.READY, retried.status)
        assertEquals(WorkspaceStatus.READY, retried.services.single().status)
        assertTrue(Files.isDirectory(Path.of(retried.services.single().worktreePath)))
    }

    @Test
    fun `retryFailedServices creates branch when missing`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("source"))
        val repository = GitTestSupport.clone(remote, temporary.resolve("services").resolve("retry-new"))
        val repositoryInfo = RepositoryScanner().scan(listOf(repository.parent), null).single()
        val taskRoot = temporary.resolve("tasks")
        val config = AppConfig(
            scanRoots = listOf(repository.parent.toString()),
            taskRoot = taskRoot.toString(),
            services = mapOf(
                repositoryInfo.id to ServiceConfig(
                    repositoryId = repositoryInfo.id,
                    displayName = repositoryInfo.name,
                ),
            ),
        )
        val manager = TaskManager()
        val created = manager.create(
            config,
            listOf(repositoryInfo),
            CreateTaskRequest("OBT-retry2", "feature/OBT-retry2", listOf(repositoryInfo.id)),
        )
        val taskDirectory = taskRoot.resolve(created.taskDirectoryName)
        val worktree = Path.of(created.services.single().worktreePath)
        GitClient().removeWorktree(repository, worktree, force = true)
        GitTestSupport.run(repository, "branch", "-D", "feature/OBT-retry2")
        assertFalse(GitClient().refExists(repository, "refs/heads/feature/OBT-retry2"))

        ManifestStore().save(
            taskDirectory,
            created.copy(
                status = WorkspaceStatus.FAILED,
                services = created.services.map {
                    it.copy(status = WorkspaceStatus.FAILED, warnings = listOf("checkout failed"))
                },
            ),
        )

        val retried = manager.retryFailedServices(config, taskDirectory)
        assertEquals(WorkspaceStatus.READY, retried.status)
        assertTrue(Files.isDirectory(Path.of(retried.services.single().worktreePath)))
        assertTrue(GitClient().refExists(repository, "refs/heads/feature/OBT-retry2"))
    }

    @Test
    fun `delete removes worktree and task directory but keeps feature branch`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("source-delete"))
        val repository = GitTestSupport.clone(remote, temporary.resolve("services").resolve("delete-svc"))
        val repositoryInfo = RepositoryScanner().scan(listOf(repository.parent), null).single()
        val taskRoot = temporary.resolve("tasks-delete")
        val config = AppConfig(
            scanRoots = listOf(repository.parent.toString()),
            taskRoot = taskRoot.toString(),
            services = mapOf(
                repositoryInfo.id to ServiceConfig(
                    repositoryId = repositoryInfo.id,
                    displayName = repositoryInfo.name,
                ),
            ),
        )
        val manager = TaskManager()
        val created = manager.create(
            config,
            listOf(repositoryInfo),
            CreateTaskRequest("OBT-del", "feature/OBT-del", listOf(repositoryInfo.id)),
        )
        val taskDirectory = taskRoot.resolve(created.taskDirectoryName)
        val worktree = Path.of(created.services.single().worktreePath)
        assertTrue(Files.isDirectory(worktree))
        assertTrue(Files.isDirectory(taskDirectory))

        manager.delete(taskDirectory)

        assertFalse(Files.exists(worktree))
        assertFalse(Files.exists(taskDirectory))
        assertTrue(GitClient().refExists(repository, "refs/heads/feature/OBT-del"))
    }

    @Test
    fun `delete requires forceDiscard when worktree has uncommitted files`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("source-dirty"))
        val repository = GitTestSupport.clone(remote, temporary.resolve("services").resolve("dirty-svc"))
        val repositoryInfo = RepositoryScanner().scan(listOf(repository.parent), null).single()
        val taskRoot = temporary.resolve("tasks-dirty")
        val config = AppConfig(
            scanRoots = listOf(repository.parent.toString()),
            taskRoot = taskRoot.toString(),
            services = mapOf(
                repositoryInfo.id to ServiceConfig(
                    repositoryId = repositoryInfo.id,
                    displayName = repositoryInfo.name,
                ),
            ),
        )
        val manager = TaskManager()
        val created = manager.create(
            config,
            listOf(repositoryInfo),
            CreateTaskRequest("OBT-dirty", "feature/OBT-dirty", listOf(repositoryInfo.id)),
        )
        val taskDirectory = taskRoot.resolve(created.taskDirectoryName)
        val worktree = Path.of(created.services.single().worktreePath)
        Files.writeString(worktree.resolve("uncommitted.txt"), "dirty")

        val risks = manager.inspectDeleteRisk(taskDirectory)
        assertEquals(1, risks.size)
        assertTrue(risks.single().untracked)

        val error = assertThrows(IllegalStateException::class.java) {
            manager.delete(taskDirectory)
        }
        assertTrue(error.message!!.contains("未提交"))
        assertTrue(Files.exists(worktree))
        assertTrue(Files.exists(taskDirectory))

        manager.delete(taskDirectory, forceDiscard = true)
        assertFalse(Files.exists(worktree))
        assertFalse(Files.exists(taskDirectory))
        assertTrue(GitClient().refExists(repository, "refs/heads/feature/OBT-dirty"))
    }

    @Test
    fun `delete allows unpushed commits without force while archive does not`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("source-unpushed"))
        val repository = GitTestSupport.clone(remote, temporary.resolve("services").resolve("unpushed-svc"))
        val repositoryInfo = RepositoryScanner().scan(listOf(repository.parent), null).single()
        val taskRoot = temporary.resolve("tasks-unpushed")
        val config = AppConfig(
            scanRoots = listOf(repository.parent.toString()),
            taskRoot = taskRoot.toString(),
            services = mapOf(
                repositoryInfo.id to ServiceConfig(
                    repositoryId = repositoryInfo.id,
                    displayName = repositoryInfo.name,
                ),
            ),
        )
        val manager = TaskManager()
        val created = manager.create(
            config,
            listOf(repositoryInfo),
            CreateTaskRequest("OBT-unpushed", "feature/OBT-unpushed", listOf(repositoryInfo.id)),
        )
        val taskDirectory = taskRoot.resolve(created.taskDirectoryName)
        val worktree = Path.of(created.services.single().worktreePath)
        Files.writeString(worktree.resolve("local-only.txt"), "commit me")
        GitTestSupport.run(worktree, "add", "local-only.txt")
        GitTestSupport.run(worktree, "commit", "-m", "local only commit")

        val status = GitClient().status(worktree)
        assertTrue(status.unpushedCommits > 0)
        assertFalse(status.hasUncommittedChanges)
        assertFalse(status.safeToArchive)
        assertTrue(manager.inspectDeleteRisk(taskDirectory).isEmpty())

        assertThrows(IllegalStateException::class.java) {
            manager.archive(taskDirectory)
        }
        assertTrue(Files.exists(worktree))

        manager.delete(taskDirectory)
        assertFalse(Files.exists(worktree))
        assertFalse(Files.exists(taskDirectory))
        assertTrue(GitClient().refExists(repository, "refs/heads/feature/OBT-unpushed"))
    }

    private fun cloneNamedService(servicesRoot: Path, name: String): Path {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("remotes").resolve(name))
        return GitTestSupport.clone(remote, servicesRoot.resolve(name))
    }
}
