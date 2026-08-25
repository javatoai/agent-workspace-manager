package com.snowball.awm.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.snowball.awm.core.AgentDocumentService
import com.snowball.awm.core.AgentConflictResolution
import com.snowball.awm.core.AgentFileChange
import com.snowball.awm.core.AgentFileMonitor
import com.snowball.awm.core.AgentDocumentPropagationService
import com.snowball.awm.core.AgentTaskTemplate
import com.snowball.awm.core.AppConfig
import com.snowball.awm.core.ApplicationPaths
import com.snowball.awm.core.BatchRepositoryAddResult
import com.snowball.awm.core.BranchReuseKey
import com.snowball.awm.core.ConfigStore
import com.snowball.awm.core.DeleteRisk
import com.snowball.awm.core.DesktopIntegration
import com.snowball.awm.core.ConfiguredGitExecutable
import com.snowball.awm.core.ConfiguredMeegleExecutable
import com.snowball.awm.core.GitBranchReferenceValidator
import com.snowball.awm.core.GitClient
import com.snowball.awm.core.GitCommandSource
import com.snowball.awm.core.GitWorkspaceLifecycle
import com.snowball.awm.core.MeegleCommandSource
import com.snowball.awm.core.MeegleRequirementMetadataProvider
import com.snowball.awm.core.MeegleProjectConfig
import com.snowball.awm.core.MeegleProjectCatalog
import com.snowball.awm.core.CliMeegleProjectCatalog
import com.snowball.awm.core.MeegleCliService
import com.snowball.awm.core.ProcessMeegleCliService
import com.snowball.awm.core.LocalGitEnvironmentInspector
import com.snowball.awm.core.DiagnosticsExporter
import com.snowball.awm.core.ApplicationEvent
import com.snowball.awm.core.ApplicationErrorLogReader
import com.snowball.awm.core.EventSink
import com.snowball.awm.core.JsonlEventSink
import com.snowball.awm.core.error
import com.snowball.awm.core.MeegleRequirementLinkSource
import com.snowball.awm.core.RequirementLinkFailureLog
import com.snowball.awm.core.GitRepositoryInspector
import com.snowball.awm.core.GroupConfigurationService
import com.snowball.awm.core.GroupServiceConfig
import com.snowball.awm.core.GitRemoteBranchCatalog
import com.snowball.awm.core.GitRepositoryRemoteCatalog
import com.snowball.awm.core.ManifestStore
import com.snowball.awm.core.ModuleBaseOverride
import com.snowball.awm.core.TaskServiceSelection
import com.snowball.awm.core.RepositoryConfig
import com.snowball.awm.core.RepositoryInspector
import com.snowball.awm.core.RepositoryOperationLock
import com.snowball.awm.core.RemoteBranchCatalog
import com.snowball.awm.core.RepositoryRemoteCatalog
import com.snowball.awm.core.RequirementMetadataProvider
import com.snowball.awm.core.RequirementMetadata
import com.snowball.awm.core.RequirementMaterialsService
import com.snowball.awm.core.ServiceWorkspace
import com.snowball.awm.core.TagBuildService
import com.snowball.awm.core.GitTagDeliveryAdapter
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
import com.snowball.awm.core.WorkspaceGitOperationService
import com.snowball.awm.core.WorkspaceGitBatchMode
import com.snowball.awm.core.WorkspaceGitBatchResult
import com.snowball.awm.core.GitWorkspaceGitStatusReader
import com.snowball.awm.core.GitTaskBranchCatalog
import com.snowball.awm.core.TaskBranchCatalog
import com.snowball.awm.core.WorkspaceRepairConfirmation
import com.snowball.awm.core.WorkspaceRepairPreview
import com.snowball.awm.core.WorkspaceRepairResult
import com.snowball.awm.core.WorkspaceRepairService
import com.snowball.awm.core.WorkspaceProvisioningService
import com.snowball.awm.core.WorkspaceBranchReuseInspector
import com.snowball.awm.core.StandardWorktreeProvisioner
import com.snowball.awm.core.IndependentCloneProvisioner
import com.snowball.awm.core.BootstrapService
import com.snowball.awm.core.WorkspaceModuleRemovalService
import com.snowball.awm.core.toInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

