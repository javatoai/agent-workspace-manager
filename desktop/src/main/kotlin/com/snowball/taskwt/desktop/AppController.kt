package com.snowball.taskwt.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.snowball.taskwt.core.AgentDocumentService
import com.snowball.taskwt.core.AgentConflictResolution
import com.snowball.taskwt.core.AgentFileChange
import com.snowball.taskwt.core.AgentFileMonitor
import com.snowball.taskwt.core.AgentDocumentPropagationService
import com.snowball.taskwt.core.AgentInstructionScope
import com.snowball.taskwt.core.AppConfig
import com.snowball.taskwt.core.ApplicationPaths
import com.snowball.taskwt.core.BatchRepositoryAddResult
import com.snowball.taskwt.core.ConfigStore
import com.snowball.taskwt.core.CreateGroupedTaskRequest
import com.snowball.taskwt.core.DeleteRisk
import com.snowball.taskwt.core.DesktopIntegration
import com.snowball.taskwt.core.FeishuRequirementInfoClient
import com.snowball.taskwt.core.FeishuWorkItemLink
import com.snowball.taskwt.core.GitRepositoryInspector
import com.snowball.taskwt.core.GroupConfigurationService
import com.snowball.taskwt.core.GroupServiceConfig
import com.snowball.taskwt.core.GitRemoteBranchCatalog
import com.snowball.taskwt.core.IdeType
import com.snowball.taskwt.core.ManifestStore
import com.snowball.taskwt.core.RepositoryConfig
import com.snowball.taskwt.core.RepositoryInfo
import com.snowball.taskwt.core.RepositoryInspector
import com.snowball.taskwt.core.RemoteBranchCatalog
import com.snowball.taskwt.core.RequirementInfoClient
import com.snowball.taskwt.core.RequirementParticipants
import com.snowball.taskwt.core.ServiceWorkspace
import com.snowball.taskwt.core.TagBuildService
import com.snowball.taskwt.core.TagOperation
import com.snowball.taskwt.core.TaskApplicationService
import com.snowball.taskwt.core.TaskManifest
import com.snowball.taskwt.core.TaskOperationLock
import com.snowball.taskwt.core.FileTaskOperationLock
import com.snowball.taskwt.core.TaskBranchNaming
import com.snowball.taskwt.core.TaskNaming
import com.snowball.taskwt.core.TaskWtTime
import com.snowball.taskwt.core.TaskWorkspaceToolAvailability
import com.snowball.taskwt.core.TaskWorkspaceToolDescriptor
import com.snowball.taskwt.core.TaskWorkspaceToolRegistry
import com.snowball.taskwt.core.ThemePreference
import com.snowball.taskwt.core.TagNavigationPolicy
import com.snowball.taskwt.core.WorkspaceStatus
import com.snowball.taskwt.core.WorkspaceStrategy
import com.snowball.taskwt.core.WorkspaceLayout
import com.snowball.taskwt.core.WorkspaceToolLaunchService
import com.snowball.taskwt.core.toInfo
import com.snowball.taskwt.core.selectionKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

/**
 * Desktop presentation controller. Compose only calls application services and
 * receives immutable state; Git, JSON and AGENTS.md file details stay in core.
 */
