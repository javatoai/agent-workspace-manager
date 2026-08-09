package com.snowball.awm.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.snowball.awm.core.AddGroupedTaskServicesRequest
import com.snowball.awm.core.AppConfig
import com.snowball.awm.core.ConfigStore
import com.snowball.awm.core.CreateGroupedTaskRequest
import com.snowball.awm.core.DeleteRisk
import com.snowball.awm.core.ManifestStore
import com.snowball.awm.core.RepositoryConfig
import com.snowball.awm.core.RepositoryInspector
import com.snowball.awm.core.ServiceWorkspace
import com.snowball.awm.core.TaskApplicationService
import com.snowball.awm.core.TaskManifest
import com.snowball.awm.core.WorkspaceGitHealth
import com.snowball.awm.core.WorkspaceGitHealthState
import com.snowball.awm.core.WorkspaceGitStatusService
import com.snowball.awm.core.WorkspaceToolLaunchService
import com.snowball.awm.core.toInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** Immutable state exposed by task use cases to the presentation layer. */
data class TaskUiState(
    val config: AppConfig,
    val tasks: List<TaskManifest>,
    val selectedTask: TaskManifest?,
    val gitHealth: Map<String, WorkspaceGitHealth>,
    val deleteRisks: Map<String, DeleteRiskInspection>,
    val busy: Boolean,
)

/**
 * Owns task lifecycle and workspace-health use cases.
 *
 * This controller depends on application services and small callbacks only; it
 * deliberately has no reference to [DesktopApplication] or another controller.
 */
