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

    private fun cloneNamedService(servicesRoot: Path, name: String): Path {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("remotes").resolve(name))
        return GitTestSupport.clone(remote, servicesRoot.resolve(name))
    }
}