class AppController(
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
    private val tagBuildService: TagBuildService = TagBuildService(paths = paths),
    private val requirementInfoClient: RequirementInfoClient = FeishuRequirementInfoClient(),
    private val desktopIntegration: DesktopIntegration = DesktopIntegration(),
    private val nativePathPicker: NativePathPicker = FileKitNativePathPicker(),
    private val remoteBranchCatalog: RemoteBranchCatalog = GitRemoteBranchCatalog(),
    private val workspaceToolRegistry: TaskWorkspaceToolRegistry = TaskWorkspaceToolRegistry(
        listOf(CodexWorkspaceToolLauncher()),
    ),
    private val workspaceToolLaunchService: WorkspaceToolLaunchService = WorkspaceToolLaunchService(
        workspaceToolRegistry,
        manifests,
    ),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val initial = runCatching { configStore.load() }
    private val agentMonitor by lazy {
        AgentFileMonitor(onChange = { change ->
            scope.launch { handleAgentFileChange(change) }
        })
    }

    var config by mutableStateOf(initial.getOrDefault(AppConfig()))
        private set
    var repositories by mutableStateOf(config.repositories.map(RepositoryConfig::toInfo))
        private set
    private val initialTasks = scanTasks(config)
    var tasks by mutableStateOf(initialTasks.manifests)
        private set
    var navigation by mutableStateOf(NavigationItem.TASKS)
    var selectedTask by mutableStateOf(tasks.firstOrNull())
        private set
    var busy by mutableStateOf(false)
        private set
    var activeOperation by mutableStateOf<String?>(null)
        private set
    var statusMessage by mutableStateOf<String?>(null)
        private set
    var errorMessage by mutableStateOf(
        initial.exceptionOrNull()?.let { "配置读取失败：${it.message}" } ?: initialTasks.warning,
    )
        private set
    var requirementStatuses by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var requirementParticipants by mutableStateOf<Map<String, RequirementParticipants>>(emptyMap())
        private set
    var tagResult by mutableStateOf<TagOperation?>(null)
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

    init {
        // Register authoritative global/group files without invoking Git or a
        // remote integration. Subsequent external writes can then propagate
        // even if the user never opens the Settings editor.
        runCatching { agentMonitor.track(paths.globalAgents) }.onFailure(::showError)
        config.groups.forEach { group ->
            runCatching { agentMonitor.track(paths.groupAgents(group.id)) }.onFailure(::showError)
        }
    }

    /**
     * The only repository validation entry point. Startup deliberately does not
     * call this method, so opening the app never runs Git or Meegle commands.
     */
    fun refresh() = runOperation("手动刷新完成", block = {
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
    }

    fun setTheme(theme: ThemePreference) = mutateConfig("主题已更新") { it.copy(theme = theme) }

    fun updateTaskRoot(value: String) = runOperation("任务根目录已保存", block = {
        val path = Path.of(value).toAbsolutePath().normalize()
        Files.createDirectories(path)
        config.copy(taskRoot = path.toString()).also(configStore::save)
    }, onSuccess = {
        applyConfig(it)
        reloadTasks()
    })

    fun updateExecutables(idea: String, webStorm: String, terminal: String) =
        mutateConfig("开发工具配置已保存") {
            it.copy(
                ideaExecutable = idea.trim().ifBlank { null },
                webStormExecutable = webStorm.trim().ifBlank { null },
                terminalExecutable = terminal.trim().ifBlank { null },
            )
        }

    fun addGroup(name: String) = configurationMutation("组已创建") {
        groupConfigurations.addGroup(name)
    }

    fun renameGroup(groupId: String, name: String) = configurationMutation("组已重命名") {
        groupConfigurations.renameGroup(groupId, name)
    }

    fun moveGroup(groupId: String, offset: Int) = configurationMutation("组顺序已更新") {
        groupConfigurations.moveGroup(groupId, offset)
    }

    fun deleteGroup(groupId: String) = configurationMutation("空组已删除") {
        require(tasks.none { it.groupId == groupId }) { "该组还有研发任务，不能删除" }
        groupConfigurations.deleteGroup(groupId)
    }

    fun setGroupTagEnabled(groupId: String, enabled: Boolean) =
        configurationMutation("组 Tag 开关已更新") {
            groupConfigurations.setGroupTagEnabled(groupId, enabled)
        }

    fun updateGroupDefaults(groupId: String, branchPrefix: String, workspaceToolIds: List<String>) =
        configurationMutation("组默认配置已保存") {
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

    /** Loads origin heads on demand. No startup path calls this method. */
    fun loadRemoteBranches(repositoryId: String, force: Boolean = false) {
        val current = remoteBranches[repositoryId]
        if (!force && (current is RemoteBranchesState.Loading || current is RemoteBranchesState.Loaded)) return
        val repository = config.repositories.firstOrNull { it.id == repositoryId }
            ?: return showError(IllegalArgumentException("找不到仓库：$repositoryId"))
        remoteBranches = remoteBranches + (repositoryId to RemoteBranchesState.Loading)
        scope.launch {
            val result = withContext(ioDispatcher) {
                runCatching { remoteBranchCatalog.list(Path.of(repository.rootPath), "origin") }
            }
            remoteBranches = remoteBranches + (repositoryId to result.fold(
                onSuccess = { RemoteBranchesState.Loaded(it) },
                onFailure = { RemoteBranchesState.Failed(it.message ?: "远程分支加载失败") },
            ))
        }
    }

    fun addRepository(groupId: String, selectedDirectory: String, strategy: WorkspaceStrategy) =
        runOperation("服务已添加", block = {
            groupConfigurations.addRepository(groupId, Path.of(selectedDirectory), strategy)
        }, onSuccess = ::applyConfig)

    fun addRepositories(groupId: String, selectedDirectories: List<String>) =
        runOperation("仓库批量添加完成", block = {
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
        configurationMutation("服务配置已保存") {
            groupConfigurations.updateService(groupId, service)
        }

    fun moveService(groupId: String, serviceId: String, offset: Int) =
        configurationMutation("服务顺序已更新") {
            groupConfigurations.moveService(groupId, serviceId, offset)
        }

    fun removeService(groupId: String, serviceId: String) =
        configurationMutation("服务已移除") {
            require(tasks.none { task ->
                task.groupId == groupId && task.services.any { it.groupServiceId == serviceId }
            }) { "该服务仍被研发任务引用，不能从组内移除" }
            groupConfigurations.removeService(groupId, serviceId)
        }

    fun readGlobalAgents(): String = runCatching { agentMonitor.track(paths.globalAgents).content }.getOrElse {
        showError(it); ""
    }

    fun saveGlobalAgents(content: String) = runOperation("全局 AGENTS.md 已保存", block = {
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

    fun saveGroupAgents(groupId: String, content: String) = runOperation("组 AGENTS.md 已保存", block = {
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
        cloneOverrides: Map<String, String>,
        notes: String,
    ): String {
        val root = config.taskRoot?.let(Path::of) ?: paths.temp
        val normalizedName = folderName.trim().ifBlank { "任务名称" }
        val normalizedBranch = branch.trim().ifBlank { "feature/example" }
        val directoryName = runCatching { TaskNaming.directoryName(normalizedName) }.getOrDefault("preview")
        val now = TaskWtTime.format(Instant.now())
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
                                moduleName = com.snowball.taskwt.core.ModuleDisplayNaming.resolve(
                                    module.name, service.displayName, module.baseRef, service.modules.size,
                                ),
                                strategy = service.strategy,
                                tagEnabled = module.tagEnabled,
                                baseRef = module.baseRef,
                            )
                        }
                    }
                    WorkspaceStrategy.INDEPENDENT_CLONE -> listOf(
                        ServiceWorkspace(
                            repositoryId = repository.id,
                            serviceName = service.displayName,
                            repositoryPath = repository.rootPath,
                            worktreePath = taskDirectory.resolve(WorkspaceLayout.cloneDirectoryName(service)).toString(),
                            ideType = service.ideType,
                            branch = cloneOverrides[service.id].orEmpty().ifBlank {
                                service.cloneDefaultBranch.orEmpty()
                            },
                            groupServiceId = service.id,
                            moduleId = "clone",
                            moduleName = service.displayName,
                            strategy = service.strategy,
                            tagEnabled = service.cloneTagEnabled,
                            originUrl = repository.originUrl,
                        ),
                    )
                }
            }
        val preview = TaskManifest(
            folderName = normalizedName,
            taskDirectoryName = directoryName,
            featureBranch = normalizedBranch,
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
        cloneOverrides: Map<String, String>,
        notes: String,
        workspaceToolIds: List<String> = emptyList(),
    ) = runOperation("任务已创建", block = {
        val created = tasksApplication.create(
            config,
            CreateGroupedTaskRequest(
                folderName = folderName,
                featureBranch = branch,
                groupId = groupId,
                serviceIds = serviceIds,
                requirementLink = requirementLink,
                cloneBranchOverrides = cloneOverrides,
                taskNotes = notes,
            ),
        )
        val directory = taskDirectory(config, created)
        workspaceToolLaunchService.launch(directory, created, workspaceToolIds)
    }, onSuccess = { created ->
        reloadTasks(created.folderName)
        navigation = NavigationItem.TASKS
    })

    fun retryWorkspaceTool(task: TaskManifest, toolId: String) = runOperation("工作区工具已重新打开", block = {
        workspaceToolLaunchService.retry(taskDirectory(task), task, toolId)
    }, onSuccess = { updated -> reloadTasks(updated.folderName) })

    fun readTaskNotes(task: TaskManifest): String = runCatching {
        val file = taskDirectory(task).resolve("AGENTS.md")
        val document = agentMonitor.track(file).content
        if (document.isNotBlank()) agentDocuments.extractTaskNotes(document) else ""
    }.getOrElse { showError(it); "" }

    fun saveTaskNotes(task: TaskManifest, notes: String) = runOperation("任务说明已保存", block = {
        requireNoReservedAgentMarkers(notes)
        val directory = taskDirectory(task)
        operationLock.withLock(directory) {
            val path = directory.resolve("AGENTS.md")
            val current = agentMonitor.snapshot(path)?.content ?: agentMonitor.track(path).content
            agentMonitor.save(path, replaceTaskNotes(current, notes))
        }
    })

    fun markTaskNotesEdited(task: TaskManifest, notes: String) {
        val path = taskDirectory(task).resolve("AGENTS.md")
        runCatching {
            requireNoReservedAgentMarkers(notes)
            val current = agentMonitor.snapshot(path)?.content ?: agentMonitor.track(path).content
            agentMonitor.markLocalEdit(path, replaceTaskNotes(current, notes))
        }.onFailure(::showError)
    }

    fun refreshTaskAgents(task: TaskManifest) = runOperation("AGENTS.md 已重新生成", block = {
        tasksApplication.refreshAgents(config, taskDirectory(task))
    })

    fun archiveTask(task: TaskManifest, force: Boolean = false) = runOperation("任务已归档", block = {
        tasksApplication.archive(config, taskDirectory(task), force)
    }, onSuccess = { reloadTasks(it.folderName) })

    fun restoreTask(task: TaskManifest) = runOperation("任务已恢复", block = {
        tasksApplication.restore(config, taskDirectory(task))
    }, onSuccess = { reloadTasks(it.folderName) })

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
                    onSuccess = { DeleteRiskInspection(loading = false, risks = it) },
                    onFailure = { DeleteRiskInspection(loading = false, error = it.message ?: "删除风险检查失败") },
                )
            )
        }
    }

    fun clearDeleteRisk(task: TaskManifest) {
        deleteRiskInspections = deleteRiskInspections - task.taskDirectoryName
    }

    fun deleteTask(task: TaskManifest, forceDiscard: Boolean) = runOperation("任务已删除", block = {
        tasksApplication.delete(config, taskDirectory(task), forceDiscard)
    }, onSuccess = { reloadTasks() })

    fun buildTag(task: TaskManifest, workspace: ServiceWorkspace) = runOperation("UAT Tag 操作已完成", block = {
        // A module key removes ambiguity when one repository contributes multiple workspaces.
        tagBuildService.build(config, taskDirectory(task), workspace.selectionKey)
    }, onSuccess = { tagResult = it })

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
        runOperation("Agent 文件冲突已处理", block = {
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
        tasks.filter { FeishuWorkItemLink.parse(it.requirementLink) != null }.forEach { task ->
            scope.launch {
                val info = withContext(ioDispatcher) {
                    runCatching { requirementInfoClient.fetch(task.requirementLink) }.getOrNull()
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

    fun dismissMessages() {
        statusMessage = null
        errorMessage = null
    }

    fun showError(error: Throwable) {
        errorMessage = error.message ?: error::class.simpleName ?: "操作失败"
    }

    override fun close() {
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
        require(false) { "内容不能包含 TaskWT 保留标记：$marker" }
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

    private fun scanTasks(config: AppConfig): LoadedTasks {
        val root = config.taskRoot?.let(Path::of) ?: return LoadedTasks(emptyList())
        val scan = runCatching { manifests.scan(root) }.getOrElse { error ->
            return LoadedTasks(emptyList(), "任务目录扫描失败：${error.message ?: error::class.simpleName}")
        }
        val messages = buildList {
            if (scan.ignoredLegacyDirectories.isNotEmpty()) {
                add("已忽略 ${scan.ignoredLegacyDirectories.size} 个旧版任务；请参考旧数据迁移文档")
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
        loaded.warning?.let { showError(IllegalStateException(it)) }
    }

    private fun applySnapshot(updatedConfig: AppConfig, updatedTasks: List<TaskManifest>) {
        val selectedFolder = selectedTask?.folderName
        applyConfig(updatedConfig)
        tasks = updatedTasks.sortedByDescending(TaskManifest::updatedAt)
        selectedTask = selectedFolder?.let { folder -> tasks.firstOrNull { it.folderName == folder } }
            ?: tasks.firstOrNull()
    }

    private fun applyConfig(updated: AppConfig) {
        config = updated
        if (navigation == NavigationItem.UAT && !TagNavigationPolicy.isVisible(updated)) {
            navigation = NavigationItem.TASKS
        }
        repositories = updated.repositories.map(RepositoryConfig::toInfo)
        updated.groups.forEach { group ->
            runCatching { agentMonitor.track(paths.groupAgents(group.id)) }.onFailure(::showError)
        }
    }

    private fun mutateConfig(message: String, transform: (AppConfig) -> AppConfig): Boolean =
        runOperation(message, block = {
            transform(config).also(configStore::save)
        }, onSuccess = ::applyConfig)

    private fun configurationMutation(message: String, block: () -> AppConfig): Boolean =
        runOperation(message, block = block, onSuccess = ::applyConfig)

    private fun showStatus(message: String) {
        statusMessage = message
        errorMessage = null
    }

    private fun <T> runOperation(
        successMessage: String,
        activeMessage: String = "正在处理：$successMessage",
        block: () -> T,
        onSuccess: (T) -> Unit = {},
    ): Boolean {
        if (busy) {
            showError(IllegalStateException("另一个操作正在执行，请稍候"))
            return false
        }
        busy = true
        activeOperation = activeMessage
        scope.launch {
            val result = withContext(ioDispatcher) { runCatching(block) }
            busy = false
            activeOperation = null
            result.onSuccess {
                showStatus(successMessage)
                onSuccess(it)
            }.onFailure(::showError)
        }
        return true
    }
}
