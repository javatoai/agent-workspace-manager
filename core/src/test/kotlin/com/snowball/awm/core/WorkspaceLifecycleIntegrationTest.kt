package com.snowball.awm.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class WorkspaceLifecycleIntegrationTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `same-base modules keep one physical worktree through archive restore and delete`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("shared"))
        val repositoryPath = GitTestSupport.clone(remote, temporary.resolve("shared").resolve("source"))
        val repository = GitRepositoryInspector().inspect(repositoryPath)
        val serviceConfig = GroupServiceConfig(
            id = "shared-service",
            repositoryId = repository.id,
            displayName = "shared",
            modules = listOf(
                ServiceModuleConfig("api", "API", "origin/master"),
                ServiceModuleConfig("web", "Web", "refs/remotes/origin/master"),
            ),
        )
        val taskRoot = temporary.resolve("tasks")
        val config = AppConfig(
            taskRoot = taskRoot.toString(),
            repositories = listOf(repository),
            groups = listOf(GroupConfig("shared", "Shared", services = listOf(serviceConfig))),
        )
        val application = TaskApplicationService(
            agentDocuments = AgentDocumentService(ApplicationPaths(temporary.resolve("home"))),
            operationLock = NoOpTaskOperationLock,
        )

        val created = application.create(
            config,
            CreateGroupedTaskRequest("T-1", "feature/T-1", "shared", listOf("shared-service")),
        )
        assertEquals(2, created.services.size)
        assertEquals(1, created.services.map { it.worktreePath }.distinct().size)
        assertEquals(2, GitClient().worktrees(repositoryPath).size)

        val taskDirectory = taskRoot.resolve(created.taskDirectoryName)
        val archived = application.archive(config, taskDirectory)
        assertEquals(WorkspaceStatus.ARCHIVED, archived.status)
        assertTrue(Files.exists(Path.of(created.services.first().worktreePath)))
        assertEquals(2, GitClient().worktrees(repositoryPath).size)

        val restored = application.restore(config, taskDirectory)
        assertTrue(restored.services.all { it.status == WorkspaceStatus.READY })
        assertEquals(2, GitClient().worktrees(repositoryPath).size)

        application.delete(config, taskDirectory)
        assertFalse(Files.exists(taskDirectory))
        assertEquals(1, GitClient().worktrees(repositoryPath).size)
    }

    @Test
    fun `independent clone requires explicit discard for commits on any local branch`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("clone-risk"))
        val source = GitTestSupport.clone(remote, temporary.resolve("clone-risk").resolve("source"))
        val repository = GitRepositoryInspector().inspect(source)
        val cloneService = GroupServiceConfig(
            id = "clone-risk",
            repositoryId = repository.id,
            displayName = "clone-risk",
            strategy = WorkspaceStrategy.INDEPENDENT_CLONE,
            modules = emptyList(),
            cloneModules = listOf(IndependentCloneModuleConfig("clone", branch = "origin/master")),
        )
        val taskRoot = temporary.resolve("clone-risk-tasks")
        val config = AppConfig(
            taskRoot = taskRoot.toString(),
            repositories = listOf(repository),
            groups = listOf(GroupConfig("clone-risk", "Clone Risk", services = listOf(cloneService))),
        )
        val application = TaskApplicationService(
            agentDocuments = AgentDocumentService(ApplicationPaths(temporary.resolve("clone-risk-home"))),
            operationLock = NoOpTaskOperationLock,
        )
        val created = application.create(
            config,
            CreateGroupedTaskRequest("CLONE-1", "unused", "clone-risk", listOf("clone-risk")),
        )
        val taskDirectory = taskRoot.resolve(created.taskDirectoryName)
        val clone = Path.of(created.services.single().worktreePath)
        GitTestSupport.configureIdentity(clone)
        GitTestSupport.run(clone, "switch", "-c", "local-only")
        Files.writeString(clone.resolve("local.txt"), "local only\n")
        GitTestSupport.run(clone, "add", "local.txt")
        GitTestSupport.run(clone, "commit", "-m", "local only")

        val risks = application.inspectDeleteRisk(config, taskDirectory)
        assertTrue(risks.single().unpushedCommits > 0)
        assertFailsWith<IllegalStateException> { application.delete(config, taskDirectory) }
        assertTrue(Files.exists(clone))

        application.delete(config, taskDirectory, forceDiscard = true)
        assertFalse(Files.exists(taskDirectory))
    }
}
