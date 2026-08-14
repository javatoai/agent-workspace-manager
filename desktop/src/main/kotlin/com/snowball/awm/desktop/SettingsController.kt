package com.snowball.awm.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.snowball.awm.core.AppConfig
import com.snowball.awm.core.BatchRepositoryAddResult
import com.snowball.awm.core.ConfigStore
import com.snowball.awm.core.GroupConfigurationService
import com.snowball.awm.core.GroupServiceConfig
import com.snowball.awm.core.MeegleProjectConfig
import com.snowball.awm.core.DevelopmentToolConfig
import com.snowball.awm.core.DevelopmentToolType
import com.snowball.awm.core.MeegleProjectCatalog
import com.snowball.awm.core.MeegleCliService
import com.snowball.awm.core.MeegleCliStatus
import com.snowball.awm.core.MeegleProjectSummary
import com.snowball.awm.core.LocalGitEnvironmentInspector
import com.snowball.awm.core.LocalGitEnvironmentSnapshot
import com.snowball.awm.core.RemoteBranchCatalog
import com.snowball.awm.core.RepositoryRemoteCatalog
import com.snowball.awm.core.GitRepositoryRemoteCatalog
import com.snowball.awm.core.TaskManifest
import com.snowball.awm.core.ThemePreference
import com.snowball.awm.core.WorkspaceStrategy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

data class SettingsUiState(
    val config: AppConfig,
    val remoteBranches: Map<String, RemoteBranchesState>,
    val repositoryRemotes: Map<String, RepositoryRemotesState>,
    val repositoryAddResult: BatchRepositoryAddResult?,
    val pathPickerBusy: Boolean,
    val meegleProjects: MeegleProjectCatalogState,
    val meegleCli: MeegleCliState,
    val localGit: LocalGitSettingsState,
    val saveStates: Map<String, SettingsSaveState>,
)

enum class SettingsSaveState { IDLE, SAVING, SAVED, FAILED }

sealed interface MeegleProjectCatalogState {
    data object Idle : MeegleProjectCatalogState
    data object Loading : MeegleProjectCatalogState
    data class Loaded(val projects: List<MeegleProjectSummary>) : MeegleProjectCatalogState
    data class Failed(val message: String) : MeegleProjectCatalogState
}

sealed interface MeegleCliState {
    data object Idle : MeegleCliState
    data object Loading : MeegleCliState
    data class Ready(val status: MeegleCliStatus) : MeegleCliState
    data class Failed(val message: String) : MeegleCliState
}

sealed interface LocalGitSettingsState {
    data object Idle : LocalGitSettingsState
    data object Loading : LocalGitSettingsState
    data class Loaded(val snapshot: LocalGitEnvironmentSnapshot) : LocalGitSettingsState
    data class Failed(val message: String) : LocalGitSettingsState
}

sealed interface RepositoryRemotesState {
    data object Idle : RepositoryRemotesState
    data object Loading : RepositoryRemotesState
    data class Loaded(val remotes: List<String>) : RepositoryRemotesState
    data class Failed(val message: String) : RepositoryRemotesState
}

