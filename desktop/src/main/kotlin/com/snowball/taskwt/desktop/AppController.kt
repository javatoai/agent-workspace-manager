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

data class BatchTagPreflightEntry(
    val repositoryId: String,
    val serviceName: String,
    val preview: TagPreflight? = null,
    val error: String? = null,
)

data class BatchTagPreflight(
    val taskKey: String,
    val entries: List<BatchTagPreflightEntry>,
)

class AppController(
    private val paths: ApplicationPaths = ApplicationPaths.systemDefault(),
    private val configStore: ConfigStore = ConfigStore(paths),
    private val scanner: RepositoryScanner = RepositoryScanner(),
    private val manifests: ManifestStore = ManifestStore(),
    private val taskManager: TaskManager = TaskManager(events = JsonlEventSink(paths)),
    private val tagBuildService: TagBuildService = TagBuildService(paths = paths),
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
    var tagPreview by mutableStateOf<TagPreflight?>(null)
        private set
    var tagResult by mutableStateOf<TagOperation?>(null)
        private set
    var batchSelectionTask by mutableStateOf<TaskManifest?>(null)
        private set
    var batchTagPreview by mutableStateOf<BatchTagPreflight?>(null)
        private set
    var batchTagResults by mutableStateOf<List<TagOperation>?>(null)
        private set

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
            selectedTask = selectedTask?.let { previous ->
                taskList.firstOrNull { it.taskKey == previous.taskKey }
            }
        },
    )

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

    fun updateService(updated: ServiceConfig) {
        saveConfig(config.copy(services = config.services + (updated.repositoryId to updated)))
        showStatus("${updated.displayName} 配置已保存")
    }

    fun createTask(taskKey: String, branch: String, repositoryIds: List<String>) =
        runOperation(
            successMessage = "任务已创建",
            block = {
            taskManager.create(
                config,
                repositories,
                CreateTaskRequest(taskKey, branch, repositoryIds),
            )
            },
            onSuccess = { manifest ->
            selectedTask = manifest
            reloadTasks()
            navigation = NavigationItem.TASKS
            },
        )

    fun archiveTask(task: TaskManifest, force: Boolean = false) =
        runOperation(
            successMessage = "任务已归档",
            block = { taskManager.archive(taskDirectory(task), force) },
            onSuccess = { updated ->
                selectedTask = updated
                reloadTasks()
            },
        )

    fun restoreTask(task: TaskManifest) =
        runOperation(
            successMessage = "任务已恢复",
            block = { taskManager.restore(config, taskDirectory(task)) },
            onSuccess = { updated ->
                selectedTask = updated
                reloadTasks()
            },
        )

    fun initializeTask(task: TaskManifest, failedOnly: Boolean) =
        runOperation(
            successMessage = "初始化步骤已完成",
            block = { taskManager.initialize(config, taskDirectory(task), failedOnly) },
            onSuccess = { updated ->
                selectedTask = updated
                reloadTasks()
            },
        )

    fun copyPath(path: String) {
        runCatching { desktopIntegration.copyPath(Path.of(path)) }
            .onSuccess { showStatus("路径已复制") }
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

    fun openTask(task: TaskManifest, ideType: IdeType) {
        val executable = when (ideType) {
            IdeType.IDEA -> config.ideaExecutable
            IdeType.WEBSTORM -> config.webStormExecutable
        }
        if (executable.isNullOrBlank()) {
            showError(
                IllegalStateException(
                    "尚未配置 ${if (ideType == IdeType.IDEA) "IDEA" else "WebStorm"} 可执行文件",
                ),
            )
            navigation = NavigationItem.SETTINGS
            return
        }
        val directory = taskDirectory(task).resolve(
            when (ideType) {
                IdeType.IDEA -> "idea-${task.taskDirectoryName}"
                IdeType.WEBSTORM -> "webstorm-${task.taskDirectoryName}"
            },
        )
        if (!directory.toFile().isDirectory) {
            showError(IllegalStateException("开发工具工作区不存在：$directory"))
            return
        }
        runCatching { desktopIntegration.openIde(directory, executable) }
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

    fun preflightTag(task: TaskManifest, repositoryId: String) =
        runOperation(
            successMessage = "预检通过，请确认后构建",
            block = { tagBuildService.preflight(config, taskDirectory(task), repositoryId) },
            onSuccess = { preview -> tagPreview = preview },
        )

    fun buildTag(task: TaskManifest, repositoryId: String) =
        runOperation(
            successMessage = "Tag 操作已完成",
            block = {
                tagBuildService.build(config, taskDirectory(task), repositoryId, confirmed = true)
            },
            onSuccess = { operation ->
                tagPreview = null
                tagResult = operation
            },
        )

    fun showBatchSelection(task: TaskManifest) {
        batchSelectionTask = task
    }

    fun clearBatchSelection() {
        batchSelectionTask = null
    }

    fun preflightTags(task: TaskManifest, repositoryIds: List<String>) =
        runOperation(
            successMessage = "批量预检完成，请核对每个服务",
            block = {
                repositoryIds.map { repositoryId ->
                    val serviceName = task.services
                        .firstOrNull { it.repositoryId == repositoryId }
                        ?.serviceName
                        ?: repositoryId
                    runCatching {
                        tagBuildService.preflight(config, taskDirectory(task), repositoryId)
                    }.fold(
                        onSuccess = {
                            BatchTagPreflightEntry(repositoryId, serviceName, preview = it)
                        },
                        onFailure = {
                            BatchTagPreflightEntry(
                                repositoryId,
                                serviceName,
                                error = it.message ?: "预检失败",
                            )
                        },
                    )
                }
            },
            onSuccess = { entries ->
                batchSelectionTask = null
                batchTagPreview = BatchTagPreflight(task.taskKey, entries)
            },
        )

    fun buildTags(preflight: BatchTagPreflight) {
        val task = tasks.firstOrNull { it.taskKey == preflight.taskKey }
        if (task == null) {
            showError(IllegalStateException("任务已不存在：${preflight.taskKey}"))
            return
        }
        val repositoryIds = preflight.entries.filter { it.preview != null }.map { it.repositoryId }
        runOperation(
            successMessage = "批量 UAT Tag 操作已结束",
            block = {
                repositoryIds.map { repositoryId ->
                    tagBuildService.build(
                        config,
                        taskDirectory(task),
                        repositoryId,
                        confirmed = true,
                    )
                }
            },
            onSuccess = { results ->
                batchTagPreview = null
                batchTagResults = results
            },
        )
    }

    fun clearBatchTagPreview() {
        batchTagPreview = null
    }

    fun clearBatchTagResults() {
        batchTagResults = null
    }

    fun resumeTag(operation: TagOperation) {
        val task = tasks.firstOrNull { it.taskKey == operation.taskKey }
        if (task == null) {
            showError(IllegalStateException("任务已不存在：${operation.taskKey}"))
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
        val task = tasks.firstOrNull { it.taskKey == operation.taskKey }
        if (task == null) {
            showError(IllegalStateException("任务已不存在：${operation.taskKey}"))
            return
        }
        selectedTask = task
        navigation = NavigationItem.TASKS
    }

    fun clearTagPreview() {
        tagPreview = null
    }

    fun clearTagResult() {
        tagResult = null
    }

    fun dismissMessages() {
        errorMessage = null
        statusMessage = null
    }

    private fun reloadTasks() {
        tasks = config.taskRoot
            ?.let(Path::of)
            ?.let(manifests::list)
            ?.map { it.second }
            ?.sortedByDescending { it.updatedAt }
            .orEmpty()
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
    ) {
        scope.launch {
            busy = true
            dismissMessages()
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess {
                    onSuccess(it)
                    showStatus(successMessage)
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