enum class NavigationItem(val title: String, val subtitle: String) {
    TASKS("研发任务", "Tasks"),
    ARCHIVED("已归档", "Archived"),
    SERVICES("服务仓库", "Services"),
    TAG("Tag 构建", "Tag Builds"),
    SETTINGS("设置", "Settings"),
}

data class DeleteRiskInspection(
    val loading: Boolean = true,
    val risks: List<DeleteRisk> = emptyList(),
    val error: String? = null,
)

sealed interface RemoteBranchesState {
    data object Idle : RemoteBranchesState
    data class Loading(val staleBranches: List<String> = emptyList()) : RemoteBranchesState
    data class Loaded(
        val branches: List<String>,
        val loadedAtNanos: Long = System.nanoTime(),
    ) : RemoteBranchesState
    data class Failed(
        val message: String,
        val staleBranches: List<String> = emptyList(),
    ) : RemoteBranchesState
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
    val manifestIssues: List<TaskManifestIssue> = emptyList(),
)

/** A task manifest that AWM deliberately left unchanged because it could not read it safely. */
data class TaskManifestIssue(
    val manifestPath: String,
    val reason: String,
)

/** Desktop dependency container; feature controllers expose its application use cases to Compose. */
class DesktopApplication(
    private val paths: ApplicationPaths = ApplicationPaths.systemDefault(),
    private val events: EventSink = JsonlEventSink(paths),
    private val errorLogReader: ApplicationErrorLogReader = ApplicationErrorLogReader(paths),
    private val meegleExecutablePath: AtomicReference<String?> = AtomicReference(null),
    private val meegleExecutable: ConfiguredMeegleExecutable = ConfiguredMeegleExecutable(meegleExecutablePath::get),
    private val gitExecutablePath: AtomicReference<String?> = AtomicReference(null),
    private val gitExecutable: ConfiguredGitExecutable = ConfiguredGitExecutable(gitExecutablePath::get),
    private val gitClient: GitClient = GitClient(executable = gitExecutable),
    private val bootstrapService: BootstrapService = BootstrapService(git = gitClient),
    private val diagnosticsExporter: DiagnosticsExporter = DiagnosticsExporter(paths, git = gitClient, meegleExecutable = meegleExecutable),
    private val configStore: ConfigStore = ConfigStore(paths),
    private val manifests: ManifestStore = ManifestStore(),
    private val repositoryInspector: RepositoryInspector = GitRepositoryInspector(gitClient),
    private val groupConfigurations: GroupConfigurationService =
        GroupConfigurationService(configStore, repositoryInspector),
    private val operationLock: TaskOperationLock = FileTaskOperationLock(paths),
    private val repositoryLock: RepositoryOperationLock = RepositoryOperationLock(paths),
    private val agentDocuments: AgentDocumentService = AgentDocumentService(paths),
    private val provisioning: WorkspaceProvisioningService = WorkspaceProvisioningService(
        listOf(
            StandardWorktreeProvisioner(git = gitClient, bootstrap = bootstrapService, repositoryLock = repositoryLock),
            IndependentCloneProvisioner(git = gitClient, bootstrap = bootstrapService),
        ),
    ),
    private val branchReuseInspector: WorkspaceBranchReuseInspector =
        WorkspaceBranchReuseInspector(git = gitClient, repositoryLock = repositoryLock),
    private val workspaceRepairs: WorkspaceRepairService = WorkspaceRepairService(
        manifests = manifests,
        agentDocuments = agentDocuments,
        taskLock = operationLock,
        repositoryLock = repositoryLock,
        git = gitClient,
        bootstrap = bootstrapService,
    ),
    private val tasksApplication: TaskApplicationService = TaskApplicationService(
        manifests = manifests,
        provisioning = provisioning,
        agentDocuments = agentDocuments,
        requirementMaterials = RequirementMaterialsService(meegleExecutable = meegleExecutable),
        lifecycle = GitWorkspaceLifecycle(git = gitClient, bootstrap = bootstrapService, repositoryLock = repositoryLock),
        operationLock = operationLock,
        branchValidator = GitBranchReferenceValidator(gitExecutable = gitExecutable),
        branchReuseInspector = branchReuseInspector,
        repairs = workspaceRepairs,
        moduleRemoval = WorkspaceModuleRemovalService(
            manifests = manifests,
            agentDocuments = agentDocuments,
            taskLock = operationLock,
            repositoryLock = repositoryLock,
            git = gitClient,
        ),
        bootstrap = bootstrapService,
    ),
    private val agentPropagation: AgentDocumentPropagationService =
        AgentDocumentPropagationService(manifests, agentDocuments, operationLock),
    private val tagDelivery: GitTagDeliveryAdapter = GitTagDeliveryAdapter(
        TagBuildService(paths = paths, git = gitClient, repositoryLock = repositoryLock),
    ),
    val deliveryRegistry: DeliveryPipelineRegistry = DeliveryPipelineRegistry(listOf(tagDelivery)),
    private val requirementMetadataProvider: RequirementMetadataProvider = MeegleRequirementMetadataProvider(meegleExecutable = meegleExecutable),
    private val requirementLinkSource: MeegleRequirementLinkSource = MeegleRequirementLinkSource(metadata = requirementMetadataProvider, meegleExecutable = meegleExecutable),
    private val requirementLinkFailures: RequirementLinkFailureLog = RequirementLinkFailureLog(paths),
    private val gitStatusService: WorkspaceGitStatusService = WorkspaceGitStatusService(GitWorkspaceGitStatusReader(gitClient)),
    private val gitOperationService: WorkspaceGitOperationService = WorkspaceGitOperationService(gitClient, repositoryLock),
    private val taskBranchCatalog: TaskBranchCatalog = GitTaskBranchCatalog(gitClient),
    private val desktopIntegration: DesktopIntegration = DesktopIntegration(),
    private val nativePathPicker: NativePathPicker = FileKitNativePathPicker(),
    private val remoteBranchCatalog: RemoteBranchCatalog = GitRemoteBranchCatalog(gitClient),
    private val repositoryRemoteCatalog: RepositoryRemoteCatalog = GitRepositoryRemoteCatalog(gitClient),
    private val meegleProjectCatalog: MeegleProjectCatalog = CliMeegleProjectCatalog(meegleExecutable = meegleExecutable),
    private val meegleCliService: MeegleCliService = ProcessMeegleCliService(meegleExecutable = meegleExecutable),
    private val localGitInspector: LocalGitEnvironmentInspector = LocalGitEnvironmentInspector(gitExecutable = gitExecutable),
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
    var configFileSnapshot by mutableStateOf(configStore.fileSnapshot())
        private set
    var configFileSnapshotRefreshing by mutableStateOf(false)
        private set
    private val agentMonitor: AgentFileMonitor by lazy {
        AgentFileMonitor(onChange = { change ->
            scope.launch { agentInstructionsController.handleFileChange(change) }
        })
    }

    private val initialConfig = initial.getOrDefault(AppConfig()).also {
        meegleExecutablePath.set(it.meegleExecutablePath)
        gitExecutablePath.set(it.gitExecutablePath)
    }
    private val initialTasks = scanTasks(initialConfig)
    private var taskScanWarning: String? = initialTasks.warning
    var taskManifestIssues by mutableStateOf(initialTasks.manifestIssues)
        private set
    val sessionStore = AppSessionStore(initialConfig, initialTasks.manifests)
    val operationCoordinator = OperationCoordinator(
        initialError = initial.exceptionOrNull()?.let { "配置读取失败：${it.message}" } ?: initialTasks.warning,
        onError = ::recordError,
    )
    var recentErrors by mutableStateOf(errorLogReader.latest())
        private set
    private val operationRunner = OperationRunner(operationCoordinator, scope, ioDispatcher)
    private val settingsOperationCoordinator = OperationCoordinator(onError = ::recordError)
    private val settingsOperationRunner = OperationRunner(settingsOperationCoordinator, scope, ioDispatcher)
    private val meegleOperationCoordinator = OperationCoordinator(onError = ::recordError)
    private val meegleOperationRunner = OperationRunner(meegleOperationCoordinator, scope, ioDispatcher)
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
            gitOperations = gitOperationService,
            taskBranchCatalog = taskBranchCatalog,
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
            events = events,
        )
    }
    val settingsController by lazy {
        SettingsController(
            session = sessionStore,
            configStore = configStore,
            groups = groupConfigurations,
            pathPicker = nativePathPicker,
            branchCatalog = remoteBranchCatalog,
            remoteCatalog = repositoryRemoteCatalog,
            meegleProjectCatalog = meegleProjectCatalog,
            meegleCliService = meegleCliService,
            meegleExecutable = meegleExecutable,
            gitExecutable = gitExecutable,
            localGitInspector = localGitInspector,
            scope = scope,
            ioDispatcher = ioDispatcher,
            operations = operationRunner,
            settingsOperations = settingsOperationRunner,
            meegleOperations = meegleOperationRunner,
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
            adapter = tagDelivery,
            operations = operationRunner,
            taskDirectory = ::taskDirectory,
            refreshGitStatus = ::refreshCurrentTaskGitStatus,
        )
    }

    var config: AppConfig
        get() = sessionStore.config
        private set(value) {
            meegleExecutablePath.set(value.meegleExecutablePath)
            gitExecutablePath.set(value.gitExecutablePath)
            sessionStore.config = value
        }
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
    val activeOperationCancellable: Boolean get() = operationCoordinator.cancellable
    val activeOperationCancelling: Boolean get() = operationCoordinator.cancelling
    fun cancelActiveOperation(): Boolean = operationRunner.cancel()
    val meegleBusy: Boolean get() = meegleOperationCoordinator.busy
    val settingsBusy: Boolean get() = settingsOperationCoordinator.busy
    val hasActiveOperations: Boolean get() = busy || settingsBusy || meegleBusy
    val meegleOperationCancellable: Boolean get() = meegleOperationCoordinator.cancellable
    val meegleOperationError: String? get() = meegleOperationCoordinator.errorMessage
    fun cancelMeegleOperation(): Boolean = meegleOperationRunner.cancel()
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
    val workspaceRepairPreview: WorkspaceRepairPreview? get() = taskController.repairPreview
    val workspaceRepairResult: WorkspaceRepairResult? get() = taskController.repairResult
    val taskBranchCandidates: TaskBranchCandidatesState get() = taskController.branchCandidates
    val batchGitPreviewState: BatchGitPreviewState get() = taskController.batchGitPreviews

    val needsTaskRoot: Boolean get() = config.taskRoot.isNullOrBlank()
    val showsTagNavigation: Boolean get() = TagNavigationPolicy.isVisible(config)
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

    fun inspectWorkspaceRepair(task: TaskManifest, workspace: ServiceWorkspace) = taskController.inspectRepair(task, workspace)
    fun clearWorkspaceWarnings(task: TaskManifest, workspace: ServiceWorkspace) = taskController.clearWorkspaceWarnings(task, workspace)
    fun rerunWorkspaceBootstrap(task: TaskManifest, workspace: ServiceWorkspace) = taskController.rerunWorkspaceBootstrap(task, workspace)
    fun repairWorkspace(task: TaskManifest, preview: WorkspaceRepairPreview, confirmation: WorkspaceRepairConfirmation) =
        taskController.repairWorkspace(task, preview, confirmation)
    fun clearWorkspaceRepairPreview() = taskController.clearRepairPreview()
    fun clearWorkspaceRepairResult() = taskController.clearRepairResult()
    fun loadTaskBranchCandidates(groupId: String, serviceIds: Set<String>) = taskController.loadTaskBranchCandidates(groupId, serviceIds)
    fun cancelTaskBranchCandidates() = taskController.cancelTaskBranchCandidates()
    fun cancelRemoteBranchLoads() = settingsController.cancelRemoteBranchLoads()

    fun requestRequirementMetadata(link: String, onResult: (RequirementMetadata?) -> Unit) {
        requirementController.requestDraftMetadata(link, onResult)
    }

    /** Saves the configured Feishu project identities without enabling any automatic query. */
    fun updateMeegleProjects(projects: List<MeegleProjectConfig>, onFailure: (Throwable) -> Unit = {}): Boolean =
        settingsController.updateMeegleProjects(projects, onFailure)

    fun updateMeegleExecutablePath(raw: String, onFailure: (Throwable) -> Unit = {}): Boolean =
        settingsController.updateMeegleExecutablePath(raw, onFailure)

    fun meegleCommandResolution(): Pair<String, MeegleCommandSource> = settingsController.meegleCommandResolution()

    fun updateGitExecutablePath(raw: String, onFailure: (Throwable) -> Unit = {}): Boolean =
        settingsController.updateGitExecutablePath(raw, onFailure)

    fun gitCommandResolution(): Pair<String, GitCommandSource> = settingsController.gitCommandResolution()


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
            runCatching {
                agentDocuments.ensureGroupFile(group.id)
                agentMonitor.track(paths.groupAgents(group.id))
            }.onFailure(::showError)
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
    fun updateTaskRoot(value: String, onFailure: (Throwable) -> Unit = {}) = settingsController.updateTaskRoot(value, onFailure)
    fun updateRequirementMaterialsRoot(value: String, onFailure: (Throwable) -> Unit = {}) =
        settingsController.updateRequirementMaterialsRoot(value, onFailure)
    fun updateRequirementMaterialsSubdirectory(value: String, onFailure: (Throwable) -> Unit = {}) =
        settingsController.updateRequirementMaterialsSubdirectory(value, onFailure)
    fun updateDevelopmentTools(
        tools: List<com.snowball.awm.core.DevelopmentToolConfig>,
        defaultTool: com.snowball.awm.core.DevelopmentToolType,
        terminal: String,
        allowTemporaryDevelopmentToolSelection: Boolean,
        onFailure: (Throwable) -> Unit = {},
    ) = settingsController.updateDevelopmentTools(tools, defaultTool, terminal, allowTemporaryDevelopmentToolSelection, onFailure)
    fun updateHiddenTaskDetailBranches(branches: List<String>, onFailure: (Throwable) -> Unit = {}) =
        settingsController.updateHiddenTaskDetailBranches(branches, onFailure)
    fun addGroup(name: String, onCompleted: () -> Unit = {}) = settingsController.addGroup(name, onCompleted)
    fun renameGroup(groupId: String, name: String, onCompleted: () -> Unit = {}) = settingsController.renameGroup(groupId, name, onCompleted)
    fun moveGroup(groupId: String, offset: Int) = settingsController.moveGroup(groupId, offset)
    fun deleteGroup(groupId: String, onCompleted: () -> Unit = {}) = settingsController.deleteGroup(groupId, onCompleted)
    fun setGroupTagEnabled(groupId: String, enabled: Boolean) = settingsController.setGroupTagEnabled(groupId, enabled)
    fun updateGroupDefaults(groupId: String, branchPrefix: String, workspaceToolIds: List<String>, onFailure: (Throwable) -> Unit = {}) =
        settingsController.updateGroupDefaults(groupId, branchPrefix, workspaceToolIds, onFailure)
    fun chooseDirectory(initialPath: String? = null, onSelected: (String) -> Unit) = settingsController.chooseDirectory(initialPath, onSelected)
    fun chooseFile(initialPath: String? = null, onSelected: (String) -> Unit) = settingsController.chooseFile(initialPath, onSelected)
    fun chooseApplication(initialPath: String? = null, onSelected: (String) -> Unit) =
        settingsController.chooseApplication(initialPath, onSelected)
    fun chooseDirectories(initialPath: String? = null, onSelected: (List<String>) -> Unit) = settingsController.chooseDirectories(initialPath, onSelected)
    fun loadRemoteBranches(repositoryId: String, remote: String = "origin", force: Boolean = false) =
        settingsController.loadRemoteBranches(repositoryId, remote, force)
    fun remoteBranchState(repositoryId: String, remote: String) = settingsController.remoteBranchState(repositoryId, remote)
    fun loadRepositoryRemotes(repositoryId: String, force: Boolean = false) = settingsController.loadRepositoryRemotes(repositoryId, force)
    fun repositoryRemotesState(repositoryId: String) = settingsController.repositoryRemotesState(repositoryId)
    val meegleProjectCatalogState: MeegleProjectCatalogState get() = settingsController.state.meegleProjects
    val meegleCliState: MeegleCliState get() = settingsController.state.meegleCli
    val localGitSettingsState: LocalGitSettingsState get() = settingsController.state.localGit
    fun settingsSaveState(key: String): SettingsSaveState = settingsController.saveState(key)
    fun refreshLocalGit(force: Boolean = false) = settingsController.refreshLocalGit(force)
    fun loadMeegleProjects(force: Boolean = false) = settingsController.loadMeegleProjects(force)
    fun cancelMeegleProjectLoad() = settingsController.cancelMeegleProjectLoad()
    fun refreshMeegleStatus(force: Boolean = false) = settingsController.refreshMeegleStatus(force)
    fun loginMeegle() = settingsController.loginMeegle()
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
    val agentTaskTemplates: List<AgentTaskTemplate> get() = agentInstructionsController.state.templates
    fun saveAgentTaskTemplate(id: String?, name: String, content: String) = agentInstructionsController.saveTemplate(id, name, content)
    fun deleteAgentTaskTemplate(id: String) = agentInstructionsController.deleteTemplate(id)
    fun readGlobalAgents(): String = agentInstructionsController.readGlobal()
    fun saveGlobalAgents(content: String) = agentInstructionsController.saveGlobal(content)
    fun markGlobalAgentsEdited(content: String) = agentInstructionsController.markGlobalEdited(content)
    fun readGroupAgents(groupId: String): String = agentInstructionsController.readGroup(groupId)
    fun saveGroupAgents(groupId: String, content: String) = agentInstructionsController.saveGroup(groupId, content)
    fun markGroupAgentsEdited(groupId: String, content: String) = agentInstructionsController.markGroupEdited(groupId, content)
    fun previewAgents(
        folderName: String,
        branch: String,
        groupId: String,
        serviceIds: Set<String>,
        requirementLink: String,
        notes: String,
        serviceSelections: List<TaskServiceSelection> = emptyList(),
    ): String = agentInstructionsController.preview(
        folderName,
        branch,
        groupId,
        serviceIds,
        requirementLink,
        notes,
        serviceSelections,
    )
    fun previewTaskAgents(task: TaskManifest, notes: String): String = agentInstructionsController.previewTask(task, notes)
    fun createTask(
        folderName: String,
        branch: String,
        groupId: String,
        serviceIds: List<String>,
        requirementLink: String,
        notes: String,
        workspaceToolIds: List<String> = emptyList(),
        confirmedBranchReuseKeys: Set<BranchReuseKey> = emptySet(),
        serviceSelections: List<TaskServiceSelection> = emptyList(),
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
        serviceSelections,
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

    fun retryRequirementMaterials(task: TaskManifest, onCompleted: () -> Unit = {}) =
        taskController.retryRequirementMaterials(task, onCompleted)

    fun buildTag(task: TaskManifest, workspace: ServiceWorkspace) = deliveryController.build(task, workspace)
    fun buildTags(task: TaskManifest, workspaces: List<ServiceWorkspace>, onCompleted: () -> Unit = {}) =
        deliveryController.buildBatch(task, workspaces, onCompleted)
    fun clearBatchTagResults() = deliveryController.clearBatchResults()

    fun addServices(
        task: TaskManifest,
        serviceIds: List<String>,
        confirmedBranchReuseKeys: Set<BranchReuseKey> = emptySet(),
        serviceSelections: List<TaskServiceSelection> = emptyList(),
        onCompleted: () -> Unit = {},
    ) = taskController.addServices(task, serviceIds, confirmedBranchReuseKeys, serviceSelections, onCompleted)

    fun retryFailedServices(task: TaskManifest, serviceIds: List<String>? = null) = taskController.retry(task, serviceIds)

    fun branchInfo(task: TaskManifest): String = TaskBranchInfoFormatter.format(task)

    fun branchInfo(task: TaskManifest, includeRequirementLink: Boolean): String =
        TaskBranchInfoFormatter.formatBranchInfo(task, includeRequirementLink)

    fun branchServices(task: TaskManifest, includeRequirementLink: Boolean): String =
        TaskBranchInfoFormatter.formatServices(task, includeRequirementLink)

    fun openWorkData(task: TaskManifest, type: com.snowball.awm.core.DevelopmentToolType = config.defaultDevelopmentTool) =
        desktopActions.openWorkData(taskDirectory(task), type)

    fun configuredDevelopmentTools(): List<com.snowball.awm.core.DevelopmentToolType> = config.developmentTools.map { it.type }

    fun defaultCommitMessage(task: TaskManifest, workspace: ServiceWorkspace) = taskController.defaultCommitMessage(task, workspace)
    fun commitWorkspace(task: TaskManifest, workspace: ServiceWorkspace, message: String, pushAfter: Boolean = false, expectedFingerprint: String? = null) =
        taskController.commit(task, workspace, message, pushAfter, expectedFingerprint)
    fun pushWorkspace(task: TaskManifest, workspace: ServiceWorkspace) = taskController.push(task, workspace)
    fun physicalWorkspaces(task: TaskManifest) = taskController.physicalWorkspaces(task)
    fun workspaceKey(workspace: ServiceWorkspace) = taskController.workspaceKey(workspace)
    fun batchGit(
        task: TaskManifest,
        mode: WorkspaceGitBatchMode,
        selectedWorkspaceKeys: Set<String>,
        commitMessages: Map<String, String> = emptyMap(),
        expectedFingerprints: Map<String, String> = emptyMap(),
        onCompleted: (WorkspaceGitBatchResult) -> Unit,
    ) = taskController.batchGit(task, mode, selectedWorkspaceKeys, commitMessages, expectedFingerprints, onCompleted)
    fun loadBatchGitPreviews(task: TaskManifest) = taskController.loadBatchGitPreviews(task)

    fun clearTagResult() = deliveryController.clearResult()

    fun openWorkspace(workspace: ServiceWorkspace, type: com.snowball.awm.core.DevelopmentToolType = workspace.developmentTool) =
        desktopActions.openWorkspace(workspace, type)

    fun testDevelopmentTool(type: com.snowball.awm.core.DevelopmentToolType, path: String) {
        val target = config.taskRoot?.let(Path::of)?.takeIf(java.nio.file.Files::isDirectory)
        if (target == null) {
            showError(IllegalStateException("请先配置有效的任务根目录，再测试打开开发工具"))
            return
        }
        runCatching { desktopIntegration.openDevelopmentTool(target, type, path) }.onFailure(::showError)
    }

    fun refreshErrorLog() {
        recentErrors = errorLogReader.latest()
    }

    fun openLogDirectory() {
        runCatching {
            java.nio.file.Files.createDirectories(paths.logs)
            desktopIntegration.openDirectory(paths.logs)
        }.onFailure(::showError)
    }

    fun configBackups(): List<ConfigStore.Backup> = runCatching { configStore.backups() }.getOrDefault(emptyList())
    fun previewConfigImport(path: String): ConfigStore.ImportPreview = configStore.previewImport(Path.of(path))

    fun configurationRecoveryGuidance(): String = recoveryGuidance(
        subject = "系统主配置文件",
        path = configFileSnapshot.path.toString(),
        reason = configurationLoadError.orEmpty(),
    )

    fun taskManifestRecoveryGuidance(issue: TaskManifestIssue): String = recoveryGuidance(
        subject = "任务清单",
        path = issue.manifestPath,
        reason = issue.reason,
    )

    /** Refreshes the raw, read-only configuration preview without parsing or writing it. */
    fun refreshConfigFileSnapshot() {
        if (configFileSnapshotRefreshing) return
        configFileSnapshotRefreshing = true
        scope.launch {
            try {
                configFileSnapshot = withContext(ioDispatcher) { configStore.fileSnapshot() }
            } finally {
                configFileSnapshotRefreshing = false
            }
        }
    }

    fun revealConfigFile() {
        if (!configFileSnapshot.exists) {
            showError(IllegalStateException("主配置文件尚未创建：${configFileSnapshot.path}"))
            return
        }
        desktopActions.reveal(configFileSnapshot.path)
    }

    /** Re-scans the task root after a user repairs a task manifest outside AWM. */
    fun refreshTaskManifestIssues() = reloadTasks()

    fun restoreConfigBackup(path: String): Boolean = operationRunner.run(
        "正在恢复配置备份…",
        "配置备份已恢复",
        block = { configStore.restore(Path.of(path)) },
        onSuccess = { applyConfig(it); reloadTasks() },
    )

    fun importConfig(path: String): Boolean = operationRunner.run(
        "正在导入配置…",
        "配置已导入",
        block = { configStore.importFrom(Path.of(path)) },
        onSuccess = { applyConfig(it); reloadTasks() },
    )

    fun exportConfig(): Boolean = operationRunner.run(
        "正在导出配置…",
        "配置已导出",
        block = { configStore.exportTo(paths.backups.resolve("config-export-${System.currentTimeMillis()}.json")) },
        onSuccess = { desktopIntegration.reveal(it) },
    )

    fun exportDiagnostics(): Boolean = operationRunner.run(
        "正在生成诊断包…",
        "诊断包已生成",
        cancellable = true,
        block = { diagnosticsExporter.export(config, configurationLoadError ?: taskScanWarning) },
        onSuccess = { desktopIntegration.reveal(it) },
    )

    fun reveal(path: String) = desktopActions.reveal(Path.of(path))
    fun openDirectory(path: String) = desktopActions.openDirectory(Path.of(path))

    fun revealGlobalAgents() = agentInstructionsController.revealGlobal()
    fun revealGroupAgents(groupId: String) = agentInstructionsController.revealGroup(groupId)
    fun terminal(path: String) = desktopActions.terminal(Path.of(path))
    fun openUrl(url: String) = desktopActions.openUrl(url)
    fun copyText(text: String, message: String = "已复制") = desktopActions.copy(text, message)

    /** Called from Window.onFocusEvent as the inexpensive external-file fallback. */
    fun onWindowFocused() {
        agentInstructionsController.onWindowFocused()
        refreshConfigFileSnapshot()
    }
    fun resolveAgentConflict(resolution: AgentConflictResolution) = agentInstructionsController.resolveConflict(resolution)
    fun dismissMessages() {
        operationCoordinator.dismiss()
    }

    fun showError(error: Throwable) {
        operationCoordinator.errorMessage = OperationFailureDetails.format(error)
        recordError(error)
    }

    private fun recordError(error: Throwable) {
        events.error(
            event = "application.error",
            message = OperationFailureDetails.format(error),
            metadata = mapOf("exception" to (error::class.qualifiedName ?: error::class.simpleName.orEmpty())),
        )
        recentErrors = errorLogReader.latest()
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
        val issues = buildList {
            scan.unsupportedDirectories.forEach { directory ->
                add(
                    TaskManifestIssue(
                        manifestPath = directory.resolve(ManifestStore.FILE_NAME).toAbsolutePath().normalize().toString(),
                        reason = scan.unsupportedReasons[directory] ?: "任务清单版本与当前 AWM 不兼容",
                    ),
                )
            }
            scan.failures.forEach { (directory, reason) ->
                add(
                    TaskManifestIssue(
                        manifestPath = directory.resolve(ManifestStore.FILE_NAME).toAbsolutePath().normalize().toString(),
                        reason = reason,
                    ),
                )
            }
        }
        val messages = buildList {
            if (scan.unsupportedDirectories.isNotEmpty()) {
                add("已忽略 ${scan.unsupportedDirectories.size} 个非 AWM 0.10.x 任务目录")
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
            manifestIssues = issues,
        )
    }

    private fun reloadTasks(preferredFolder: String? = selectedTask?.folderName) {
        val loaded = scanTasks(config)
        taskScanWarning = loaded.warning
        taskManifestIssues = loaded.manifestIssues
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
        if (navigation == NavigationItem.TAG && !TagNavigationPolicy.isVisible(updated)) {
            navigation = NavigationItem.TASKS
        }
        repositories = updated.repositories.map(RepositoryConfig::toInfo)
        if (requirementConfigurationChanged) requirementController.onConfigurationChanged()
        updated.groups.forEach { group ->
            runCatching {
                agentDocuments.ensureGroupFile(group.id)
                agentMonitor.track(paths.groupAgents(group.id))
            }.onFailure(::showError)
        }
        refreshConfigFileSnapshot()
    }

    private fun showStatus(message: String) {
        operationCoordinator.statusMessage = message
        operationCoordinator.errorMessage = null
    }

    private fun recoveryGuidance(subject: String, path: String, reason: String): String = buildString {
        appendLine("$subject 加载失败")
        appendLine("文件：$path")
        if (reason.isNotBlank()) appendLine("原因：$reason")
        append("请先备份该文件；确认无需保留时，可在文件管理器中手动删除它，然后重新打开 AWM。")
    }

}
