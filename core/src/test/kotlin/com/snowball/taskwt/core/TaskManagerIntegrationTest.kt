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
}
