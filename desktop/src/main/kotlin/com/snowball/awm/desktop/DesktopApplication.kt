package com.snowball.awm.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.snowball.awm.core.AgentDocumentService
import com.snowball.awm.core.AgentConflictResolution
import com.snowball.awm.core.AgentFileChange
import com.snowball.awm.core.AgentFileMonitor
import com.snowball.awm.core.AgentDocumentPropagationService
import com.snowball.awm.core.AppConfig
import com.snowball.awm.core.ApplicationPaths
import com.snowball.awm.core.BatchRepositoryAddResult
import com.snowball.awm.core.BranchReuseKey
import com.snowball.awm.core.ConfigStore
import com.snowball.awm.core.DeleteRisk
import com.snowball.awm.core.DesktopIntegration
import com.snowball.awm.core.MeegleRequirementMetadataProvider
import com.snowball.awm.core.MeegleProjectConfig
import com.snowball.awm.core.MeegleRequirementLinkSource
import com.snowball.awm.core.RequirementLinkFailureLog
import com.snowball.awm.core.GitRepositoryInspector
import com.snowball.awm.core.GroupConfigurationService
import com.snowball.awm.core.GroupServiceConfig
import com.snowball.awm.core.GitRemoteBranchCatalog
import com.snowball.awm.core.ManifestStore
import com.snowball.awm.core.RepositoryConfig
import com.snowball.awm.core.RepositoryInspector
import com.snowball.awm.core.RemoteBranchCatalog
import com.snowball.awm.core.RequirementMetadataProvider
import com.snowball.awm.core.RequirementMetadata
import com.snowball.awm.core.ServiceWorkspace
import com.snowball.awm.core.TagBuildService
import com.snowball.awm.core.UatTagDeliveryAdapter
import com.snowball.awm.core.DeliveryPipelineRegistry
import com.snowball.awm.core.TagOperation
import com.snowball.awm.core.TaskApplicationService
import com.snowball.awm.core.TaskManifest
import com.snowball.awm.core.TaskOperationLock
import com.snowball.awm.core.FileTaskOperationLock
import com.snowball.awm.core.TaskBranchInfoFormatter
import com.snowball.awm.core.TaskWorkspaceToolAvailability
import com.snowball.awm.core.TaskWorkspaceToolDescriptor
import com.snowball.awm.core.TaskWorkspaceToolRegistry
import com.snowball.awm.core.ThemePreference
import com.snowball.awm.core.TagNavigationPolicy
import com.snowball.awm.core.WorkspaceStrategy
import com.snowball.awm.core.WorkspaceToolLaunchService
import com.snowball.awm.core.WorkspaceGitHealth
import com.snowball.awm.core.WorkspaceGitStatusService
import com.snowball.awm.core.GitWorkspaceGitStatusReader
import com.snowball.awm.core.toInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import java.nio.file.Path

enum class NavigationItem(val title: String, val subtitle: String) {
    TASKS("研发任务", "Tasks"),
    ARCHIVED("已归档", "Archived"),
    SERVICES("服务仓库", "Services"),
    UAT("UAT 构建", "UAT Builds"),
    SETTINGS("设置", "Settings"),
}

data class DeleteRiskInspection(
    val loading: Boolean = true,
    val risks: List<DeleteRisk> = emptyList(),
    val error: String? = null,
)

sealed interface RemoteBranchesState {
    data object Idle : RemoteBranchesState
    data object Loading : RemoteBranchesState
    data class Loaded(val branches: List<String>) : RemoteBranchesState
    data class Failed(val message: String) : RemoteBranchesState
}

data class WorkspaceToolOption(
    val id: String,
    val displayName: String,
    val description: String,
    val available: Boolean,
    val unavailableReason: String? = null,
)

private data class LoadedTasks(
    val manifests: List<TaskManifest>,
    val warning: String? = null,
)

