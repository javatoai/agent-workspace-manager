package com.snowball.awm.desktop

import com.snowball.awm.core.AppConfig
import com.snowball.awm.core.ApplicationPaths
import com.snowball.awm.core.ConfigStore
import com.snowball.awm.core.ManifestStore
import com.snowball.awm.core.MeegleCommandSource
import com.snowball.awm.core.RepositoryConfig
import com.snowball.awm.core.RepositoryInspector
import com.snowball.awm.core.RemoteBranchCatalog
import com.snowball.awm.core.GroupServiceConfig
import com.snowball.awm.core.GroupConfig
import com.snowball.awm.core.ServiceModuleConfig
import com.snowball.awm.core.WorkspaceStrategy
import com.snowball.awm.core.RequirementMetadataProvider
import com.snowball.awm.core.TaskManifest
import com.snowball.awm.core.TaskWorkspaceContext
import com.snowball.awm.core.TaskWorkspaceToolAvailability
import com.snowball.awm.core.TaskWorkspaceToolDescriptor
import com.snowball.awm.core.TaskWorkspaceToolLauncher
import com.snowball.awm.core.TaskWorkspaceToolRegistry
import com.snowball.awm.core.WorkspaceHealth
import com.snowball.awm.core.TaskLifecycleStatus
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
import kotlin.test.assertNotNull

class DesktopApplicationTest {
    @Test
    fun `configured Meegle executable is available after application initialization`() {
        val root = Files.createTempDirectory("awm-meegle-executable")
        val paths = ApplicationPaths(root.resolve("home"))
        val executable = Files.createFile(root.resolve("meegle.cmd")).toAbsolutePath().toString()
        val store = ConfigStore(paths)
        store.save(AppConfig(meegleExecutablePath = executable))

        val controller = DesktopApplication(paths = paths, configStore = store)
        try {
            val resolution = controller.meegleCommandResolution()
            assertEquals(executable, resolution.first)
            assertEquals(MeegleCommandSource.CONFIGURED, resolution.second)
        } finally {
            controller.close()
        }
    }

    @Test
    fun `invalid disk configuration remains visible and is never overwritten`() {
        val root = Files.createTempDirectory("awm-invalid-config")
        val paths = ApplicationPaths(root.resolve("home"))
        Files.createDirectories(paths.home)
        val original = "{ invalid json"
        Files.writeString(paths.config, original)

        val controller = DesktopApplication(paths = paths, configStore = ConfigStore(paths))
        try {
            assertNotNull(controller.configurationLoadError)
            assertEquals(original, Files.readString(paths.config))
        } finally {
            controller.close()
        }
    }

    @Test
    fun `agents preview includes the current requirement link`() {
        val root = Files.createTempDirectory("awm-preview-link")
        val paths = ApplicationPaths(root.resolve("home"))
        val store = ConfigStore(paths)
        store.save(
            AppConfig(
                taskRoot = root.resolve("tasks").toString(),
                repositories = listOf(
                    RepositoryConfig("repo", "Service", root.resolve("service").toString(), root.resolve("service/.git").toString()),
                ),
                groups = listOf(
                    GroupConfig("g", "G", services = listOf(GroupServiceConfig.standard("service", "repo", "Service"))),
                ),
            ),
        )
        val controller = DesktopApplication(paths = paths, configStore = store)
        try {
            val preview = controller.previewAgents(
                folderName = "支付 订单优化",
                branch = "feature/task-1",
                groupId = "g",
                serviceIds = setOf("service"),
                requirementLink = "REQ-123 raw requirement",
                notes = "notes",
            )

            assertContains(preview, "REQ-123 raw requirement")
            assertContains(preview, root.resolve("tasks").resolve("支付 订单优化").toString())
        } finally {
            controller.close()
        }
    }

