package com.snowball.awm.desktop

import com.snowball.awm.core.AppConfig
import com.snowball.awm.core.ApplicationPaths
import com.snowball.awm.core.ConfigStore
import com.snowball.awm.core.CommandResult
import com.snowball.awm.core.CommandRunner
import com.snowball.awm.core.ConfiguredGitExecutable
import com.snowball.awm.core.ConfiguredMeegleExecutable
import com.snowball.awm.core.GitCommandSource
import com.snowball.awm.core.LocalGitEnvironmentInspector
import com.snowball.awm.core.ManifestStore
import com.snowball.awm.core.MeegleCliService
import com.snowball.awm.core.MeegleCliStatus
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
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
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
import kotlin.test.assertTrue
import kotlin.test.assertFalse

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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `first Meegle status check detects persists and then reuses the executable path`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("awm-meegle-auto-save")
        val paths = ApplicationPaths(root.resolve("home"))
        val detected = Files.createFile(root.resolve(if (System.getProperty("os.name").startsWith("Windows")) "meegle.cmd" else "meegle"))
            .toAbsolutePath()
        detected.toFile().setExecutable(true)
        val store = ConfigStore(paths)
        store.save(AppConfig())
        val configuredPath = AtomicReference<String?>(null)
        val runner = RecordingCommandRunner(CommandResult(0, "$detected\n", ""))
        val executable = ConfiguredMeegleExecutable(configuredPath::get, runner)
        val cli = RecordingMeegleCliService()
        val controller = DesktopApplication(
            paths = paths,
            configStore = store,
            meegleExecutablePath = configuredPath,
            meegleExecutable = executable,
            meegleCliService = cli,
            ioDispatcher = dispatcher,
        )
        try {
            controller.refreshMeegleStatus()
            advanceUntilIdle()

            assertEquals(detected.toString(), store.load().meegleExecutablePath)
            assertEquals(detected.toString(), controller.config.meegleExecutablePath)
            assertEquals(MeegleCommandSource.CONFIGURED, controller.meegleCommandResolution().second)
            assertEquals(1, runner.calls)
            assertEquals(1, cli.statusCalls)

            controller.refreshMeegleStatus(force = true)
            advanceUntilIdle()

            assertEquals(1, runner.calls)
            assertEquals(2, cli.statusCalls)
        } finally {
            controller.close()
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `failed automatic Meegle path save is surfaced without changing invalid configuration`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("awm-meegle-auto-save-failure")
        val paths = ApplicationPaths(root.resolve("home"))
        Files.createDirectories(paths.home)
        val original = "{ invalid json"
        Files.writeString(paths.config, original)
        val detected = Files.createFile(root.resolve(if (System.getProperty("os.name").startsWith("Windows")) "meegle.cmd" else "meegle"))
            .toAbsolutePath()
        detected.toFile().setExecutable(true)
        val configuredPath = AtomicReference<String?>(null)
        val runner = RecordingCommandRunner(CommandResult(0, "$detected\n", ""))
        val cli = RecordingMeegleCliService()
        val controller = DesktopApplication(
            paths = paths,
            configStore = ConfigStore(paths),
            meegleExecutablePath = configuredPath,
            meegleExecutable = ConfiguredMeegleExecutable(configuredPath::get, runner),
            meegleCliService = cli,
            ioDispatcher = dispatcher,
        )
        try {
            controller.refreshMeegleStatus()
            advanceUntilIdle()

            assertIs<MeegleCliState.Failed>(controller.meegleCliState)
            assertEquals(null, controller.config.meegleExecutablePath)
            assertEquals(original, Files.readString(paths.config))
            assertEquals(1, runner.calls)
            assertEquals(0, cli.statusCalls)
        } finally {
            controller.close()
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `first Git refresh detects persists and then reuses the executable path`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("awm-git-auto-save")
        val paths = ApplicationPaths(root.resolve("home"))
        val detected = Files.createFile(root.resolve(if (System.getProperty("os.name").startsWith("Windows")) "git.exe" else "git"))
            .toAbsolutePath()
        detected.toFile().setExecutable(true)
        val store = ConfigStore(paths)
        store.save(AppConfig())
        val configuredPath = AtomicReference<String?>(null)
        val runner = RecordingGitEnvironmentRunner(detected.toString())
        val executable = ConfiguredGitExecutable(configuredPath::get, runner)
        val controller = DesktopApplication(
            paths = paths,
            configStore = store,
            gitExecutablePath = configuredPath,
            gitExecutable = executable,
            localGitInspector = LocalGitEnvironmentInspector(runner, executable),
            ioDispatcher = dispatcher,
        )
        try {
            controller.refreshLocalGit()
            advanceUntilIdle()

            assertEquals(detected.toString(), store.load().gitExecutablePath)
            assertEquals(detected.toString(), controller.config.gitExecutablePath)
            assertEquals(GitCommandSource.CONFIGURED, controller.gitCommandResolution().second)
            assertEquals(1, runner.probeCalls)

            controller.refreshLocalGit(force = true)
            advanceUntilIdle()

            assertEquals(1, runner.probeCalls)
        } finally {
            controller.close()
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `task root saves under the paths feedback state`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("awm-task-root-save")
        val paths = ApplicationPaths(root.resolve("home"))
        val target = root.resolve("tasks")
        val controller = DesktopApplication(paths = paths, configStore = ConfigStore(paths), ioDispatcher = dispatcher)
        try {
            controller.updateTaskRoot(target.toString())
            assertEquals(SettingsSaveState.SAVING, controller.settingsSaveState("paths"))

            advanceUntilIdle()

            assertEquals(target.toAbsolutePath().normalize().toString(), controller.config.taskRoot)
            assertEquals(SettingsSaveState.SAVED, controller.settingsSaveState("paths"))
            assertEquals(SettingsSaveState.IDLE, controller.settingsSaveState("basic"))
        } finally {
            controller.close()
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `requirement materials settings save independently and normalize values`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("awm-requirement-materials-save")
        val paths = ApplicationPaths(root.resolve("home"))
        val materials = root.resolve("materials")
        val controller = DesktopApplication(paths = paths, configStore = ConfigStore(paths), ioDispatcher = dispatcher)
        try {
            controller.updateRequirementMaterialsRoot(" ${materials} ")
            assertEquals(SettingsSaveState.SAVING, controller.settingsSaveState("requirement-materials-root"))
            advanceUntilIdle()

            controller.updateRequirementMaterialsSubdirectory(" 研发 ")
            assertEquals(SettingsSaveState.SAVING, controller.settingsSaveState("requirement-materials-subdirectory"))
            advanceUntilIdle()

            assertEquals(materials.toAbsolutePath().normalize().toString(), controller.config.requirementMaterialsRoot)
            assertEquals("研发", controller.config.requirementMaterialsSubdirectory)
            assertTrue(Files.isDirectory(materials))
            assertTrue(controller.config.requirementMaterialsConfigured)
            assertEquals(SettingsSaveState.SAVED, controller.settingsSaveState("requirement-materials-root"))
            assertEquals(SettingsSaveState.SAVED, controller.settingsSaveState("requirement-materials-subdirectory"))
        } finally {
            controller.close()
            Dispatchers.resetMain()
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
            assertEquals(paths.config.toAbsolutePath().normalize(), controller.configFileSnapshot.path)
            assertEquals(original, controller.configFileSnapshot.content)
            assertContains(controller.configurationRecoveryGuidance(), paths.config.toAbsolutePath().normalize().toString())
        } finally {
            controller.close()
        }
    }

    @Test
    fun `incompatible configuration remains previewable with recovery guidance`() {
        val root = Files.createTempDirectory("awm-incompatible-config")
        val paths = ApplicationPaths(root.resolve("home"))
        Files.createDirectories(paths.home)
        val original = "{\"schemaVersion\":\"0.8.1\",\"groups\":[]}"
        Files.writeString(paths.config, original)

        val controller = DesktopApplication(paths = paths, configStore = ConfigStore(paths))
        try {
            assertNotNull(controller.configurationLoadError)
            assertContains(controller.configurationLoadError.orEmpty(), "版本不受支持")
            assertEquals(original, controller.configFileSnapshot.content)
            assertContains(controller.configurationRecoveryGuidance(), paths.config.toAbsolutePath().normalize().toString())
            assertEquals(original, Files.readString(paths.config))
        } finally {
            controller.close()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `window focus refreshes the raw configuration preview`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val root = Files.createTempDirectory("awm-config-preview-focus")
        val paths = ApplicationPaths(root.resolve("home"))
        val store = ConfigStore(paths)
        store.save(AppConfig())
        val controller = DesktopApplication(paths = paths, configStore = store, ioDispatcher = dispatcher)
        val externallyUpdated = "{\"schemaVersion\":\"0.9.0\",\"groups\":[]}"
        try {
            Files.writeString(paths.config, externallyUpdated)

            controller.onWindowFocused()
            assertTrue(controller.configFileSnapshotRefreshing)
            advanceUntilIdle()

            assertEquals(externallyUpdated, controller.configFileSnapshot.content)
            assertFalse(controller.configFileSnapshotRefreshing)
        } finally {
            controller.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `task manifest issue exposes the full path and disappears after a rescan`() {
        val root = Files.createTempDirectory("awm-task-manifest-issue")
        val paths = ApplicationPaths(root.resolve("home"))
        val taskRoot = root.resolve("tasks")
        val taskDirectory = taskRoot.resolve("legacy")
        Files.createDirectories(taskDirectory)
        val manifestPath = taskDirectory.resolve(ManifestStore.FILE_NAME)
        Files.writeString(manifestPath, "{\"schemaVersion\":\"0.8.1\"}")
        val store = ConfigStore(paths)
        store.save(AppConfig(taskRoot = taskRoot.toString()))
        val controller = DesktopApplication(paths = paths, configStore = store)
        try {
            val issue = controller.taskManifestIssues.single()
            assertEquals(manifestPath.toAbsolutePath().normalize().toString(), issue.manifestPath)
            assertContains(issue.reason, "版本不受支持")
            assertContains(controller.taskManifestRecoveryGuidance(issue), issue.manifestPath)

            ManifestStore().save(
                taskDirectory,
                TaskManifest(
                    folderName = "legacy",
                    taskDirectoryName = "legacy",
                    featureBranch = "feature/legacy",
                    createdAt = "2026-08-20T00:00:00Z",
                    updatedAt = "2026-08-20T00:00:00Z",
                    lifecycleStatus = TaskLifecycleStatus.ACTIVE,
                    services = emptyList(),
                ),
            )
            controller.refreshTaskManifestIssues()

            assertEquals(emptyList(), controller.taskManifestIssues)
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
            assertContains(preview, "已关联需求，资料目录将在创建任务时创建或复用")
            assertFalse(preview.contains("未关联需求，未创建资料目录"))
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

    private class RecordingCommandRunner(private val result: CommandResult) : CommandRunner {
        var calls = 0
            private set

        override fun run(
            command: List<String>,
            workingDirectory: Path?,
            timeout: Duration,
            environment: Map<String, String>,
        ): CommandResult {
            calls++
            return result
        }
    }

    private class RecordingMeegleCliService : MeegleCliService {
        var statusCalls = 0
            private set

        override fun status(): MeegleCliStatus {
            statusCalls++
            return MeegleCliStatus(installed = true)
        }

        override fun login(host: String) = Unit
    }

    private class RecordingGitEnvironmentRunner(private val detectedPath: String) : CommandRunner {
        var probeCalls = 0
            private set

        override fun run(
            command: List<String>,
            workingDirectory: Path?,
            timeout: Duration,
            environment: Map<String, String>,
        ): CommandResult = when {
            command.firstOrNull() == "where.exe" || command.firstOrNull() == "/bin/zsh" || command.firstOrNull() == "/bin/bash" -> {
                probeCalls++
                CommandResult(0, "$detectedPath\n", "")
            }
            command.lastOrNull() == "--version" -> CommandResult(0, "git version test\n", "")
            command.contains("config") -> CommandResult(0, "", "")
            else -> error("Unexpected Git command: ${command.joinToString(" ")}")
        }
    }
}