/** Desktop dependency container; feature controllers expose its application use cases to Compose. */
class DesktopApplication(
    private val paths: ApplicationPaths = ApplicationPaths.systemDefault(),
    private val configStore: ConfigStore = ConfigStore(paths),
    private val manifests: ManifestStore = ManifestStore(),
    private val repositoryInspector: RepositoryInspector = GitRepositoryInspector(),
    private val groupConfigurations: GroupConfigurationService =
        GroupConfigurationService(configStore, repositoryInspector),
    private val operationLock: TaskOperationLock = FileTaskOperationLock(paths),
    private val tasksApplication: TaskApplicationService = TaskApplicationService(
        manifests = manifests,
        agentDocuments = AgentDocumentService(paths),
        operationLock = operationLock,
    ),
    private val agentDocuments: AgentDocumentService = AgentDocumentService(paths),
    private val agentPropagation: AgentDocumentPropagationService =
        AgentDocumentPropagationService(manifests, agentDocuments, operationLock),
    private val uatDelivery: UatTagDeliveryAdapter = UatTagDeliveryAdapter(TagBuildService(paths = paths)),
    val deliveryRegistry: DeliveryPipelineRegistry = DeliveryPipelineRegistry(listOf(uatDelivery)),
    private val requirementMetadataProvider: RequirementMetadataProvider = MeegleRequirementMetadataProvider(),
    private val requirementLinkSource: MeegleRequirementLinkSource = MeegleRequirementLinkSource(),
    private val requirementLinkFailures: RequirementLinkFailureLog = RequirementLinkFailureLog(paths),
    private val gitStatusService: WorkspaceGitStatusService = WorkspaceGitStatusService(GitWorkspaceGitStatusReader()),
    private val desktopIntegration: DesktopIntegration = DesktopIntegration(),
    private val nativePathPicker: NativePathPicker = FileKitNativePathPicker(),
    private val remoteBranchCatalog: RemoteBranchCatalog = GitRemoteBranchCatalog(),
    private val workspaceToolRegistry: TaskWorkspaceToolRegistry = TaskWorkspaceToolRegistry(
        listOf(CodexWorkspaceToolLauncher(), CursorWorkspaceToolLauncher()),
    ),
    private val workspaceToolLaunchService: WorkspaceToolLaunchService = WorkspaceToolLaunchService(
        workspaceToolRegistry,
        manifests,
    ),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val initial = runCatching { configStore.load() }
    /**
     * A malformed on-disk configuration must never be mistaken for a new, empty
     * configuration.  The app still needs an in-memory model to render an error
     * state, but saves remain blocked by [ConfigStore] until the user fixes or
     * replaces the invalid file.
     */
    var configurationLoadError by mutableStateOf(initial.exceptionOrNull()?.message)
        private set
    private val agentMonitor: AgentFileMonitor by lazy {
        AgentFileMonitor(onChange = { change ->
            scope.launch { agentInstructionsController.handleFileChange(change) }
        })
    }

    private val initialConfig = initial.getOrDefault(AppConfig())
    private val initialTasks = scanTasks(initialConfig)
    val sessionStore = AppSessionStore(initialConfig, initialTasks.manifests)
    val operationCoordinator = OperationCoordinator(
        initial.exceptionOrNull()?.let { "配置读取失败：${it.message}" } ?: initialTasks.warning,
    )
    private val operationRunner = OperationRunner(operationCoordinator, scope, ioDispatcher)
    private val requirementMetadataCoordinator = RequirementMetadataCoordinator(
        provider = requirementMetadataProvider,
        scope = scope,
        ioDispatcher = ioDispatcher,
    )
    val requirementController = RequirementController(
        session = sessionStore,
        scope = scope,
        coordinator = requirementMetadataCoordinator,
        linkSource = requirementLinkSource,
        failureLog = requirementLinkFailures,
        ioDispatcher = ioDispatcher,
    )
    val desktopActions = DesktopActions(
        integration = desktopIntegration,
        config = { config },
        onSettingsRequired = { navigation = NavigationItem.SETTINGS },
        onStatus = ::showStatus,
        onError = ::showError,
    )
    val taskController: TaskController by lazy {
        TaskController(
            session = sessionStore,
            configStore = configStore,
            manifests = manifests,
            repositoryInspector = repositoryInspector,
            tasks = tasksApplication,
            gitStatus = gitStatusService,
            workspaceTools = workspaceToolLaunchService,
            operations = operationRunner,
            scope = scope,
            ioDispatcher = ioDispatcher,
            desktopActions = desktopActions,
            onConfigApplied = ::applyConfig,
            onTasksChanged = {
                deliveryController.reloadHistory()
                requirementController.reconcileTasks()
            },
            onRequirementsRefresh = requirementController::refreshAll,
            onRequirementSelected = requirementController::refresh,
            onError = ::showError,
            isBusy = { busy },
        )
    }
    val settingsController by lazy {
        SettingsController(
            session = sessionStore,
            configStore = configStore,
            groups = groupConfigurations,
            pathPicker = nativePathPicker,
            branchCatalog = remoteBranchCatalog,
            scope = scope,
            ioDispatcher = ioDispatcher,
            operations = operationRunner,
            applyConfig = ::applyConfig,
            reloadTasks = { reloadTasks() },
            showError = ::showError,
            showStatus = ::showStatus,
        )
    }
    val agentInstructionsController: AgentInstructionsController by lazy {
        AgentInstructionsController(
            session = sessionStore,
            paths = paths,
            documents = agentDocuments,
            propagation = agentPropagation,
            tasks = tasksApplication,
            monitor = agentMonitor,
            operations = operationRunner,
            scope = scope,
            ioDispatcher = ioDispatcher,
            taskDirectory = ::taskDirectory,
            desktopActions = desktopActions,
            isBusy = { busy },
            showError = ::showError,
            showStatus = ::showStatus,
        )
    }
    val deliveryController by lazy {
        DeliveryController(
            session = sessionStore,
            adapter = uatDelivery,
            operations = operationRunner,
            taskDirectory = ::taskDirectory,
            refreshGitStatus = ::refreshCurrentTaskGitStatus,
        )
    }

    var config: AppConfig
        get() = sessionStore.config
        private set(value) { sessionStore.config = value }
    var repositories by mutableStateOf(config.repositories.map(RepositoryConfig::toInfo))
        private set
    var tasks: List<TaskManifest>
        get() = sessionStore.tasks
        private set(value) { sessionStore.tasks = value }
    var navigation: NavigationItem
        get() = sessionStore.navigation
        set(value) {
            sessionStore.navigation = value
            if (value in setOf(NavigationItem.TASKS, NavigationItem.ARCHIVED)) {
                taskController.refreshGitStatus()
                requirementController.refreshAll()
            }
        }
    var selectedTask: TaskManifest?
        get() = sessionStore.selectedTask
        private set(value) { sessionStore.selectedTask = value }
    val busy: Boolean get() = operationCoordinator.busy
    val activeOperation: String? get() = operationCoordinator.activeMessage
    val statusMessage: String? get() = operationCoordinator.statusMessage
    val errorMessage: String? get() = operationCoordinator.errorMessage
    val tagResult: TagOperation? get() = deliveryController.state.result
    val batchTagResults: List<TagOperation>? get() = deliveryController.state.batchResults
    val tagHistory: List<TagOperation> get() = deliveryController.state.history
    val agentRevision: Long get() = agentInstructionsController.state.revision
    val agentConflict: AgentFileChange.Conflict? get() = agentInstructionsController.state.conflict
    val deleteRiskInspections: Map<String, DeleteRiskInspection> get() = taskController.state.deleteRisks
    val pathPickerBusy: Boolean get() = settingsController.state.pathPickerBusy
    val remoteBranches: Map<String, RemoteBranchesState> get() = settingsController.state.remoteBranches
    val repositoryAddResult: BatchRepositoryAddResult? get() = settingsController.state.repositoryAddResult
    val workspaceGitHealth: Map<String, WorkspaceGitHealth> get() = taskController.state.gitHealth

    val needsTaskRoot: Boolean get() = config.taskRoot.isNullOrBlank()
    val showsUatNavigation: Boolean get() = TagNavigationPolicy.isVisible(config)
    val globalAgentsPath: String get() = paths.globalAgents.toAbsolutePath().normalize().toString()
    fun groupAgentsPath(groupId: String): String = paths.groupAgents(groupId).toAbsolutePath().normalize().toString()

    fun workspaceToolOptions(groupId: String): List<WorkspaceToolOption> {
        val configuredIds = config.group(groupId).defaultWorkspaceToolIds
        val descriptors = workspaceToolRegistry.descriptors().associateBy(TaskWorkspaceToolDescriptor::id)
        return (descriptors.keys + configuredIds).distinct().map { toolId ->
            val descriptor = descriptors[toolId]
            when (val availability = workspaceToolRegistry.availability(toolId)) {
                TaskWorkspaceToolAvailability.Available -> WorkspaceToolOption(
                    id = toolId,
                    displayName = descriptor?.displayName ?: toolId,
                    description = descriptor?.description.orEmpty(),
                    available = true,
                )
                is TaskWorkspaceToolAvailability.Unavailable -> WorkspaceToolOption(
                    id = toolId,
                    displayName = descriptor?.displayName ?: toolId,
                    description = descriptor?.description.orEmpty(),
                    available = false,
                    unavailableReason = availability.reason,
                )
            }
        }
    }

    fun taskPath(task: TaskManifest): String = taskDirectory(task).toString()

    fun gitHealth(workspace: ServiceWorkspace): WorkspaceGitHealth? =
        taskController.gitHealth(workspace)

    fun requestRequirementMetadata(link: String, onResult: (RequirementMetadata?) -> Unit) {
        requirementController.requestDraftMetadata(link, onResult)
    }

    /** Saves the configured Feishu project identities without enabling any automatic query. */
    fun updateMeegleProjects(projects: List<MeegleProjectConfig>): Boolean =
        settingsController.updateMeegleProjects(projects)


    fun refreshCurrentTaskGitStatus() = taskController.refreshGitStatus()

    fun addableServices(task: TaskManifest): List<GroupServiceConfig> {
        val existing = task.services.map(ServiceWorkspace::groupServiceId).toSet()
        return config.group(task.groupId).services.filter { it.enabled && it.id !in existing }
    }

    fun canBuildTag(task: TaskManifest, workspace: ServiceWorkspace): Boolean = deliveryController.canBuild(task, workspace)

    init {
        requirementLinkFailures.cleanup()
        // Register authoritative global/group files without invoking Git or a
        // remote integration. Subsequent external writes can then propagate
        // even if the user never opens the Settings editor.
        runCatching {
            agentDocuments.ensureGlobalFile()
            agentMonitor.track(paths.globalAgents)
        }.onFailure(::showError)
        config.groups.forEach { group ->
            runCatching { agentMonitor.track(paths.groupAgents(group.id)) }.onFailure(::showError)
        }
        refreshCurrentTaskGitStatus()
    }

    /**
     * The only repository validation entry point. Startup deliberately does not
     * call this method, so opening the app never runs Git or Meegle commands.
     */
    fun refresh() = taskController.refresh()

    fun selectTask(task: TaskManifest) = taskController.select(task)

    fun setTheme(theme: ThemePreference) = settingsController.setTheme(theme)
    fun updateTaskRoot(value: String) = settingsController.updateTaskRoot(value)
    fun updateExecutables(idea: String, webStorm: String, terminal: String) = settingsController.updateExecutables(idea, webStorm, terminal)
    fun addGroup(name: String, onCompleted: () -> Unit = {}) = settingsController.addGroup(name, onCompleted)
    fun renameGroup(groupId: String, name: String, onCompleted: () -> Unit = {}) = settingsController.renameGroup(groupId, name, onCompleted)
    fun moveGroup(groupId: String, offset: Int) = settingsController.moveGroup(groupId, offset)
    fun deleteGroup(groupId: String, onCompleted: () -> Unit = {}) = settingsController.deleteGroup(groupId, onCompleted)
    fun setGroupTagEnabled(groupId: String, enabled: Boolean) = settingsController.setGroupTagEnabled(groupId, enabled)
    fun updateGroupDefaults(groupId: String, branchPrefix: String, workspaceToolIds: List<String>) =
        settingsController.updateGroupDefaults(groupId, branchPrefix, workspaceToolIds)
    fun chooseDirectory(initialPath: String? = null, onSelected: (String) -> Unit) = settingsController.chooseDirectory(initialPath, onSelected)
    fun chooseFile(initialPath: String? = null, onSelected: (String) -> Unit) = settingsController.chooseFile(initialPath, onSelected)
    fun chooseDirectories(initialPath: String? = null, onSelected: (List<String>) -> Unit) = settingsController.chooseDirectories(initialPath, onSelected)
    fun loadRemoteBranches(repositoryId: String, remote: String = "origin", force: Boolean = false) =
        settingsController.loadRemoteBranches(repositoryId, remote, force)
    fun remoteBranchState(repositoryId: String, remote: String) = settingsController.remoteBranchState(repositoryId, remote)
    fun addRepository(groupId: String, selectedDirectory: String, strategy: WorkspaceStrategy) =
        settingsController.addRepository(groupId, selectedDirectory, strategy)
    fun addRepositories(groupId: String, selectedDirectories: List<String>, onCompleted: () -> Unit = {}) =
        settingsController.addRepositories(groupId, selectedDirectories, onCompleted)
    fun clearRepositoryAddResult() = settingsController.clearRepositoryAddResult()
    fun updateService(groupId: String, service: GroupServiceConfig, onCompleted: () -> Unit = {}) =
        settingsController.updateService(groupId, service, onCompleted)
    fun moveService(groupId: String, serviceId: String, offset: Int) = settingsController.moveService(groupId, serviceId, offset)
    fun removeService(groupId: String, serviceId: String, onCompleted: () -> Unit = {}) =
        settingsController.removeService(groupId, serviceId, onCompleted)
    fun readGlobalAgents(): String = agentInstructionsController.readGlobal()
    fun saveGlobalAgents(content: String) = agentInstructionsController.saveGlobal(content)
    fun markGlobalAgentsEdited(content: String) = agentInstructionsController.markGlobalEdited(content)
    fun readGroupAgents(groupId: String): String = agentInstructionsController.readGroup(groupId)
    fun saveGroupAgents(groupId: String, content: String) = agentInstructionsController.saveGroup(groupId, content)
    fun markGroupAgentsEdited(groupId: String, content: String) = agentInstructionsController.markGroupEdited(groupId, content)
    fun previewAgents(folderName: String, branch: String, groupId: String, serviceIds: Set<String>, requirementLink: String, notes: String): String =
        agentInstructionsController.preview(folderName, branch, groupId, serviceIds, requirementLink, notes)
    fun createTask(
        folderName: String,
        branch: String,
        groupId: String,
        serviceIds: List<String>,
        requirementLink: String,
        notes: String,
        workspaceToolIds: List<String> = emptyList(),
        confirmedBranchReuseKeys: Set<BranchReuseKey> = emptySet(),
        onCompleted: () -> Unit = {},
    ) = taskController.create(
        folderName,
        branch,
        groupId,
        serviceIds,
        requirementLink,
        notes,
        workspaceToolIds,
        confirmedBranchReuseKeys,
        onCompleted,
    )

    fun retryWorkspaceTool(task: TaskManifest, toolId: String) = taskController.retryWorkspaceTool(task, toolId)

    fun readTaskNotes(task: TaskManifest): String = agentInstructionsController.readTaskNotes(task)
    fun saveTaskNotes(task: TaskManifest, notes: String) = agentInstructionsController.saveTaskNotes(task, notes)
    fun markTaskNotesEdited(task: TaskManifest, notes: String) = agentInstructionsController.markTaskNotesEdited(task, notes)
    fun archiveTask(task: TaskManifest, force: Boolean = false, onCompleted: () -> Unit = {}) = taskController.archive(task, onCompleted)

    fun restoreTask(task: TaskManifest, onCompleted: () -> Unit = {}) = taskController.restore(task, onCompleted)

    /** Runs Git safety checks away from Compose's event-dispatch thread. */
    fun requestDeleteRisk(task: TaskManifest) = taskController.requestDeleteRisk(task)

    fun clearDeleteRisk(task: TaskManifest) = taskController.clearDeleteRisk(task)

    fun deleteTask(task: TaskManifest, forceDiscard: Boolean, onCompleted: () -> Unit = {}) =
        taskController.delete(task, forceDiscard, onCompleted)

    fun buildTag(task: TaskManifest, workspace: ServiceWorkspace) = deliveryController.build(task, workspace)
    fun buildTags(task: TaskManifest, workspaces: List<ServiceWorkspace>, onCompleted: () -> Unit = {}) =
        deliveryController.buildBatch(task, workspaces, onCompleted)
    fun clearBatchTagResults() = deliveryController.clearBatchResults()

    fun addServices(
        task: TaskManifest,
        serviceIds: List<String>,
        confirmedBranchReuseKeys: Set<BranchReuseKey> = emptySet(),
        onCompleted: () -> Unit = {},
    ) = taskController.addServices(task, serviceIds, confirmedBranchReuseKeys, onCompleted)

    fun retryFailedServices(task: TaskManifest, serviceIds: List<String>? = null) = taskController.retry(task, serviceIds)

    fun branchInfo(task: TaskManifest): String = TaskBranchInfoFormatter.format(task)

    fun openWorkData(task: TaskManifest) = taskController.openWorkData(task)

    fun clearTagResult() = deliveryController.clearResult()

    fun openWorkspace(workspace: ServiceWorkspace) = desktopActions.openWorkspace(workspace)

    fun reveal(path: String) = desktopActions.reveal(Path.of(path))
    fun openDirectory(path: String) = desktopActions.openDirectory(Path.of(path))

    fun revealGlobalAgents() = agentInstructionsController.revealGlobal()
    fun revealGroupAgents(groupId: String) = agentInstructionsController.revealGroup(groupId)
    fun terminal(path: String) = desktopActions.terminal(Path.of(path))
    fun openUrl(url: String) = desktopActions.openUrl(url)
    fun copyText(text: String, message: String = "已复制") = desktopActions.copy(text, message)

    /** Called from Window.onFocusEvent as the inexpensive external-file fallback. */
    fun onWindowFocused() = agentInstructionsController.onWindowFocused()
    fun resolveAgentConflict(resolution: AgentConflictResolution) = agentInstructionsController.resolveConflict(resolution)
    fun dismissMessages() {
        operationCoordinator.dismiss()
    }

    fun showError(error: Throwable) {
        operationCoordinator.errorMessage = error.message ?: error::class.simpleName ?: "操作失败"
    }

    override fun close() {
        requirementController.close()
        agentMonitor.close()
        scope.cancel()
    }

    private fun taskDirectory(task: TaskManifest): Path = taskDirectory(config, task)

    private fun taskDirectory(config: AppConfig, task: TaskManifest): Path =
        Path.of(requireNotNull(config.taskRoot) { "尚未配置任务根目录" }).resolve(task.taskDirectoryName)

    private fun scanTasks(config: AppConfig): LoadedTasks {
        val root = config.taskRoot?.let(Path::of) ?: return LoadedTasks(emptyList())
        val scan = runCatching { manifests.scan(root) }.getOrElse { error ->
            return LoadedTasks(emptyList(), "任务目录扫描失败：${error.message ?: error::class.simpleName}")
        }
        val messages = buildList {
            if (scan.unsupportedDirectories.isNotEmpty()) {
                add("已忽略 ${scan.unsupportedDirectories.size} 个非 AWM v5 任务目录")
            }
            if (scan.failures.isNotEmpty()) {
                add(
                    "${scan.failures.size} 个任务清单读取失败：" +
                        scan.failures.entries.joinToString { (path, reason) -> "${path.fileName}（$reason）" },
                )
            }
        }
        return LoadedTasks(
            manifests = scan.current.map { it.second }.sortedByDescending(TaskManifest::updatedAt),
            warning = messages.takeIf { it.isNotEmpty() }?.joinToString("；"),
        )
    }

    private fun reloadTasks(preferredFolder: String? = selectedTask?.folderName) {
        val loaded = scanTasks(config)
        sessionStore.replaceTasks(loaded.manifests, preferredFolder)
        deliveryController.reloadHistory()
        requirementController.reconcileTasks()
        refreshCurrentTaskGitStatus()
        loaded.warning?.let { showError(IllegalStateException(it)) }
    }

    private fun applyConfig(updated: AppConfig) {
        val requirementConfigurationChanged = config.meegleProjects != updated.meegleProjects
        config = updated
        configurationLoadError = null
        if (navigation == NavigationItem.UAT && !TagNavigationPolicy.isVisible(updated)) {
            navigation = NavigationItem.TASKS
        }
        repositories = updated.repositories.map(RepositoryConfig::toInfo)
        if (requirementConfigurationChanged) requirementController.onConfigurationChanged()
        updated.groups.forEach { group ->
            runCatching { agentMonitor.track(paths.groupAgents(group.id)) }.onFailure(::showError)
        }
    }

    private fun showStatus(message: String) {
        operationCoordinator.statusMessage = message
        operationCoordinator.errorMessage = null
    }

}
