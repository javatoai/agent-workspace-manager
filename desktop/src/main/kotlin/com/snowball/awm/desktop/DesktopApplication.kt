package com.snowball.awm.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.snowball.awm.core.AgentDocumentService
import com.snowball.awm.core.AgentConflictResolution
import com.snowball.awm.core.AgentFileChange
import com.snowball.awm.core.AgentFileMonitor
import com.snowball.awm.core.AgentDocumentPropagationService
import com.snowball.awm.core.AgentInstructionScope
import com.snowball.awm.core.AppConfig
import com.snowball.awm.core.AddGroupedTaskServicesRequest
import com.snowball.awm.core.ApplicationPaths
import com.snowball.awm.core.BatchRepositoryAddResult
import com.snowball.awm.core.ConfigStore
import com.snowball.awm.core.CreateGroupedTaskRequest
import com.snowball.awm.core.DeleteRisk
import com.snowball.awm.core.DesktopIntegration
import com.snowball.awm.core.MeegleRequirementMetadataProvider
import com.snowball.awm.core.MeegleProjectConfig
import com.snowball.awm.core.MeegleRequirementLinkSource
import com.snowball.awm.core.RequirementLinkCandidate
import com.snowball.awm.core.RequirementLinkFailure
import com.snowball.awm.core.RequirementLinkFailureLog
import com.snowball.awm.core.FeishuWorkItemLink
import com.snowball.awm.core.GitRepositoryInspector
import com.snowball.awm.core.GroupConfigurationService
import com.snowball.awm.core.GroupServiceConfig
import com.snowball.awm.core.GitRemoteBranchCatalog
import com.snowball.awm.core.IdeType
import com.snowball.awm.core.ManifestStore
import com.snowball.awm.core.RepositoryConfig
import com.snowball.awm.core.RepositoryInfo
import com.snowball.awm.core.RepositoryInspector
import com.snowball.awm.core.RemoteBranchCatalog
import com.snowball.awm.core.RemoteBranchRef
import com.snowball.awm.core.ModuleDisplayNaming
import com.snowball.awm.core.RequirementMetadataProvider
import com.snowball.awm.core.fetch
import com.snowball.awm.core.RequirementParticipants
import com.snowball.awm.core.ServiceWorkspace
import com.snowball.awm.core.TagBuildService
import com.snowball.awm.core.UatTagDeliveryAdapter
import com.snowball.awm.core.DeliveryTarget
import com.snowball.awm.core.DeliveryPipelineRegistry
import com.snowball.awm.core.TagOperation
import com.snowball.awm.core.TaskApplicationService
import com.snowball.awm.core.TaskManifest
import com.snowball.awm.core.TaskOperationLock
import com.snowball.awm.core.FileTaskOperationLock
import com.snowball.awm.core.TaskBranchNaming
import com.snowball.awm.core.TaskBranchInfoFormatter
import com.snowball.awm.core.TaskNaming
import com.snowball.awm.core.AwmTime
import com.snowball.awm.core.TaskWorkspaceToolAvailability
import com.snowball.awm.core.TaskWorkspaceToolDescriptor
import com.snowball.awm.core.TaskWorkspaceToolRegistry
import com.snowball.awm.core.ThemePreference
import com.snowball.awm.core.TagNavigationPolicy
import com.snowball.awm.core.WorkspaceStatus
import com.snowball.awm.core.WorkspaceStrategy
import com.snowball.awm.core.WorkspaceLayout
import com.snowball.awm.core.WorkspaceToolLaunchService
import com.snowball.awm.core.RequirementMetadata
import com.snowball.awm.core.WorkspaceGitHealth
import com.snowball.awm.core.WorkspaceGitHealthState
import com.snowball.awm.core.WorkspaceGitStatusService
import com.snowball.awm.core.GitWorkspaceGitStatusReader
import com.snowball.awm.core.toInfo
import com.snowball.awm.core.selectionKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists

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
    private val agentMonitor by lazy {
        AgentFileMonitor(onChange = { change ->
            scope.launch { handleAgentFileChange(change) }
        })
    }

    private val initialConfig = initial.getOrDefault(AppConfig())
    private val initialTasks = scanTasks(initialConfig)
    val sessionStore = AppSessionStore(initialConfig, initialTasks.manifests)
    val operationCoordinator = OperationCoordinator(
        initial.exceptionOrNull()?.let { "配置读取失败：${it.message}" } ?: initialTasks.warning,
    )
    val taskController = TaskController(this)
    val settingsController = SettingsController(this)
    val agentInstructionsController = AgentInstructionsController(this)
    val deliveryController = DeliveryController(this)

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
            val changed = sessionStore.navigation != value
            sessionStore.navigation = value
            if (changed && value in setOf(NavigationItem.TASKS, NavigationItem.ARCHIVED)) {
                refreshRequirementStatuses()
            }
        }
    var selectedTask: TaskManifest?
        get() = sessionStore.selectedTask
        private set(value) { sessionStore.selectedTask = value }
    val busy: Boolean get() = operationCoordinator.busy
    val activeOperation: String? get() = operationCoordinator.activeMessage
    val statusMessage: String? get() = operationCoordinator.statusMessage
    val errorMessage: String? get() = operationCoordinator.errorMessage
    var requirementStatuses by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var requirementParticipants by mutableStateOf<Map<String, RequirementParticipants>>(emptyMap())
        private set
    var tagResult by mutableStateOf<TagOperation?>(null)
        private set
    var batchTagResults by mutableStateOf<List<TagOperation>?>(null)
        private set
    var tagHistory by mutableStateOf(uatDelivery.historyOperations(config, tasks))
        private set
    var agentRevision by mutableStateOf(0L)
        private set
    var agentConflict by mutableStateOf<AgentFileChange.Conflict?>(null)
        private set
    var deleteRiskInspections by mutableStateOf<Map<String, DeleteRiskInspection>>(emptyMap())
        private set
    var pathPickerBusy by mutableStateOf(false)
        private set
    var remoteBranches by mutableStateOf<Map<String, RemoteBranchesState>>(emptyMap())
        private set
    var repositoryAddResult by mutableStateOf<BatchRepositoryAddResult?>(null)
        private set
    var workspaceGitHealth by mutableStateOf<Map<String, WorkspaceGitHealth>>(emptyMap())
        private set
    private var gitStatusRevision = 0L
    private var metadataJob: Job? = null
    private var metadataRequestRevision = 0L
    var requirementLinkCandidates by mutableStateOf<List<RequirementLinkCandidate>>(emptyList())
        private set
    var requirementLinksLoading by mutableStateOf(false)
        private set

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
        workspaceGitHealth[normalizedWorkspacePath(workspace)]

    fun requestRequirementMetadata(link: String, onResult: (RequirementMetadata?) -> Unit) {
        val revision = ++metadataRequestRevision
        metadataJob?.cancel()
        val workItem = FeishuWorkItemLink.parse(link)
        if (workItem == null) {
            // Plain-text references are allowed. Clear the UI state immediately
            // because no local CLI lookup will run for them.
            onResult(null)
            return
        }
        metadataJob = scope.launch {
            delay(250)
            val metadata = withContext(ioDispatcher) {
                runCatching { requirementMetadataProvider.fetch(link, configuredProjectKey(workItem)) }.getOrNull()
            }
            if (revision == metadataRequestRevision) onResult(metadata)
        }
    }

    /** Saves the configured Feishu project identities without enabling any automatic query. */
    fun updateMeegleProjects(projects: List<MeegleProjectConfig>): Boolean {
        val current = config
        return configurationMutation("正在保存飞书需求配置…", "飞书需求配置已保存") {
            configStore.save(current.copy(meegleProjects = projects))
            configStore.load()
        }
    }

    /**
     * Loads configured Feishu links once per create-task dialog.  The project
     * list is the explicit opt-in, so no separate on/off switch is needed.
     */
    fun loadAutoRequirementLinks() {
        if (config.meegleProjects.isEmpty() || requirementLinksLoading) return
        requirementLinksLoading = true
        scope.launch {
            try {
                val result = withContext(ioDispatcher) { requirementLinkSource.load(config.meegleProjects) }
                result.failures.forEach(requirementLinkFailures::record)
                requirementLinkCandidates = result.candidates
            } catch (error: Exception) {
                // Loading failures must not leave the create dialog permanently busy.
                // Details stay in the local diagnostic log rather than surfacing raw CLI output.
                requirementLinkFailures.record(
                    RequirementLinkFailure(
                        stage = "desktop-load",
                        message = error.message ?: error::class.simpleName.orEmpty(),
                    ),
                )
            } finally {
                requirementLinksLoading = false
            }
        }
    }

    fun refreshCurrentTaskGitStatus() {
        val task = selectedTask ?: run {
            workspaceGitHealth = emptyMap()
            return
        }
        val revision = ++gitStatusRevision
        val paths = task.services.map(::normalizedWorkspacePath).distinct()
        workspaceGitHealth = paths.associateWith { WorkspaceGitHealth(WorkspaceGitHealthState.CHECKING) }
        scope.launch {
            val inspected = withContext(ioDispatcher) { gitStatusService.inspect(task.services) }
                .mapKeys { (path, _) -> path.toString() }
            if (revision == gitStatusRevision && selectedTask?.taskDirectoryName == task.taskDirectoryName) {
                workspaceGitHealth = inspected
            }
        }
    }

    fun addableServices(task: TaskManifest): List<GroupServiceConfig> {
        val existing = task.services.map(ServiceWorkspace::groupServiceId).toSet()
        return config.group(task.groupId).services.filter { it.enabled && it.id !in existing }
    }

    fun canBuildTag(task: TaskManifest, workspace: ServiceWorkspace): Boolean {
        val group = config.groups.firstOrNull { it.id == task.groupId } ?: return false
        if (!group.uatTagEnabled || workspace.status == WorkspaceStatus.FAILED) {
            return false
        }
        val service = group.services.firstOrNull { it.id == workspace.groupServiceId } ?: return false
        return when (service.strategy) {
            WorkspaceStrategy.STANDARD_WORKTREE -> service.modules
                .firstOrNull { it.id == workspace.moduleId }
                ?.uatTagEnabled == true
            WorkspaceStrategy.INDEPENDENT_CLONE -> service.cloneModules
                .firstOrNull { it.id == workspace.moduleId }
                ?.uatTagEnabled == true
        }
    }

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
    fun refresh() = runOperation("正在刷新状态…", "状态刷新完成", block = {
        val latest = configStore.load()
        val validated = latest.repositories.map { persisted ->
            val inspected = repositoryInspector.inspect(Path.of(persisted.rootPath))
            require(samePathIdentity(persisted.gitCommonDirectory, inspected.gitCommonDirectory)) {
                "仓库身份已变化：${persisted.name} 当前指向 ${inspected.gitCommonDirectory}，" +
                    "与配置中的 ${persisted.gitCommonDirectory} 不一致"
            }
            inspected.copy(id = persisted.id)
        }
        val refreshedConfig = latest.copy(repositories = validated)
        configStore.save(refreshedConfig)
        val refreshedTasks = scanTasks(refreshedConfig)
        refreshedTasks.manifests.forEach { task ->
            tasksApplication.refreshAgents(refreshedConfig, taskDirectory(refreshedConfig, task))
        }
        refreshedConfig to refreshedTasks
    }, onSuccess = { (refreshedConfig, refreshedTasks) ->
        applySnapshot(refreshedConfig, refreshedTasks.manifests)
        refreshRequirementStatuses()
        refreshedTasks.warning?.let { showError(IllegalStateException(it)) }
    })

    fun selectTask(task: TaskManifest) {
        selectedTask = task
        refreshCurrentTaskGitStatus()
        refreshRequirementStatus(task)
    }

    fun setTheme(theme: ThemePreference) = mutateConfig("正在更新主题…", "主题已更新") { it.copy(theme = theme) }

    fun updateTaskRoot(value: String) = runOperation("正在保存任务根目录…", "任务根目录已保存", block = {
        val path = Path.of(value).toAbsolutePath().normalize()
        Files.createDirectories(path)
        config.copy(taskRoot = path.toString()).also(configStore::save)
    }, onSuccess = {
        applyConfig(it)
        reloadTasks()
    })

    fun updateExecutables(idea: String, webStorm: String, terminal: String) =
        mutateConfig("正在保存开发工具配置…", "开发工具配置已保存") {
            it.copy(
                ideaExecutable = idea.trim().ifBlank { null },
                webStormExecutable = webStorm.trim().ifBlank { null },
                terminalExecutable = terminal.trim().ifBlank { null },
            )
        }

    fun addGroup(name: String) = configurationMutation("正在创建组…", "组已创建") {
        groupConfigurations.addGroup(name)
    }

    fun renameGroup(groupId: String, name: String) = configurationMutation("正在重命名组…", "组已重命名") {
        groupConfigurations.renameGroup(groupId, name)
    }

    fun moveGroup(groupId: String, offset: Int) = configurationMutation("正在更新组顺序…", "组顺序已更新") {
        groupConfigurations.moveGroup(groupId, offset)
    }

    fun deleteGroup(groupId: String) = configurationMutation("正在删除空组…", "空组已删除") {
        require(tasks.none { it.groupId == groupId }) { "该组还有研发任务，不能删除" }
        groupConfigurations.deleteGroup(groupId)
    }

    fun setGroupTagEnabled(groupId: String, enabled: Boolean) =
        configurationMutation("正在更新组 Tag 开关…", "组 Tag 开关已更新") {
            groupConfigurations.setGroupTagEnabled(groupId, enabled)
        }

    fun updateGroupDefaults(groupId: String, branchPrefix: String, workspaceToolIds: List<String>) =
        configurationMutation("正在保存组默认配置…", "组默认配置已保存") {
            groupConfigurations.updateGroupDefaults(groupId, branchPrefix, workspaceToolIds)
        }

    /** Opens one native dialog at a time and leaves the field untouched on cancellation. */
    fun chooseDirectory(initialPath: String? = null, onSelected: (String) -> Unit) {
        choosePath(
            pick = { nativePathPicker.pickDirectory(initialPath) },
            onComplete = { selected -> selected?.let(onSelected) },
        )
    }

    fun chooseFile(initialPath: String? = null, onSelected: (String) -> Unit) {
        choosePath(
            pick = { nativePathPicker.pickFile(initialPath) },
            onComplete = { selected -> selected?.let(onSelected) },
        )
    }

    fun chooseDirectories(initialPath: String? = null, onSelected: (List<String>) -> Unit) {
        choosePath(
            pick = { nativePathPicker.pickDirectories(initialPath) },
            onComplete = { selected -> selected?.takeIf { it.isNotEmpty() }?.let(onSelected) },
        )
    }

    private fun <T> choosePath(pick: suspend () -> T?, onComplete: (T?) -> Unit) {
        if (pathPickerBusy) return
        pathPickerBusy = true
        scope.launch {
            runCatching { pick() }
                .onSuccess(onComplete)
                .onFailure(::showError)
            pathPickerBusy = false
        }
    }

    /** Loads one remote on demand and caches it by repository plus remote name. */
    fun loadRemoteBranches(repositoryId: String, remote: String = "origin", force: Boolean = false) {
        val key = "$repositoryId|$remote"
        val current = remoteBranches[key]
        if (!force && (current is RemoteBranchesState.Loading || current is RemoteBranchesState.Loaded)) return
        val repository = config.repositories.firstOrNull { it.id == repositoryId }
            ?: return showError(IllegalArgumentException("找不到仓库：$repositoryId"))
        remoteBranches = remoteBranches + (key to RemoteBranchesState.Loading)
        scope.launch {
            val result = withContext(ioDispatcher) {
                runCatching { remoteBranchCatalog.list(Path.of(repository.rootPath), remote) }
            }
            remoteBranches = remoteBranches + (key to result.fold(
                onSuccess = { RemoteBranchesState.Loaded(it) },
                onFailure = { RemoteBranchesState.Failed(it.message ?: "远程分支加载失败") },
            ))
        }
    }

    fun remoteBranchState(repositoryId: String, remote: String): RemoteBranchesState =
        remoteBranches["$repositoryId|$remote"] ?: RemoteBranchesState.Idle

    fun addRepository(groupId: String, selectedDirectory: String, strategy: WorkspaceStrategy) =
        runOperation("正在添加服务…", "服务已添加", block = {
            groupConfigurations.addRepository(groupId, Path.of(selectedDirectory), strategy)
        }, onSuccess = ::applyConfig)

    fun addRepositories(groupId: String, selectedDirectories: List<String>) =
        runOperation("正在批量添加仓库…", "仓库批量添加完成", block = {
            groupConfigurations.addRepositories(groupId, selectedDirectories.map(Path::of))
        }, onSuccess = { result ->
            applyConfig(result.config)
            repositoryAddResult = result
            val skipped = result.skipped.size
            showStatus("已添加 ${result.added.size} 个服务" + if (skipped > 0) "，跳过 $skipped 个目录" else "")
        })

    fun clearRepositoryAddResult() {
        repositoryAddResult = null
    }

    fun updateService(groupId: String, service: GroupServiceConfig) =
        configurationMutation("正在保存服务配置…", "服务配置已保存") {
            groupConfigurations.updateService(groupId, service)
        }

    fun moveService(groupId: String, serviceId: String, offset: Int) =
        configurationMutation("正在更新服务顺序…", "服务顺序已更新") {
            groupConfigurations.moveService(groupId, serviceId, offset)
        }

    fun removeService(groupId: String, serviceId: String) =
        configurationMutation("正在移除服务…", "服务已移除") {
            require(tasks.none { task ->
                task.groupId == groupId && task.services.any { it.groupServiceId == serviceId }
            }) { "该服务仍被研发任务引用，不能从组内移除" }
            groupConfigurations.removeService(groupId, serviceId)
        }

    fun readGlobalAgents(): String = runCatching { agentMonitor.track(paths.globalAgents).content }.getOrElse {
        showError(it); ""
    }

    fun saveGlobalAgents(content: String) = runOperation("正在保存全局 AGENTS.md…", "全局 AGENTS.md 已保存", block = {
        requireNoReservedAgentMarkers(content)
        agentMonitor.save(paths.globalAgents, content)
        requirePropagationSucceeded(agentPropagation.propagate(config, AgentInstructionScope.Global).failures)
    })

    fun markGlobalAgentsEdited(content: String) = agentMonitor.markLocalEdit(paths.globalAgents, content)

    fun readGroupAgents(groupId: String): String = runCatching {
        agentMonitor.track(paths.groupAgents(groupId)).content
    }.getOrElse {
        showError(it); ""
    }

    fun saveGroupAgents(groupId: String, content: String) = runOperation("正在保存组 AGENTS.md…", "组 AGENTS.md 已保存", block = {
        requireNoReservedAgentMarkers(content)
        agentMonitor.save(paths.groupAgents(groupId), content)
        requirePropagationSucceeded(
            agentPropagation.propagate(config, AgentInstructionScope.Group(groupId)).failures,
        )
    })

    fun markGroupAgentsEdited(groupId: String, content: String) =
        agentMonitor.markLocalEdit(paths.groupAgents(groupId), content)

    fun previewAgents(
        folderName: String,
        branch: String,
        groupId: String,
        serviceIds: Set<String>,
        requirementLink: String,
        notes: String,
    ): String {
        val root = config.taskRoot?.let(Path::of) ?: paths.temp
        val normalizedName = folderName.ifBlank { "任务名称" }
        val normalizedBranch = branch.trim().ifBlank { "feature/example" }
        val directoryName = runCatching { TaskNaming.requireValidDirectoryName(normalizedName) }
            .getOrDefault("任务名称")
        val now = AwmTime.format(Instant.now())
        val taskDirectory = root.resolve(directoryName)
        val repositoriesById = config.repositories.associateBy(RepositoryConfig::id)
        val previewWorkspaces = config.group(groupId).services
            .filter { it.id in serviceIds }
            .flatMap { service ->
                val repository = repositoriesById[service.repositoryId] ?: return@flatMap emptyList()
                when (service.strategy) {
                    WorkspaceStrategy.STANDARD_WORKTREE -> {
                        val branches = runCatching { TaskBranchNaming.derive(normalizedBranch, service.modules) }
                            .getOrElse { service.modules.associate { it.id to normalizedBranch } }
                        val distinctBaseCount = service.modules.map(TaskBranchNaming::baseIdentity).distinct().size
                        service.modules.map { module ->
                            ServiceWorkspace(
                                repositoryId = repository.id,
                                serviceName = service.displayName,
                                repositoryPath = repository.rootPath,
                                worktreePath = taskDirectory.resolve(
                                    WorkspaceLayout.standardDirectoryName(service, module, distinctBaseCount),
                                ).toString(),
                                ideType = service.ideType,
                                branch = branches.getValue(module.id),
                                groupServiceId = service.id,
                                moduleId = module.id,
                                moduleName = com.snowball.awm.core.ModuleDisplayNaming.resolve(
                                    module.name, service.displayName, module.baseRef, service.modules.size,
                                ),
                                strategy = service.strategy,
                                baseRef = module.baseRef,
                            )
                        }
                    }
                    WorkspaceStrategy.INDEPENDENT_CLONE -> service.cloneModules.map { module ->
                        ServiceWorkspace(
                            repositoryId = repository.id,
                            serviceName = service.displayName,
                            repositoryPath = repository.rootPath,
                            worktreePath = taskDirectory.resolve(WorkspaceLayout.cloneDirectoryName(service, module)).toString(),
                            ideType = service.ideType,
                            branch = RemoteBranchRef.parse(module.branch).branch,
                            groupServiceId = service.id,
                            moduleId = module.id,
                            moduleName = ModuleDisplayNaming.resolve(module.name, service.displayName, module.branch, service.cloneModules.size),
                            strategy = service.strategy,
                            originUrl = repository.originUrl,
                            baseRef = module.branch,
                        )
                    }
                }
            }
        val preview = TaskManifest(
            folderName = normalizedName,
            taskDirectoryName = directoryName,
            featureBranch = normalizedBranch,
            requirementLink = requirementLink.trim(),
            createdAt = now,
            updatedAt = now,
            status = WorkspaceStatus.CREATING,
            services = previewWorkspaces,
            groupId = groupId,
        )
        return agentDocuments.renderPreview(taskDirectory, preview, repositories, notes)
    }

    fun createTask(
        folderName: String,
        branch: String,
        groupId: String,
        serviceIds: List<String>,
        requirementLink: String,
        notes: String,
        workspaceToolIds: List<String> = emptyList(),
    ) = runOperation("正在创建任务…", "任务已创建", block = {
        val created = tasksApplication.create(
            config,
            CreateGroupedTaskRequest(
                folderName = folderName,
                featureBranch = branch,
                groupId = groupId,
                serviceIds = serviceIds,
                requirementLink = requirementLink,
                taskNotes = notes,
            ),
        )
        val directory = taskDirectory(config, created)
        workspaceToolLaunchService.launch(directory, created, workspaceToolIds)
    }, onSuccess = { created ->
        reloadTasks(created.folderName)
        navigation = NavigationItem.TASKS
    })

    fun retryWorkspaceTool(task: TaskManifest, toolId: String) = runOperation("正在重新打开工作区工具…", "工作区工具已重新打开", block = {
        workspaceToolLaunchService.retry(taskDirectory(task), task, toolId)
    }, onSuccess = { updated -> reloadTasks(updated.folderName) })

    fun readTaskNotes(task: TaskManifest): String = runCatching {
        val file = taskDirectory(task).resolve("AGENTS.md")
        val document = agentMonitor.track(file).content
        if (document.isNotBlank()) agentDocuments.extractTaskNotes(document) else ""
    }.getOrElse { showError(it); "" }

    fun saveTaskNotes(task: TaskManifest, notes: String) = runOperation("正在保存任务说明…", "任务说明已保存", block = {
        requireNoReservedAgentMarkers(notes)
        val directory = taskDirectory(task)
        val path = directory.resolve("AGENTS.md")
        val current = agentMonitor.snapshot(path)?.content ?: agentMonitor.track(path).content
        // Let the monitor perform its disk-hash conflict check before the
        // application service regenerates the authoritative system region.
        agentMonitor.save(path, replaceTaskNotes(current, notes))
        tasksApplication.saveTaskNotes(config, directory, notes)
        agentMonitor.checkNow()
    })

    fun markTaskNotesEdited(task: TaskManifest, notes: String) {
        val path = taskDirectory(task).resolve("AGENTS.md")
        runCatching {
            requireNoReservedAgentMarkers(notes)
            val current = agentMonitor.snapshot(path)?.content ?: agentMonitor.track(path).content
            agentMonitor.markLocalEdit(path, replaceTaskNotes(current, notes))
        }.onFailure(::showError)
    }

    fun archiveTask(task: TaskManifest, force: Boolean = false) = runOperation("正在归档任务…", "任务已归档", block = {
        tasksApplication.archive(config, taskDirectory(task), force)
    }, onSuccess = { reloadTasks(it.folderName); navigation = NavigationItem.ARCHIVED })

    fun restoreTask(task: TaskManifest) = runOperation("正在恢复任务…", "任务已恢复", block = {
        tasksApplication.restore(config, taskDirectory(task))
    }, onSuccess = { reloadTasks(it.folderName); navigation = NavigationItem.TASKS })

    /** Runs Git safety checks away from Compose's event-dispatch thread. */
    fun requestDeleteRisk(task: TaskManifest) {
        val key = task.taskDirectoryName
        if (deleteRiskInspections[key]?.loading == true) return
        deleteRiskInspections = deleteRiskInspections + (key to DeleteRiskInspection())
        val configSnapshot = config
        scope.launch {
            val result = withContext(ioDispatcher) {
                runCatching {
                    tasksApplication.inspectDeleteRisk(configSnapshot, taskDirectory(configSnapshot, task))
                }
            }
            deleteRiskInspections = deleteRiskInspections + (
                key to result.fold(
                    onSuccess = { risks -> DeleteRiskInspection(loading = false, risks = risks) },
                    onFailure = { DeleteRiskInspection(loading = false, error = it.message ?: "删除风险检查失败") },
                )
            )
        }
    }

    fun clearDeleteRisk(task: TaskManifest) {
        deleteRiskInspections = deleteRiskInspections - task.taskDirectoryName
    }

    fun deleteTask(task: TaskManifest, forceDiscard: Boolean) =
        runOperation("正在删除任务…", "任务已删除", block = {
            tasksApplication.delete(config, taskDirectory(task), forceDiscard)
        }, onSuccess = { reloadTasks() })

    fun buildTag(task: TaskManifest, workspace: ServiceWorkspace) = runOperation("正在构建 UAT Tag…", "UAT Tag 操作已完成", block = {
        // A module key removes ambiguity when one repository contributes multiple workspaces.
        uatDelivery.executeTag(DeliveryTarget(config, taskDirectory(task), workspace.selectionKey))
    }, onSuccess = {
        tagResult = it
        reloadTagHistory()
        refreshCurrentTaskGitStatus()
    })

    fun buildTags(task: TaskManifest, workspaces: List<ServiceWorkspace>) =
        runOperation("正在批量构建 UAT Tag…", "批量 UAT Tag 操作已完成", block = {
            uatDelivery.executeBatch(config, taskDirectory(task), workspaces.map { it.selectionKey })
        }, onSuccess = {
            batchTagResults = it
            reloadTagHistory()
            refreshCurrentTaskGitStatus()
        })

    fun clearBatchTagResults() { batchTagResults = null }

    fun addServices(
        task: TaskManifest,
        serviceIds: List<String>,
    ) = runOperation("正在追加服务…", "服务已追加", block = {
        tasksApplication.addServices(
            config,
            taskDirectory(task),
            AddGroupedTaskServicesRequest(serviceIds),
        )
    }, onSuccess = { reloadTasks(it.folderName) })

    fun retryFailedServices(task: TaskManifest, serviceIds: List<String>? = null) =
        runOperation("正在重试失败服务…", "失败服务已重试", block = {
            tasksApplication.retryFailedServices(config, taskDirectory(task), serviceIds)
        }, onSuccess = { reloadTasks(it.folderName) })

    fun branchInfo(task: TaskManifest): String = TaskBranchInfoFormatter.format(task)

    fun openWorkData(task: TaskManifest) {
        val executable = config.ideaExecutable
        if (executable.isNullOrBlank()) {
            navigation = NavigationItem.SETTINGS
            showError(IllegalStateException("请先在设置中配置 IDEA 可执行文件"))
            return
        }
        runOperation("正在打开工作数据…", "已打开工作数据目录", block = {
            val directory = taskDirectory(task).resolve("ai-data")
            Files.createDirectories(directory)
            desktopIntegration.openIde(directory, executable)
        })
    }

    fun clearTagResult() { tagResult = null }

    fun openWorkspace(workspace: ServiceWorkspace) {
        val executable = when (workspace.ideType) {
            IdeType.IDEA -> config.ideaExecutable
            IdeType.WEBSTORM -> config.webStormExecutable
        }
        if (executable.isNullOrBlank()) {
            navigation = NavigationItem.SETTINGS
            showError(IllegalStateException("请先在设置中配置 IDE 可执行文件"))
            return
        }
        runCatching { desktopIntegration.openIde(Path.of(workspace.worktreePath), executable) }.onFailure(::showError)
    }

    fun reveal(path: String) = runCatching { desktopIntegration.reveal(Path.of(path)) }.onFailure(::showError)
    fun openDirectory(path: String) = runCatching { desktopIntegration.openDirectory(Path.of(path)) }.onFailure(::showError)

    fun revealGlobalAgents() = runCatching {
        desktopIntegration.reveal(agentDocuments.ensureGlobalFile())
    }.onFailure(::showError)

    fun revealGroupAgents(groupId: String) = runCatching {
        desktopIntegration.reveal(agentDocuments.ensureGroupFile(groupId))
    }.onFailure(::showError)
    fun terminal(path: String) = runCatching {
        desktopIntegration.openTerminal(Path.of(path), config.terminalExecutable)
    }.onFailure(::showError)
    fun openUrl(url: String) = runCatching { desktopIntegration.openUrl(url) }.onFailure(::showError)
    fun copyText(text: String, message: String = "已复制") = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }.onSuccess { showStatus(message) }.onFailure(::showError)

    /** Called from Window.onFocusEvent as the inexpensive external-file fallback. */
    fun onWindowFocused() {
        if (!busy) agentMonitor.checkNow()
    }

    fun resolveAgentConflict(resolution: AgentConflictResolution) {
        val conflict = agentConflict ?: return
        val task = tasks.firstOrNull {
            taskDirectory(it).resolve("AGENTS.md").toAbsolutePath().normalize() ==
                conflict.path.toAbsolutePath().normalize()
        }
        runOperation("正在处理 Agent 文件冲突…", "Agent 文件冲突已处理", block = {
            if (resolution == AgentConflictResolution.USE_LOCAL && task != null) {
                // Preserve only the authoritative human notes. The generated region
                // is rebuilt from the latest global/group files instead of restoring
                // an older local snapshot over a newer external update.
                val notes = agentDocuments.extractTaskNotes(conflict.localContent)
                agentMonitor.resolve(conflict.path, AgentConflictResolution.USE_DISK)
                tasksApplication.saveTaskNotes(config, taskDirectory(task), notes)
                agentMonitor.checkNow()
            } else {
                agentMonitor.resolve(conflict.path, resolution)
            }
        }, onSuccess = {
            agentConflict = null
            agentRevision++
            if (resolution == AgentConflictResolution.USE_LOCAL && task == null) {
                synchronizeTasksForAgentPath(conflict.path)
            }
        })
    }

    fun refreshRequirementStatuses() {
        tasks.mapNotNull { task -> FeishuWorkItemLink.parse(task.requirementLink)?.let { task to it } }.forEach { (task, workItem) ->
            scope.launch {
                val info = withContext(ioDispatcher) {
                    runCatching { requirementMetadataProvider.fetch(task.requirementLink, configuredProjectKey(workItem)) }.getOrNull()
                }
                if (tasks.none { it.folderName == task.folderName }) return@launch
                val status = info?.status
                requirementStatuses = if (status == null) {
                    requirementStatuses - task.folderName
                } else requirementStatuses + (task.folderName to status)
                requirementParticipants = if (info == null || info.participants.isEmpty) {
                    requirementParticipants - task.folderName
                } else requirementParticipants + (task.folderName to info.participants)
            }
        }
    }

    private fun refreshRequirementStatus(task: TaskManifest) {
        val workItem = FeishuWorkItemLink.parse(task.requirementLink) ?: return
        scope.launch {
            val info = withContext(ioDispatcher) { runCatching { requirementMetadataProvider.fetch(task.requirementLink, configuredProjectKey(workItem)) }.getOrNull() }
            if (tasks.none { it.folderName == task.folderName }) return@launch
            requirementParticipants = if (info == null || info.participants.isEmpty) requirementParticipants - task.folderName else requirementParticipants + (task.folderName to info.participants)
            val status = info?.status
            requirementStatuses = if (status == null) requirementStatuses - task.folderName else requirementStatuses + (task.folderName to status)
        }
    }

    /** Resolves a configured Meegle project key for arbitrary user-defined Feishu space names. */
    private fun configuredProjectKey(workItem: FeishuWorkItemLink): String? =
        config.meegleProjects.firstOrNull { project -> project.simpleName.equals(workItem.space, ignoreCase = true) }
            ?.projectKey
            ?: workItem.projectKey

    fun dismissMessages() {
        operationCoordinator.dismiss()
    }

    fun showError(error: Throwable) {
        operationCoordinator.errorMessage = error.message ?: error::class.simpleName ?: "操作失败"
    }

    override fun close() {
        metadataJob?.cancel()
        agentMonitor.close()
        scope.cancel()
    }

    private fun handleAgentFileChange(change: AgentFileChange) {
        when (change) {
            is AgentFileChange.Conflict -> agentConflict = change
            is AgentFileChange.Reloaded -> {
                agentRevision++
                synchronizeTasksForAgentPath(change.path)
            }
        }
    }

    private fun synchronizeTasksForAgentPath(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        val propagationScope = when {
            normalized == paths.globalAgents.toAbsolutePath().normalize() -> AgentInstructionScope.Global
            else -> config.groups.firstOrNull {
                paths.groupAgents(it.id).toAbsolutePath().normalize() == normalized
            }?.let { AgentInstructionScope.Group(it.id) } ?: return
        }
        // File synchronization is independent from button operations. It must not
        // be dropped merely because a Git operation currently owns the busy flag.
        scope.launch {
            val result = withContext(ioDispatcher) {
                runCatching {
                    requirePropagationSucceeded(agentPropagation.propagate(config, propagationScope).failures)
                }
            }
            result.onSuccess { showStatus("Agent 文件已从磁盘同步") }.onFailure(::showError)
        }
    }

    private fun replaceTaskNotes(document: String, notes: String): String {
        // Validate marker integrity before constructing a replacement. If markers
        // are damaged, AgentDocumentService throws and no disk write is attempted.
        agentDocuments.extractTaskNotes(document)
        val begin = document.indexOf(AgentDocumentService.TASK_NOTES_BEGIN) +
            AgentDocumentService.TASK_NOTES_BEGIN.length
        val end = document.indexOf(AgentDocumentService.TASK_NOTES_END)
        return buildString {
            append(document.substring(0, begin))
            appendLine()
            if (notes.isNotBlank()) appendLine(notes.trimEnd())
            append(document.substring(end))
        }
    }

    private fun requireNoReservedAgentMarkers(content: String) {
        val reserved = listOf(
            AgentDocumentService.GENERATED_BEGIN,
            AgentDocumentService.GENERATED_END,
            AgentDocumentService.TASK_NOTES_BEGIN,
            AgentDocumentService.TASK_NOTES_END,
        )
        val marker = reserved.firstOrNull(content::contains) ?: return
        require(false) { "内容不能包含 AWM 保留标记：$marker" }
    }

    private fun samePathIdentity(expected: String, actual: String): Boolean {
        val expectedPath = Path.of(expected).toAbsolutePath().normalize().toString()
        val actualPath = Path.of(actual).toAbsolutePath().normalize().toString()
        return if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            expectedPath.equals(actualPath, ignoreCase = true)
        } else {
            expectedPath == actualPath
        }
    }

    private fun requirePropagationSucceeded(failures: Map<Path, String>) {
        if (failures.isEmpty()) return
        error(
            "部分任务 AGENTS.md 同步失败：" + failures.entries.joinToString { (path, reason) ->
                "${path.fileName}（$reason）"
            },
        )
    }

    private fun taskDirectory(task: TaskManifest): Path = taskDirectory(config, task)

    private fun taskDirectory(config: AppConfig, task: TaskManifest): Path =
        Path.of(requireNotNull(config.taskRoot) { "尚未配置任务根目录" }).resolve(task.taskDirectoryName)

    private fun normalizedWorkspacePath(workspace: ServiceWorkspace): String =
        Path.of(workspace.worktreePath).toAbsolutePath().normalize().toString()

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
        tasks = loaded.manifests
        selectedTask = preferredFolder?.let { folder -> loaded.manifests.firstOrNull { it.folderName == folder } }
            ?: loaded.manifests.firstOrNull()
        reloadTagHistory()
        refreshCurrentTaskGitStatus()
        loaded.warning?.let { showError(IllegalStateException(it)) }
    }

    private fun reloadTagHistory() {
        tagHistory = uatDelivery.historyOperations(config, tasks)
    }

    private fun applySnapshot(updatedConfig: AppConfig, updatedTasks: List<TaskManifest>) {
        val selectedFolder = selectedTask?.folderName
        applyConfig(updatedConfig)
        tasks = updatedTasks.sortedByDescending(TaskManifest::updatedAt)
        selectedTask = selectedFolder?.let { folder -> tasks.firstOrNull { it.folderName == folder } }
            ?: tasks.firstOrNull()
        reloadTagHistory()
        refreshCurrentTaskGitStatus()
    }

    private fun applyConfig(updated: AppConfig) {
        config = updated
        configurationLoadError = null
        if (navigation == NavigationItem.UAT && !TagNavigationPolicy.isVisible(updated)) {
            navigation = NavigationItem.TASKS
        }
        repositories = updated.repositories.map(RepositoryConfig::toInfo)
        updated.groups.forEach { group ->
            runCatching { agentMonitor.track(paths.groupAgents(group.id)) }.onFailure(::showError)
        }
    }

    private fun mutateConfig(
        activeMessage: String,
        successMessage: String,
        transform: (AppConfig) -> AppConfig,
    ): Boolean =
        runOperation(activeMessage, successMessage, block = {
            configStore.save(transform(config))
            configStore.load()
        }, onSuccess = ::applyConfig)

    private fun configurationMutation(
        activeMessage: String,
        successMessage: String,
        block: () -> AppConfig,
    ): Boolean = runOperation(activeMessage, successMessage, block = block, onSuccess = ::applyConfig)

    private fun showStatus(message: String) {
        operationCoordinator.statusMessage = message
        operationCoordinator.errorMessage = null
    }

    private fun <T> runOperation(
        activeMessage: String,
        successMessage: String,
        block: () -> T,
        onSuccess: (T) -> Unit = {},
    ): Boolean {
        if (!operationCoordinator.begin(activeMessage)) {
            showError(IllegalStateException("另一个操作正在执行，请稍候"))
            return false
        }
        scope.launch {
            val result = withContext(ioDispatcher) { runCatching(block) }
            result.onSuccess {
                operationCoordinator.succeed(successMessage)
                onSuccess(it)
            }.onFailure(operationCoordinator::fail)
        }
        return true
    }
}