    @Test
    fun `desktop tool options come entirely from injected registry and preserve unknown ids`() {
        val root = Files.createTempDirectory("awm-tools")
        val paths = ApplicationPaths(root.resolve("home"))
        val store = ConfigStore(paths)
        store.save(
            AppConfig(
                groups = listOf(
                    GroupConfig(
                        id = "g",
                        name = "G",
                        defaultWorkspaceToolIds = listOf("claude", "legacy-tool"),
                    ),
                ),
            ),
        )
        val registry = TaskWorkspaceToolRegistry(listOf(tool("claude"), tool("cursor")))
        val controller = DesktopApplication(paths = paths, configStore = store, workspaceToolRegistry = registry)
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
        val root = Files.createTempDirectory("awm-desktop-startup")
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
        val controller = DesktopApplication(
            paths = paths,
            configStore = configStore,
            manifests = manifests,
            repositoryInspector = RepositoryInspector {
                repositoryInspections++
                error("startup must not inspect repositories")
            },
            requirementMetadataProvider = RequirementMetadataProvider {
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
        val root = Files.createTempDirectory("awm-branches")
        val paths = ApplicationPaths(root.resolve("home"))
        val store = ConfigStore(paths)
        val repository = RepositoryConfig("repo", "repo", root.resolve("repo").toString(), root.resolve("repo/.git").toString(), "https://example.test/repo.git")
        val service = GroupServiceConfig(
            id = "clone",
            repositoryId = repository.id,
            displayName = "Clone",
            modules = listOf(
                ServiceModuleConfig(
                    id = "clone",
                    strategy = WorkspaceStrategy.INDEPENDENT_CLONE,
                    baseRef = "origin/main",
                    tagEnabled = false,
                ),
            ),
        )
        store.save(AppConfig(repositories = listOf(repository), groups = listOf(GroupConfig("g", "G", services = listOf(service)))))
        var requests = 0
        var shouldFail = false
        val controller = DesktopApplication(
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
            assertIs<RemoteBranchesState.Loading>(controller.remoteBranchState("repo", "origin"))
            advanceUntilIdle()
            assertEquals(listOf("origin/main", "origin/release/test"), assertIs<RemoteBranchesState.Loaded>(controller.remoteBranchState("repo", "origin")).branches)
            shouldFail = true
            controller.loadRemoteBranches("repo", force = true)
            assertEquals(
                listOf("origin/main", "origin/release/test"),
                assertIs<RemoteBranchesState.Loading>(controller.remoteBranchState("repo", "origin")).staleBranches,
            )
            advanceUntilIdle()
            val failed = assertIs<RemoteBranchesState.Failed>(controller.remoteBranchState("repo", "origin"))
            assertEquals("offline", failed.message)
            assertEquals(listOf("origin/main", "origin/release/test"), failed.staleBranches)
        } finally {
            controller.close()
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `closing the last effective Tag gate returns Tag page to tasks`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("awm-tag-navigation")
        val paths = ApplicationPaths(root.resolve("home"))
        val store = ConfigStore(paths)
        val repository = RepositoryConfig("repo", "repo", root.resolve("repo").toString(), root.resolve("repo/.git").toString())
        store.save(AppConfig(repositories = listOf(repository), groups = listOf(
            GroupConfig("g", "G", services = listOf(GroupServiceConfig.standard("service", "repo", "Service"))),
        )))
        val controller = DesktopApplication(paths = paths, configStore = store, ioDispatcher = dispatcher)
        try {
            controller.navigation = NavigationItem.TAG
            controller.setGroupTagEnabled("g", false)
            advanceUntilIdle()

            assertEquals(false, controller.showsTagNavigation)
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
        lifecycleStatus = TaskLifecycleStatus.ACTIVE,
        services = emptyList(),
    )

    private fun tool(id: String) = object : TaskWorkspaceToolLauncher {
        override val descriptor = TaskWorkspaceToolDescriptor(id, id)
        override fun availability(): TaskWorkspaceToolAvailability = TaskWorkspaceToolAvailability.Available
        override fun open(context: TaskWorkspaceContext) = Unit
    }
}