class TaskController internal constructor(
    private val session: AppSessionStore,
    private val configStore: ConfigStore,
    private val manifests: ManifestStore,
    private val repositoryInspector: RepositoryInspector,
    private val tasks: TaskApplicationService,
    private val gitStatus: WorkspaceGitStatusService,
    private val workspaceTools: WorkspaceToolLaunchService,
    private val operations: OperationRunner,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val desktopActions: DesktopActions,
    private val onConfigApplied: (AppConfig) -> Unit,
    private val onTasksChanged: () -> Unit,
    private val onRequirementsRefresh: (Boolean) -> Unit,
    private val onRequirementSelected: (TaskManifest) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val isBusy: () -> Boolean,
) {
    private var gitStatusRevision = 0L
    private var gitHealth by mutableStateOf<Map<String, WorkspaceGitHealth>>(emptyMap())
    private var deleteRisks by mutableStateOf<Map<String, DeleteRiskInspection>>(emptyMap())

    val state: TaskUiState
        get() = TaskUiState(
            config = session.config,
            tasks = session.tasks,
            selectedTask = session.selectedTask,
            gitHealth = gitHealth,
            deleteRisks = deleteRisks,
            busy = isBusy(),
        )

    fun select(task: TaskManifest) {
        session.selectedTask = task
        refreshGitStatus()
        onRequirementSelected(task)
    }

    /** Explicit refresh is the only task path that validates every repository. */
    fun refresh(): Boolean = operations.run("正在刷新状态…", "状态刷新完成", block = {
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
        val loaded = scanTasks(refreshedConfig)
        loaded.manifests.forEach { task ->
            tasks.refreshAgents(refreshedConfig, taskDirectory(refreshedConfig, task))
        }
        refreshedConfig to loaded
    }, onSuccess = { (updatedConfig, loaded) ->
        val selectedFolder = session.selectedTask?.folderName
        onConfigApplied(updatedConfig)
        session.replaceTasks(loaded.manifests, selectedFolder)
        onTasksChanged()
        onRequirementsRefresh(true)
        refreshGitStatus()
        loaded.warning?.let { onError(IllegalStateException(it)) }
    })

    fun create(
        name: String,
        branch: String,
        groupId: String,
        serviceIds: List<String>,
        link: String,
        notes: String,
        toolIds: List<String>,
        onCompleted: () -> Unit = {},
    ): Boolean = operations.run("正在创建任务…", "任务已创建", block = {
        val created = tasks.create(
            session.config,
            CreateGroupedTaskRequest(name, branch, groupId, serviceIds, link, notes),
        )
        workspaceTools.launch(taskDirectory(session.config, created), created, toolIds)
    }, onSuccess = { created ->
        reloadTasks(created.folderName)
        session.navigation = NavigationItem.TASKS
        onCompleted()
    })

    fun archive(task: TaskManifest, onCompleted: () -> Unit = {}): Boolean =
        operations.run("正在归档任务…", "任务已归档", block = {
            tasks.archive(session.config, taskDirectory(task), false)
        }, onSuccess = {
            reloadTasks(it.folderName)
            session.navigation = NavigationItem.ARCHIVED
            onCompleted()
        })

    fun restore(task: TaskManifest, onCompleted: () -> Unit = {}): Boolean =
        operations.run("正在恢复任务…", "任务已恢复", block = {
            tasks.restore(session.config, taskDirectory(task))
        }, onSuccess = {
            reloadTasks(it.folderName)
            session.navigation = NavigationItem.TASKS
            onCompleted()
        })

    fun requestDeleteRisk(task: TaskManifest) {
        val key = task.taskDirectoryName
        if (deleteRisks[key]?.loading == true) return
        deleteRisks = deleteRisks + (key to DeleteRiskInspection())
        val config = session.config
        scope.launch {
            val result = withContext(ioDispatcher) {
                runCatching { tasks.inspectDeleteRisk(config, taskDirectory(config, task)) }
            }
            deleteRisks = deleteRisks + (key to result.fold(
                onSuccess = { DeleteRiskInspection(loading = false, risks = it) },
                onFailure = { DeleteRiskInspection(loading = false, error = it.message ?: "删除风险检查失败") },
            ))
        }
    }

    fun clearDeleteRisk(task: TaskManifest) {
        deleteRisks = deleteRisks - task.taskDirectoryName
    }

    fun delete(task: TaskManifest, discardChanges: Boolean, onCompleted: () -> Unit = {}): Boolean =
        operations.run("正在删除任务…", "任务已删除", block = {
            tasks.delete(session.config, taskDirectory(task), discardChanges)
        }, onSuccess = { reloadTasks(); onCompleted() })

    fun addServices(task: TaskManifest, serviceIds: List<String>, onCompleted: () -> Unit = {}): Boolean =
        operations.run("正在追加服务…", "服务已追加", block = {
            tasks.addServices(session.config, taskDirectory(task), AddGroupedTaskServicesRequest(serviceIds))
        }, onSuccess = { reloadTasks(it.folderName); onCompleted() })

    fun retry(task: TaskManifest, serviceIds: List<String>? = null): Boolean =
        operations.run("正在重试失败服务…", "失败服务已重试", block = {
            tasks.retryFailedServices(session.config, taskDirectory(task), serviceIds)
        }, onSuccess = { reloadTasks(it.folderName) })

    fun retryWorkspaceTool(task: TaskManifest, toolId: String): Boolean =
        operations.run("正在重新打开工作区工具…", "工作区工具已重新打开", block = {
            workspaceTools.retry(taskDirectory(task), task, toolId)
        }, onSuccess = { reloadTasks(it.folderName) })

    fun refreshGitStatus() {
        val task = session.selectedTask ?: run {
            gitHealth = emptyMap()
            return
        }
        val revision = ++gitStatusRevision
        val paths = task.services.map(::normalizedWorkspacePath).distinct()
        gitHealth = paths.associateWith { WorkspaceGitHealth(WorkspaceGitHealthState.CHECKING) }
        scope.launch {
            val inspected = withContext(ioDispatcher) { gitStatus.inspect(task.services) }
                .mapKeys { (path, _) -> path.toString() }
            if (revision == gitStatusRevision && session.selectedTask?.taskDirectoryName == task.taskDirectoryName) {
                gitHealth = inspected
            }
        }
    }

    fun gitHealth(workspace: ServiceWorkspace): WorkspaceGitHealth? = gitHealth[normalizedWorkspacePath(workspace)]

    fun openWorkData(task: TaskManifest) = desktopActions.openWorkData(taskDirectory(task))

    private fun reloadTasks(preferredFolder: String? = session.selectedTask?.folderName) {
        val loaded = scanTasks(session.config)
        session.replaceTasks(loaded.manifests, preferredFolder)
        onTasksChanged()
        refreshGitStatus()
        loaded.warning?.let { onError(IllegalStateException(it)) }
    }

    private fun scanTasks(config: AppConfig): LoadedTaskSnapshot {
        val root = config.taskRoot?.let(Path::of) ?: return LoadedTaskSnapshot(emptyList())
        val scan = runCatching { manifests.scan(root) }.getOrElse { error ->
            return LoadedTaskSnapshot(emptyList(), "任务目录扫描失败：${error.message ?: error::class.simpleName}")
        }
        val messages = buildList {
            if (scan.unsupportedDirectories.isNotEmpty()) add("已忽略 ${scan.unsupportedDirectories.size} 个非 AWM v5 任务目录")
            if (scan.failures.isNotEmpty()) {
                add("${scan.failures.size} 个任务清单读取失败：" + scan.failures.entries.joinToString { (path, reason) -> "${path.fileName}：$reason" })
            }
        }
        return LoadedTaskSnapshot(
            scan.current.map { it.second }.sortedByDescending(TaskManifest::updatedAt),
            messages.takeIf(List<String>::isNotEmpty)?.joinToString("；"),
        )
    }

    private fun taskDirectory(task: TaskManifest): Path = taskDirectory(session.config, task)

    private fun taskDirectory(config: AppConfig, task: TaskManifest): Path =
        Path.of(requireNotNull(config.taskRoot) { "尚未配置任务根目录" }).resolve(task.taskDirectoryName)

    private fun normalizedWorkspacePath(workspace: ServiceWorkspace): String =
        Path.of(workspace.worktreePath).toAbsolutePath().normalize().toString()

    private fun samePathIdentity(expected: String, actual: String): Boolean {
        val left = Path.of(expected).toAbsolutePath().normalize().toString()
        val right = Path.of(actual).toAbsolutePath().normalize().toString()
        return if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) left.equals(right, true) else left == right
    }
}

private data class LoadedTaskSnapshot(
    val manifests: List<TaskManifest>,
    val warning: String? = null,
)
