package com.snowball.taskwt.desktop

import com.snowball.taskwt.core.AppConfig
import com.snowball.taskwt.core.ApplicationPaths
import com.snowball.taskwt.core.ConfigStore
import com.snowball.taskwt.core.ManifestStore
import com.snowball.taskwt.core.RepositoryConfig
import com.snowball.taskwt.core.RepositoryInspector
import com.snowball.taskwt.core.RemoteBranchCatalog
import com.snowball.taskwt.core.GroupServiceConfig
import com.snowball.taskwt.core.ServiceGroupConfig
import com.snowball.taskwt.core.WorkspaceStrategy
import com.snowball.taskwt.core.RequirementInfoClient
import com.snowball.taskwt.core.TaskManifest
import com.snowball.taskwt.core.TaskWorkspaceContext
import com.snowball.taskwt.core.TaskWorkspaceToolAvailability
import com.snowball.taskwt.core.TaskWorkspaceToolDescriptor
import com.snowball.taskwt.core.TaskWorkspaceToolLauncher
import com.snowball.taskwt.core.TaskWorkspaceToolRegistry
import com.snowball.taskwt.core.WorkspaceStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertIs

class AppControllerTest {
    @Test
    fun `agents preview includes the current requirement link`() {
        val root = Files.createTempDirectory("taskwt-preview-link")
        val paths = ApplicationPaths(root.resolve("home"))
        val store = ConfigStore(paths)
        store.save(
            AppConfig(
                taskRoot = root.resolve("tasks").toString(),
                groups = listOf(ServiceGroupConfig("g", "G")),
            ),
        )
        val controller = AppController(paths = paths, configStore = store)
        try {
            val preview = controller.previewAgents(
                folderName = "TASK-1",
                branch = "feature/task-1",
                groupId = "g",
                serviceIds = emptySet(),
                cloneOverrides = emptyMap(),
                requirementLink = "REQ-123 raw requirement",
                notes = "notes",
            )

            assertContains(preview, "REQ-123 raw requirement")
        } finally {
            controller.close()
        }
    }

    @Test
    fun `desktop tool options come entirely from injected registry and preserve unknown ids`() {
        val root = Files.createTempDirectory("taskwt-tools")
        val paths = ApplicationPaths(root.resolve("home"))
        val store = ConfigStore(paths)
        store.save(
            AppConfig(
                groups = listOf(
                    ServiceGroupConfig(
                        id = "g",
                        name = "G",
                        defaultWorkspaceToolIds = listOf("claude", "legacy-tool"),
                    ),
                ),
            ),
        )
        val registry = TaskWorkspaceToolRegistry(listOf(tool("claude"), tool("cursor")))
        val controller = AppController(paths = paths, configStore = store, workspaceToolRegistry = registry)
        try {
            val options = controller.workspaceToolOptions("g").associateBy(WorkspaceToolOption::id)

            assertEquals(true, options.getValue("claude").available)
            assertEquals(true, options.getValue("cursor").available)
            assertEquals(false, options.getValue("legacy-tool").available)
        } finally {
            controller.close()
        }
    }

