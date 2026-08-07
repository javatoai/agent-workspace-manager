package com.snowball.taskwt.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.snowball.taskwt.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

enum class NavigationItem(
    val title: String,
    val subtitle: String,
) {
    DASHBOARD("工作台", "Dashboard"),
    TASKS("研发任务", "Tasks"),
    SERVICES("服务仓库", "Services"),
    UAT("UAT 构建", "UAT Builds"),
    SETTINGS("设置", "Settings"),
}

sealed interface PendingBranchReuse {
    val conflicts: List<BranchConflict>

    data class Create(
        val request: CreateTaskRequest,
        override val conflicts: List<BranchConflict>,
    ) : PendingBranchReuse

    data class AddServices(
        val task: TaskManifest,
        val request: AddServicesRequest,
        override val conflicts: List<BranchConflict>,
    ) : PendingBranchReuse
}

class AppController(
    private val paths: ApplicationPaths = ApplicationPaths.systemDefault(),
    private val configStore: ConfigStore = ConfigStore(paths),
    private val scanner: RepositoryScanner = RepositoryScanner(),
    private val manifests: ManifestStore = ManifestStore(),
    private val taskManager: TaskManager = TaskManager(events = JsonlEventSink(paths)),
    private val tagBuildService: TagBuildService = TagBuildService(paths = paths),
    private val requirementInfoClient: RequirementInfoClient = FeishuRequirementInfoClient(),
    private val desktopIntegration: DesktopIntegration = DesktopIntegration(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val initialConfigResult = runCatching { configStore.load() }

    var config by mutableStateOf(initialConfigResult.getOrDefault(AppConfig()))
        private set
    var repositories by mutableStateOf<List<RepositoryInfo>>(emptyList())
        private set
    var tasks by mutableStateOf<List<TaskManifest>>(emptyList())
        private set
    var navigation by mutableStateOf(NavigationItem.DASHBOARD)
    var selectedTask by mutableStateOf<TaskManifest?>(null)
    var busy by mutableStateOf(false)
        private set
    var statusMessage by mutableStateOf<String?>(null)
        private set
    var errorMessage by mutableStateOf(
        initialConfigResult.exceptionOrNull()?.let {
            "配置文件读取失败：${it.message}。请检查 ${ApplicationPaths.systemDefault().config}"
        },
    )
        private set
    var tagResult by mutableStateOf<TagOperation?>(null)
        private set
    var batchSelectionTask by mutableStateOf<TaskManifest?>(null)
        private set
    var batchTagResults by mutableStateOf<List<TagOperation>?>(null)
        private set
    var requirementStatuses by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var requirementParticipants by mutableStateOf<Map<String, RequirementParticipants>>(emptyMap())
        private set
    var pendingBranchReuse by mutableStateOf<PendingBranchReuse?>(null)
        private set
    private val pendingRequirementStatusRefreshes = mutableSetOf<String>()

    val needsOnboarding: Boolean
        get() = config.scanRoots.isEmpty() || config.taskRoot.isNullOrBlank()

    init {
        if (!needsOnboarding) refresh()
    }

    fun refresh() = runOperation(
        successMessage = "已完成刷新",
        block = {
            val latestConfig = configStore.load()
        val scanned = scanner.scan(
            latestConfig.scanRoots.map(Path::of),
            latestConfig.taskRoot?.let(Path::of),
        )
        val discoveredServices = latestConfig.services.toMutableMap()
        scanned.forEach { repository ->
            discoveredServices.putIfAbsent(
                repository.id,
                ServiceConfig(
                    repositoryId = repository.id,
                    displayName = repository.name,
                    ideType = guessIde(repository),
                ),
            )
        }
        val updatedConfig = latestConfig.copy(services = discoveredServices)
        configStore.save(updatedConfig)
        val taskList = updatedConfig.taskRoot
            ?.let(Path::of)
            ?.let(manifests::list)
            ?.map { it.second }
            ?.sortedByDescending { it.updatedAt }
            .orEmpty()
            Triple(updatedConfig, scanned, taskList)
        },
        onSuccess = { (updatedConfig, scanned, taskList) ->
            config = updatedConfig
            repositories = scanned
            tasks = taskList
            requirementStatuses = requirementStatuses.filterKeys { folderName ->
                taskList.any { it.folderName == folderName }
            }
            requirementParticipants = requirementParticipants.filterKeys { folderName ->
                taskList.any { it.folderName == folderName }
            }
            selectedTask = selectedTask?.let { previous ->
                taskList.firstOrNull { it.folderName == previous.folderName }
            }
            refreshRequirementStatuses(taskList)
        },
    )

    fun selectTask(task: TaskManifest) {
        selectedTask = task
        refreshRequirementStatus(task)
    }

    fun refreshRequirementStatuses() {
        refreshRequirementStatuses(tasks)
    }

    private fun refreshRequirementStatuses(taskList: List<TaskManifest>) {
        taskList.forEach(::refreshRequirementStatus)
    }

    private fun refreshRequirementStatus(task: TaskManifest) {
        if (FeishuWorkItemLink.parse(task.requirementLink) == null) {
            requirementStatuses = requirementStatuses - task.folderName
            requirementParticipants = requirementParticipants - task.folderName
            return
        }
        if (!pendingRequirementStatusRefreshes.add(task.folderName)) return
        scope.launch {
            val info = withContext(Dispatchers.IO) {
                runCatching { requirementInfoClient.fetch(task.requirementLink) }.getOrNull()
            }
            pendingRequirementStatusRefreshes -= task.folderName
            if (tasks.none { it.folderName == task.folderName && it.requirementLink == task.requirementLink }) {
                return@launch
            }
            val status = info?.status
            requirementStatuses = if (status == null) {
                requirementStatuses - task.folderName
            } else {
                requirementStatuses + (task.folderName to status)
            }
            requirementParticipants = if (info == null || info.participants.isEmpty) {
                requirementParticipants - task.folderName
            } else {
                requirementParticipants + (task.folderName to info.participants)
            }
        }
    }

    fun completeOnboarding(scanRoot: String, taskRoot: String) {
        require(scanRoot.isNotBlank()) { "请选择服务扫描目录" }
        require(taskRoot.isNotBlank()) { "请选择任务工作区目录" }
        val scanPath = Path.of(scanRoot).toAbsolutePath().normalize()
        require(scanPath.toFile().isDirectory) { "服务扫描目录不存在：$scanPath" }
        val taskPath = Path.of(taskRoot).toAbsolutePath().normalize()
        require(taskPath.toFile().isDirectory || taskPath.toFile().mkdirs()) {
            "无法创建任务工作区目录：$taskPath"
        }
        val updated = config.copy(
            scanRoots = listOf(scanPath.toString()),
            taskRoot = taskPath.toString(),
        )
        configStore.save(updated)
        config = updated
        refresh()
    }

    fun addScanRoot(value: String) {
        val path = Path.of(value).toAbsolutePath().normalize()
        require(path.toFile().isDirectory) { "目录不存在：$path" }
        saveConfig(config.copy(scanRoots = (config.scanRoots + path.toString()).distinct()))
        refresh()
    }

    fun removeScanRoot(value: String) {
        saveConfig(config.copy(scanRoots = config.scanRoots - value))
        refresh()
    }

    fun updateTaskRoot(value: String) {
        val path = Path.of(value).toAbsolutePath().normalize()
        require(path.toFile().isDirectory || path.toFile().mkdirs()) {
            "无法创建任务工作区目录：$path"
        }
        saveConfig(config.copy(taskRoot = path.toString()))
        refresh()
    }

    fun updateIdeExecutables(idea: String, webStorm: String, terminal: String) {
        saveConfig(
            config.copy(
                ideaExecutable = idea.trim().ifBlank { null },
                webStormExecutable = webStorm.trim().ifBlank { null },
                terminalExecutable = terminal.trim().ifBlank { null },
            ),
        )
        showStatus("开发工具配置已保存")
    }

    fun setTheme(theme: ThemePreference) {
        saveConfig(config.copy(theme = theme))
    }

    fun updateAgentsMdAppendix(appendix: String) {
        saveConfig(config.copy(agentsMdAppendix = appendix))
        showStatus("AGENTS.md 模板追加已保存")
    }

    fun updateService(updated: ServiceConfig) {
        saveConfig(config.copy(services = config.services + (updated.repositoryId to updated)))
        showStatus("${updated.displayName} 配置已保存")
    }

    fun createTask(
        folderName: String,
        branch: String,
        repositoryIds: List<String>,
        requirementLink: String,
    ) {
        val request = CreateTaskRequest(folderName, branch, repositoryIds, requirementLink)
        inspectBranchConflicts(branch, repositoryIds) { conflicts ->
            if (conflicts.isEmpty()) {
                executeCreateTask(request)
            } else {
                pendingBranchReuse = PendingBranchReuse.Create(request, conflicts)
            }
        }
    }

    fun addServices(task: TaskManifest, repositoryIds: List<String>) {
        val request = AddServicesRequest(repositoryIds)
        inspectBranchConflicts(task.featureBranch, repositoryIds) { conflicts ->
            if (conflicts.isEmpty()) {
                executeAddServices(task, request.repositoryIds, request.reuseExistingBranchRepositoryIds)
            } else {
                pendingBranchReuse = PendingBranchReuse.AddServices(task, request, conflicts)
            }
        }
    }

    fun confirmBranchReuse(repositoryIds: Set<String>) {
        val pending = pendingBranchReuse ?: return
        pendingBranchReuse = null
        when (pending) {
            is PendingBranchReuse.Create -> executeCreateTask(
                pending.request.copy(reuseExistingBranchRepositoryIds = repositoryIds),
            )
            is PendingBranchReuse.AddServices -> executeAddServices(
                pending.task,
                pending.request.repositoryIds,
                repositoryIds,
            )
        }
    }

    fun cancelBranchReuse() {
        pendingBranchReuse = null
    }

    private fun executeCreateTask(
        request: CreateTaskRequest,
    ) =
        runOperation(
            successMessage = "任务已创建",
            block = {
                taskManager.create(
                    config,
                    repositories,
                    request,
                )
            },
            onSuccess = { manifest ->
                selectTask(manifest)
                reloadTasks()
                navigation = NavigationItem.TASKS
            },
        )

    private fun executeAddServices(
        task: TaskManifest,
        repositoryIds: List<String>,
        reuseExistingBranchRepositoryIds: Set<String> = emptySet(),
    ) =
        runOperation(
            successMessage = "服务已追加",
            block = {
                taskManager.addServices(
                    config,
                    repositories,
                    taskDirectory(task),
                    AddServicesRequest(repositoryIds, reuseExistingBranchRepositoryIds),
                )
            },
            onSuccess = { updated ->
                selectTask(updated)
                reloadTasks()
            },
        )

    fun archiveTask(task: TaskManifest, force: Boolean = false) =
        runOperation(
            successMessage = "任务已归档",
            block = { taskManager.archive(config, taskDirectory(task), repositories, force) },
            onSuccess = { updated ->
                selectTask(updated)
                reloadTasks()
            },
        )

    fun restoreTask(task: TaskManifest) =
        runOperation(
            successMessage = "任务已恢复",
            block = { taskManager.restore(config, taskDirectory(task), repositories) },
            onSuccess = { updated ->
                selectTask(updated)
                reloadTasks()
            },
        )

    fun inspectDeleteRisk(task: TaskManifest): List<DeleteRisk> =
        runCatching { taskManager.inspectDeleteRisk(taskDirectory(task)) }
            .getOrElse {
                showError(it)
                emptyList()
            }

    fun deleteTask(task: TaskManifest, forceDiscard: Boolean) =
        runOperation(
            successMessage = "任务已删除",
            block = {
                taskManager.delete(taskDirectory(task), forceDiscard)
            },
            onSuccess = {
                selectedTask = null
                reloadTasks()
            },
        )

    fun initializeTask(task: TaskManifest, failedOnly: Boolean) =
        runOperation(
            successMessage = { updated ->
                val warningServices = updated.services.filter { it.warnings.isNotEmpty() }
                if (warningServices.isEmpty()) {
                    "初始化步骤已完成"
                } else {
                    "初始化未完成：${warningServices.joinToString("、") { "${it.serviceName}（${it.warnings.size} 项警告）" }}"
                }
            },
            block = {
                taskManager.initialize(config, taskDirectory(task), repositories, failedOnly)
            },
            onSuccess = { updated ->
                selectTask(updated)
                reloadTasks()
            },
        )

    fun retryFailedServices(task: TaskManifest, repositoryIds: List<String>? = null) =
        runOperation(
            successMessage = "失败服务已重试",
            block = {
                taskManager.retryFailedServices(
                    config,
                    taskDirectory(task),
                    repositories,
                    repositoryIds,
                )
            },
            onSuccess = { updated ->
                selectTask(updated)
                reloadTasks()
            },
        )

    fun refreshAgentsMd(task: TaskManifest) =
        runOperation(
            successMessage = "AGENTS.md 已刷新",
            block = {
                taskManager.refreshAgentsMd(config, taskDirectory(task), repositories)
            },
            onSuccess = {},
        )

    fun openUrl(url: String) {
        runCatching { desktopIntegration.openUrl(url) }
            .onFailure(::showError)
    }

    fun copyPath(path: String) {
        copyText(path, "路径已复制")
    }

    fun copyText(text: String, successMessage: String = "已复制") {
        runCatching {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .setContents(java.awt.datatransfer.StringSelection(text), null)
        }
            .onSuccess { showStatus(successMessage) }
            .onFailure(::showError)
    }

    fun reveal(path: String) {
        runCatching { desktopIntegration.reveal(Path.of(path)) }
            .onFailure(::showError)
    }

    fun terminal(path: String) {
        runCatching { desktopIntegration.openTerminal(Path.of(path), config.terminalExecutable) }
            .onFailure(::showError)
    }

    fun openWorkspace(workspace: ServiceWorkspace) {
        val executable = when (workspace.ideType) {
            IdeType.IDEA -> config.ideaExecutable
            IdeType.WEBSTORM -> config.webStormExecutable
        }
        if (executable.isNullOrBlank()) {
            showError(
                IllegalStateException(
                    "尚未配置 ${if (workspace.ideType == IdeType.IDEA) "IDEA" else "WebStorm"} 可执行文件",
                ),
            )
            navigation = NavigationItem.SETTINGS
            return
        }
        val directory = Path.of(workspace.worktreePath)
        if (!directory.toFile().isDirectory) {
            showError(IllegalStateException("服务 Worktree 不存在：$directory"))
            return
        }
        runCatching { desktopIntegration.openIde(directory, executable) }
            .onFailure(::showError)
    }

    fun openAiData(task: TaskManifest) {
        val executable = config.ideaExecutable
        if (executable.isNullOrBlank()) {
            showError(IllegalStateException("尚未配置 IDEA 可执行文件"))
            navigation = NavigationItem.SETTINGS
            return
        }
        runOperation(
            successMessage = "已打开工作数据目录",
            block = {
                val directory = taskManager.ensureAiDataDirectory(taskDirectory(task))
                desktopIntegration.openIde(directory, executable)
            },
            onSuccess = {},
        )
    }

    fun buildTag(task: TaskManifest, repositoryId: String) =
        runOperation(
            successMessage = "Tag 操作已完成",
            block = {
                tagBuildService.build(config, taskDirectory(task), repositoryId)
            },
            onSuccess = { operation ->
                tagResult = operation
            },
        )

    fun showBatchSelection(task: TaskManifest) {
        batchSelectionTask = task
    }

    fun clearBatchSelection() {
        batchSelectionTask = null
    }

    fun buildTags(task: TaskManifest, repositoryIds: List<String>) =
        runOperation(
            successMessage = "批量 UAT Tag 操作已结束",
            block = {
                tagBuildService.buildBatch(config, taskDirectory(task), repositoryIds)
            },
            onSuccess = { results ->
                batchSelectionTask = null
                batchTagResults = results
            },
        )

    fun clearBatchTagResults() {
        batchTagResults = null
    }

    fun resumeTag(operation: TagOperation) {
        val task = tasks.firstOrNull { it.folderName == operation.folderName }
        if (task == null) {
            showError(IllegalStateException("任务已不存在：${operation.folderName}"))
            return
        }
        runOperation(
            successMessage = "Tag 操作恢复完成",
            block = {
                tagBuildService.resumePartial(
                    config,
                    taskDirectory(task),
                    operation.operationId,
                )
            },
            onSuccess = { tagResult = it },
        )
    }

    fun openOperationTask(operation: TagOperation) {
        val task = tasks.firstOrNull { it.folderName == operation.folderName }
        if (task == null) {
            showError(IllegalStateException("任务已不存在：${operation.folderName}"))
            return
        }
        selectedTask = task
        refreshRequirementStatus(task)
        navigation = NavigationItem.TASKS
    }

    fun clearTagResult() {
        tagResult = null
    }

    fun dismissMessages() {
        errorMessage = null
        statusMessage = null
    }

    private fun inspectBranchConflicts(
        featureBranch: String,
        repositoryIds: List<String>,
        onConflicts: (List<BranchConflict>) -> Unit,
    ) {
        if (busy) return
        busy = true
        dismissMessages()
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    taskManager.inspectBranchConflicts(config, repositories, featureBranch, repositoryIds)
                }
            }
                .onSuccess {
                    busy = false
                    onConflicts(it)
                }
                .onFailure {
                    busy = false
                    showError(it)
                }
        }
    }

    private fun reloadTasks() {
        tasks = config.taskRoot
            ?.let(Path::of)
            ?.let(manifests::list)
            ?.map { it.second }
            ?.sortedByDescending { it.updatedAt }
            .orEmpty()
        refreshRequirementStatuses(tasks)
    }

    private fun taskDirectory(task: TaskManifest): Path =
        Path.of(config.taskRoot ?: error("尚未配置任务根目录")).resolve(task.taskDirectoryName)

    private fun saveConfig(updated: AppConfig) {
        configStore.save(updated)
        config = updated
    }

    private fun guessIde(repository: RepositoryInfo): IdeType {
        val root = Path.of(repository.rootPath)
        return if (root.resolve("package.json").toFile().exists() &&
            !root.resolve("pom.xml").toFile().exists()
        ) {
            IdeType.WEBSTORM
        } else {
            IdeType.IDEA
        }
    }

    private fun <T> runOperation(
        successMessage: String,
        block: () -> T,
        onSuccess: (T) -> Unit,
    ) = runOperation(
        successMessage = { successMessage },
        block = block,
        onSuccess = onSuccess,
    )

    private fun <T> runOperation(
        successMessage: (T) -> String,
        block: () -> T,
        onSuccess: (T) -> Unit,
    ) {
        if (busy) return
        busy = true
        dismissMessages()
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess {
                    onSuccess(it)
                    showStatus(successMessage(it))
                }
                .onFailure(::showError)
            busy = false
        }
    }

    private fun showStatus(message: String) {
        statusMessage = message
        errorMessage = null
    }

    private fun showError(error: Throwable) {
        errorMessage = error.message ?: error::class.simpleName ?: "操作失败"
        statusMessage = null
    }

}