/** Configuration and repository use cases with no dependency on DesktopApplication. */
class SettingsController internal constructor(
    private val session: AppSessionStore,
    private val configStore: ConfigStore,
    private val groups: GroupConfigurationService,
    private val pathPicker: NativePathPicker,
    private val branchCatalog: RemoteBranchCatalog,
    private val remoteCatalog: RepositoryRemoteCatalog = GitRepositoryRemoteCatalog(),
    private val meegleProjectCatalog: MeegleProjectCatalog,
    private val meegleCliService: MeegleCliService,
    private val localGitInspector: LocalGitEnvironmentInspector,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val operations: OperationRunner,
    private val settingsOperations: OperationRunner,
    private val meegleOperations: OperationRunner,
    private val applyConfig: (AppConfig) -> Unit,
    private val reloadTasks: () -> Unit,
    private val showError: (Throwable) -> Unit,
    private val showStatus: (String) -> Unit,
) {
    private val remoteBranchJobs = mutableMapOf<String, Job>()
    private var remoteBranches by mutableStateOf<Map<String, RemoteBranchesState>>(emptyMap())
    private var repositoryRemotes by mutableStateOf<Map<String, RepositoryRemotesState>>(emptyMap())
    private var repositoryAddResult by mutableStateOf<BatchRepositoryAddResult?>(null)
    private var pathPickerBusy by mutableStateOf(false)
    private var meegleProjects by mutableStateOf<MeegleProjectCatalogState>(MeegleProjectCatalogState.Idle)
    private var meegleProjectJob: Job? = null
    private var meegleCli by mutableStateOf<MeegleCliState>(MeegleCliState.Idle)
    private var localGit by mutableStateOf<LocalGitSettingsState>(LocalGitSettingsState.Idle)
    private var localGitJob: Job? = null
    private var saveStates by mutableStateOf<Map<String, SettingsSaveState>>(emptyMap())

    val state: SettingsUiState
        get() = SettingsUiState(session.config, remoteBranches, repositoryRemotes, repositoryAddResult, pathPickerBusy, meegleProjects, meegleCli, localGit, saveStates)

    fun saveState(key: String): SettingsSaveState = saveStates[key] ?: SettingsSaveState.IDLE

    fun updateMeegleProjects(projects: List<MeegleProjectConfig>, onFailure: (Throwable) -> Unit = {}): Boolean = mutate(
        "正在保存飞书需求配置…",
        "飞书需求配置已保存",
        onFailure,
        "feishu",
        settingsOperations,
    ) { it.copy(meegleProjects = projects) }

    fun setTheme(theme: ThemePreference): Boolean = mutate(
        "正在更新主题…",
        "主题已更新",
        saveKey = "basic",
        runner = settingsOperations,
    ) { it.copy(theme = theme) }

    fun updateTaskRoot(value: String, onFailure: (Throwable) -> Unit = {}): Boolean = settingsOperations.run(
        "正在保存任务根目录…",
        "任务根目录已保存",
        block = {
            val path = Path.of(value).toAbsolutePath().normalize()
            Files.createDirectories(path)
            configStore.update { it.copy(taskRoot = path.toString()) }
        },
        onSuccess = { applyConfig(it); reloadTasks(); setSaveState("basic", SettingsSaveState.SAVED) },
        onFailure = { setSaveState("basic", SettingsSaveState.FAILED); onFailure(it) },
    ).also { started -> setSaveState("basic", if (started) SettingsSaveState.SAVING else SettingsSaveState.FAILED) }

    fun updateDevelopmentTools(
        tools: List<DevelopmentToolConfig>,
        defaultTool: DevelopmentToolType,
        terminal: String,
        allowTemporaryDevelopmentToolSelection: Boolean,
        onFailure: (Throwable) -> Unit = {},
    ): Boolean = mutate(
        "正在保存开发工具配置…",
        "开发工具配置已保存",
        onFailure,
        "tools",
        settingsOperations,
    ) {
        it.copy(
            developmentTools = tools,
            defaultDevelopmentTool = defaultTool,
            terminalExecutable = terminal.trim().ifBlank { null },
            allowTemporaryDevelopmentToolSelection = allowTemporaryDevelopmentToolSelection,
        )
    }

    fun updateHiddenTaskDetailBranches(branches: List<String>, onFailure: (Throwable) -> Unit = {}): Boolean = mutate(
        "正在保存分支显示规则…",
        "分支显示规则已保存",
        onFailure,
        "branches",
        settingsOperations,
    ) { config ->
        val normalized = branches.map(String::trim).filter(String::isNotEmpty)
        require(normalized.distinct().size == normalized.size) { "不展示分支名不能重复" }
        config.copy(hiddenTaskDetailBranches = normalized)
    }

    fun updateBlockedGitWriteBranches(branches: List<String>, onFailure: (Throwable) -> Unit = {}): Boolean = mutate(
        "正在保存 Git 写保护分支…",
        "Git 写保护已保存",
        onFailure,
        "git-write-policy",
        settingsOperations,
    ) { config ->
        val normalized = branches.map(String::trim).filter(String::isNotEmpty)
        require(normalized.map(String::lowercase).distinct().size == normalized.size) { "Git 写保护分支不能重复（忽略大小写）" }
        config.copy(blockedGitWriteBranches = normalized)
    }

    fun refreshLocalGit(force: Boolean = false) {
        if (!force && localGit is LocalGitSettingsState.Loading) return
        localGitJob?.cancel()
        localGit = LocalGitSettingsState.Loading
        localGitJob = scope.launch {
            val result = withContext(ioDispatcher) { runCatching { localGitInspector.inspect() } }
            localGit = result.fold(
                onSuccess = { LocalGitSettingsState.Loaded(it) },
                onFailure = { LocalGitSettingsState.Failed(it.message ?: "读取本地 Git 信息失败") },
            )
        }
    }

    fun addGroup(name: String, onCompleted: () -> Unit = {}) = mutateWithService("正在创建组…", "组已创建", onCompleted) { groups.addGroup(name) }
    fun renameGroup(groupId: String, name: String, onCompleted: () -> Unit = {}) = mutateWithService("正在重命名组…", "组已重命名", onCompleted) { groups.renameGroup(groupId, name) }
    fun moveGroup(groupId: String, offset: Int) = mutateWithService("正在更新组顺序…", "组顺序已更新") { groups.moveGroup(groupId, offset) }
    fun deleteGroup(groupId: String, onCompleted: () -> Unit = {}) = mutateWithService("正在删除空组…", "空组已删除", onCompleted) {
        require(session.tasks.none { it.groupId == groupId }) { "该组还有研发任务，不能删除" }
        groups.deleteGroup(groupId)
    }
    fun setGroupTagEnabled(groupId: String, enabled: Boolean) = mutateWithService(
        "正在更新组 Tag 开关…",
        "组 Tag 开关已更新",
        saveKey = "groups",
        runner = settingsOperations,
    ) {
        groups.setGroupTagEnabled(groupId, enabled)
    }
    fun updateGroupDefaults(groupId: String, prefix: String, tools: List<String>, onFailure: (Throwable) -> Unit = {}) = mutateWithService("正在保存组默认配置…", "组默认配置已保存", onFailure = onFailure, saveKey = "groups", runner = settingsOperations) {
        groups.updateGroupDefaults(groupId, prefix, tools)
    }

    fun refreshMeegleStatus(force: Boolean = false) {
        if (!force && meegleCli is MeegleCliState.Loading) return
        meegleCli = MeegleCliState.Loading
        scope.launch {
            val result = withContext(ioDispatcher) { runCatching { meegleCliService.status() } }
            meegleCli = result.fold(
                onSuccess = { MeegleCliState.Ready(it) },
                onFailure = { MeegleCliState.Failed(it.message ?: "检查 Meegle CLI 状态失败") },
            )
            if (result.getOrNull()?.authenticated == true) loadMeegleProjects(force = true)
        }
    }

    fun loginMeegle(): Boolean = meegleOperations.run(
        activeMessage = "正在等待 Meegle 浏览器登录…",
        successMessage = "Meegle 登录成功",
        cancellable = true,
        block = {
            meegleCliService.login("project.feishu.cn")
            meegleCliService.status().also { check(it.authenticated) { "浏览器授权完成后仍未检测到 Meegle 登录状态" } }
        },
        onSuccess = {
            meegleCli = MeegleCliState.Ready(it)
            loadMeegleProjects(force = true)
        },
        onFailure = { refreshMeegleStatus(force = true) },
    )

    fun chooseDirectory(initial: String? = null, selected: (String) -> Unit) = choose({ pathPicker.pickDirectory(initial) }) { it?.let(selected) }
    fun chooseFile(initial: String? = null, selected: (String) -> Unit) = choose({ pathPicker.pickFile(initial) }) { it?.let(selected) }
    fun chooseApplication(initial: String? = null, selected: (String) -> Unit) =
        choose({ pathPicker.pickApplication(initial) }) { it?.let(selected) }
    fun chooseDirectories(initial: String? = null, selected: (List<String>) -> Unit) = choose({ pathPicker.pickDirectories(initial) }) {
        it?.takeIf(List<String>::isNotEmpty)?.let(selected)
    }

    fun loadRemoteBranches(repositoryId: String, remote: String = "origin", force: Boolean = false) {
        val key = "$repositoryId|$remote"
        val current = remoteBranches[key]
        if (!force && current is RemoteBranchesState.Loading) return
        if (!force && current is RemoteBranchesState.Loaded && !RemoteBranchCachePolicy.isExpired(current.loadedAtNanos)) return
        val repository = session.config.repositories.firstOrNull { it.id == repositoryId }
            ?: return showError(IllegalArgumentException("找不到仓库：$repositoryId"))
        val staleBranches = when (current) {
            is RemoteBranchesState.Loaded -> current.branches
            is RemoteBranchesState.Failed -> current.staleBranches
            is RemoteBranchesState.Loading -> current.staleBranches
            RemoteBranchesState.Idle, null -> emptyList()
        }
        remoteBranches = remoteBranches + (key to RemoteBranchesState.Loading(staleBranches))
        if (force) remoteBranchJobs.remove(key)?.cancel()
        val job = scope.launch {
            try {
                val branches = runInterruptible(ioDispatcher) { branchCatalog.list(Path.of(repository.rootPath), remote) }
                remoteBranches = remoteBranches + (key to RemoteBranchesState.Loaded(branches))
            } catch (cancelled: CancellationException) {
                remoteBranches = remoteBranches + (key to (
                    staleBranches.takeIf(List<String>::isNotEmpty)?.let(RemoteBranchesState::Loaded)
                        ?: RemoteBranchesState.Idle
                    ))
                throw cancelled
            } catch (error: Throwable) {
                remoteBranches = remoteBranches + (key to RemoteBranchesState.Failed(error.message ?: "远程分支加载失败", staleBranches))
            } finally {
                val currentJob = currentCoroutineContext()[Job]
                if (remoteBranchJobs[key] == currentJob) remoteBranchJobs.remove(key)
            }
        }
        remoteBranchJobs[key] = job
    }

    fun loadRepositoryRemotes(repositoryId: String, force: Boolean = false) {
        if (!force && repositoryRemotes[repositoryId] is RepositoryRemotesState.Loading) return
        if (!force && repositoryRemotes[repositoryId] is RepositoryRemotesState.Loaded) return
        val repository = session.config.repositories.firstOrNull { it.id == repositoryId }
            ?: return showError(IllegalArgumentException("找不到仓库：$repositoryId"))
        repositoryRemotes = repositoryRemotes + (repositoryId to RepositoryRemotesState.Loading)
        scope.launch {
            val result = runCatching { runInterruptible(ioDispatcher) { remoteCatalog.list(Path.of(repository.rootPath)) } }
            repositoryRemotes = repositoryRemotes + (repositoryId to result.fold(
                onSuccess = RepositoryRemotesState::Loaded,
                onFailure = { RepositoryRemotesState.Failed(it.message ?: "Git 远程加载失败") },
            ))
        }
    }

    fun repositoryRemotesState(repositoryId: String): RepositoryRemotesState =
        repositoryRemotes[repositoryId] ?: RepositoryRemotesState.Idle

    fun cancelRemoteBranchLoads() {
        remoteBranchJobs.values.forEach(Job::cancel)
        remoteBranchJobs.clear()
        remoteBranches = remoteBranches.mapValues { (_, state) ->
            when (state) {
                is RemoteBranchesState.Loading -> state.staleBranches.takeIf(List<String>::isNotEmpty)
                    ?.let(RemoteBranchesState::Loaded) ?: RemoteBranchesState.Idle
                else -> state
            }
        }
    }

    fun remoteBranchState(repositoryId: String, remote: String): RemoteBranchesState =
        remoteBranches["$repositoryId|$remote"] ?: RemoteBranchesState.Idle

    fun loadMeegleProjects(force: Boolean = false) {
        if (!force && (meegleProjects is MeegleProjectCatalogState.Loading || meegleProjects is MeegleProjectCatalogState.Loaded)) return
        meegleProjects = MeegleProjectCatalogState.Loading
        if (force) meegleProjectJob?.cancel()
        meegleProjectJob = scope.launch {
            val result = withContext(ioDispatcher) { runCatching { meegleProjectCatalog.list() } }
            meegleProjects = result.fold(
                onSuccess = { MeegleProjectCatalogState.Loaded(it) },
                onFailure = { MeegleProjectCatalogState.Failed(it.message ?: "读取 Meegle 项目失败") },
            )
        }
    }

    fun cancelMeegleProjectLoad() {
        meegleProjectJob?.cancel()
        meegleProjectJob = null
        if (meegleProjects is MeegleProjectCatalogState.Loading) meegleProjects = MeegleProjectCatalogState.Idle
    }

    fun addRepository(groupId: String, directory: String, strategy: WorkspaceStrategy): Boolean = operations.run(
        "正在添加服务…",
        "服务已添加",
        cancellable = true,
        block = { groups.addRepository(groupId, Path.of(directory), strategy) },
        onSuccess = applyConfig,
    )

    fun addRepositories(groupId: String, paths: List<String>, onCompleted: () -> Unit = {}): Boolean = operations.run(
        "正在批量添加仓库…",
        "仓库批量添加完成",
        cancellable = true,
        block = { groups.addRepositories(groupId, paths.map(Path::of)) },
        onSuccess = { result ->
            applyConfig(result.config)
            repositoryAddResult = result
            val suffix = if (result.skipped.isNotEmpty()) "，跳过 ${result.skipped.size} 个目录" else ""
            showStatus("已添加 ${result.added.size} 个服务$suffix")
            onCompleted()
        },
    )

    fun clearRepositoryAddResult() { repositoryAddResult = null }
    fun updateService(groupId: String, service: GroupServiceConfig, onCompleted: () -> Unit = {}) =
        mutateWithService("正在保存服务配置…", "服务配置已保存", onCompleted) { groups.updateService(groupId, service) }
    fun moveService(groupId: String, serviceId: String, offset: Int) = mutateWithService("正在更新服务顺序…", "服务顺序已更新") { groups.moveService(groupId, serviceId, offset) }
    fun removeService(groupId: String, serviceId: String, onCompleted: () -> Unit = {}) = mutateWithService("正在移除服务…", "服务已移除", onCompleted) {
        require(session.tasks.none { task -> task.groupId == groupId && task.services.any { it.groupServiceId == serviceId } }) {
            "该服务仍被研发任务引用，不能从组内移除"
        }
        groups.removeService(groupId, serviceId)
    }

    private fun mutate(
        active: String,
        success: String,
        onFailure: (Throwable) -> Unit = {},
        saveKey: String? = null,
        runner: OperationRunner = operations,
        transform: (AppConfig) -> AppConfig,
    ): Boolean = runner.run(
        active,
        success,
        block = { configStore.update(transform) },
        onSuccess = { applyConfig(it); saveKey?.let { key -> setSaveState(key, SettingsSaveState.SAVED) } },
        onFailure = { error -> saveKey?.let { key -> setSaveState(key, SettingsSaveState.FAILED) }; onFailure(error) },
    ).also { started -> saveKey?.let { setSaveState(it, if (started) SettingsSaveState.SAVING else SettingsSaveState.FAILED) } }

    private fun mutateWithService(
        active: String,
        success: String,
        onCompleted: () -> Unit = {},
        onFailure: (Throwable) -> Unit = {},
        saveKey: String? = null,
        runner: OperationRunner = operations,
        block: () -> AppConfig,
    ): Boolean = runner.run(
        active,
        success,
        block = block,
        onSuccess = {
            applyConfig(it)
            saveKey?.let { key -> setSaveState(key, SettingsSaveState.SAVED) }
            onCompleted()
        },
        onFailure = { error -> saveKey?.let { key -> setSaveState(key, SettingsSaveState.FAILED) }; onFailure(error) },
    ).also { started -> saveKey?.let { setSaveState(it, if (started) SettingsSaveState.SAVING else SettingsSaveState.FAILED) } }

    private fun setSaveState(key: String, state: SettingsSaveState) { saveStates = saveStates + (key to state) }

    private fun <T> choose(pick: suspend () -> T?, complete: (T?) -> Unit) {
        if (pathPickerBusy) return
        pathPickerBusy = true
        scope.launch {
            runCatching { pick() }.onSuccess(complete).onFailure(showError)
            pathPickerBusy = false
        }
    }
}

internal object RemoteBranchCachePolicy {
    private const val CACHE_TTL_NANOS = 30_000_000_000L

    fun isExpired(loadedAtNanos: Long, nowNanos: Long = System.nanoTime()): Boolean =
        nowNanos - loadedAtNanos >= CACHE_TTL_NANOS
}