    @Test
    fun `startup reads persisted arrays and selects newest task without external refresh`() {
        val root = Files.createTempDirectory("taskwt-desktop-startup")
        val paths = ApplicationPaths(root.resolve("home"))
        val taskRoot = root.resolve("tasks")
        val configStore = ConfigStore(paths)
        val manifests = ManifestStore()
        configStore.save(
            AppConfig(
                taskRoot = taskRoot.toString(),
                repositories = listOf(
                    RepositoryConfig("repo-1", "service", root.resolve("missing").toString(), "missing"),
                ),
            ),
        )
        manifests.save(taskRoot.resolve("older"), task("older", "2026-08-01T00:00:00Z"))
        manifests.save(taskRoot.resolve("newer"), task("newer", "2026-08-02T00:00:00Z"))
        var repositoryInspections = 0
        var remoteRequests = 0
        val controller = AppController(
            paths = paths,
            configStore = configStore,
            manifests = manifests,
            repositoryInspector = RepositoryInspector {
                repositoryInspections++
                error("startup must not inspect repositories")
            },
            requirementInfoClient = RequirementInfoClient {
                remoteRequests++
                error("startup must not call Meegle")
            },
        )

        assertEquals(NavigationItem.TASKS, controller.navigation)
        assertEquals(listOf("newer", "older"), controller.tasks.map { it.folderName })
        assertEquals("newer", controller.selectedTask?.folderName)
        assertEquals(0, repositoryInspections)
        assertEquals(0, remoteRequests)
        controller.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `remote branch loading exposes loading success and failure without startup request`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("taskwt-branches")
        val paths = ApplicationPaths(root.resolve("home"))
        val store = ConfigStore(paths)
        val repository = RepositoryConfig("repo", "repo", root.resolve("repo").toString(), root.resolve("repo/.git").toString(), "https://example.test/repo.git")
        val service = GroupServiceConfig(
            id = "clone",
            repositoryId = repository.id,
            displayName = "Clone",
            strategy = WorkspaceStrategy.INDEPENDENT_CLONE,
            modules = emptyList(),
            cloneDefaultBranch = "origin/main",
        )
        store.save(AppConfig(repositories = listOf(repository), groups = listOf(ServiceGroupConfig("g", "G", services = listOf(service)))))
        var requests = 0
        var shouldFail = false
        val controller = AppController(
            paths = paths,
            configStore = store,
            remoteBranchCatalog = object : RemoteBranchCatalog {
                override fun list(repository: Path, remote: String): List<String> {
                    requests++
                    if (shouldFail) error("offline")
                    return listOf("origin/main", "origin/release/test")
                }
            },
            ioDispatcher = dispatcher,
        )
        try {
            assertEquals(0, requests)
            controller.loadRemoteBranches("repo")
            assertIs<RemoteBranchesState.Loading>(controller.remoteBranches["repo"])
            advanceUntilIdle()
            assertEquals(listOf("origin/main", "origin/release/test"), assertIs<RemoteBranchesState.Loaded>(controller.remoteBranches["repo"]).branches)
            shouldFail = true
            controller.loadRemoteBranches("repo", force = true)
            assertIs<RemoteBranchesState.Loading>(controller.remoteBranches["repo"])
            advanceUntilIdle()
            assertEquals("offline", assertIs<RemoteBranchesState.Failed>(controller.remoteBranches["repo"]).message)
        } finally {
            controller.close()
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `closing the last effective Tag gate returns UAT page to tasks`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("taskwt-uat-navigation")
        val paths = ApplicationPaths(root.resolve("home"))
        val store = ConfigStore(paths)
        val repository = RepositoryConfig("repo", "repo", root.resolve("repo").toString(), root.resolve("repo/.git").toString())
        store.save(AppConfig(repositories = listOf(repository), groups = listOf(
            ServiceGroupConfig("g", "G", services = listOf(GroupServiceConfig.standard("service", "repo", "Service"))),
        )))
        val controller = AppController(paths = paths, configStore = store, ioDispatcher = dispatcher)
        try {
            controller.navigation = NavigationItem.UAT
            controller.setGroupTagEnabled("g", false)
            advanceUntilIdle()

            assertEquals(false, controller.showsUatNavigation)
            assertEquals(NavigationItem.TASKS, controller.navigation)
        } finally {
            controller.close()
            Dispatchers.resetMain()
        }
    }

    private fun task(name: String, updatedAt: String) = TaskManifest(
        folderName = name,
        taskDirectoryName = name,
        featureBranch = "feature/$name",
        createdAt = updatedAt,
        updatedAt = updatedAt,
        status = WorkspaceStatus.READY,
        services = emptyList(),
    )

    private fun tool(id: String) = object : TaskWorkspaceToolLauncher {
        override val descriptor = TaskWorkspaceToolDescriptor(id, id)
        override fun availability(): TaskWorkspaceToolAvailability = TaskWorkspaceToolAvailability.Available
        override fun open(context: TaskWorkspaceContext) = Unit
    }
}
