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
import com.snowball.awm.core.RemoteBranchCatalog
import com.snowball.awm.core.TaskManifest
import com.snowball.awm.core.ThemePreference
import com.snowball.awm.core.WorkspaceStrategy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

data class SettingsUiState(
    val config: AppConfig,
    val remoteBranches: Map<String, RemoteBranchesState>,
    val repositoryAddResult: BatchRepositoryAddResult?,
    val pathPickerBusy: Boolean,
)

/** Configuration and repository use cases with no dependency on DesktopApplication. */
class SettingsController internal constructor(
    private val session: AppSessionStore,
    private val configStore: ConfigStore,
    private val groups: GroupConfigurationService,
    private val pathPicker: NativePathPicker,
    private val branchCatalog: RemoteBranchCatalog,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val operations: OperationRunner,
    private val applyConfig: (AppConfig) -> Unit,
    private val reloadTasks: () -> Unit,
    private val showError: (Throwable) -> Unit,
    private val showStatus: (String) -> Unit,
) {
    private var remoteBranches by mutableStateOf<Map<String, RemoteBranchesState>>(emptyMap())
    private var repositoryAddResult by mutableStateOf<BatchRepositoryAddResult?>(null)
    private var pathPickerBusy by mutableStateOf(false)

    val state: SettingsUiState
        get() = SettingsUiState(session.config, remoteBranches, repositoryAddResult, pathPickerBusy)

    fun updateMeegleProjects(projects: List<MeegleProjectConfig>): Boolean = mutate(
        "正在保存飞书需求配置…",
        "飞书需求配置已保存",
    ) { it.copy(meegleProjects = projects) }

    fun setTheme(theme: ThemePreference): Boolean = mutate("正在更新主题…", "主题已更新") { it.copy(theme = theme) }

    fun updateTaskRoot(value: String): Boolean = operations.run(
        "正在保存任务根目录…",
        "任务根目录已保存",
        block = {
            val path = Path.of(value).toAbsolutePath().normalize()
            Files.createDirectories(path)
            session.config.copy(taskRoot = path.toString()).also(configStore::save)
        },
        onSuccess = { applyConfig(it); reloadTasks() },
    )

    fun updateExecutables(idea: String, webStorm: String, terminal: String): Boolean = mutate(
        "正在保存开发工具配置…",
        "开发工具配置已保存",
    ) {
        it.copy(
            ideaExecutable = idea.trim().ifBlank { null },
            webStormExecutable = webStorm.trim().ifBlank { null },
            terminalExecutable = terminal.trim().ifBlank { null },
        )
    }

    fun addGroup(name: String, onCompleted: () -> Unit = {}) = mutateWithService("正在创建组…", "组已创建", onCompleted) { groups.addGroup(name) }
    fun renameGroup(groupId: String, name: String, onCompleted: () -> Unit = {}) = mutateWithService("正在重命名组…", "组已重命名", onCompleted) { groups.renameGroup(groupId, name) }
    fun moveGroup(groupId: String, offset: Int) = mutateWithService("正在更新组顺序…", "组顺序已更新") { groups.moveGroup(groupId, offset) }
    fun deleteGroup(groupId: String, onCompleted: () -> Unit = {}) = mutateWithService("正在删除空组…", "空组已删除", onCompleted) {
        require(session.tasks.none { it.groupId == groupId }) { "该组还有研发任务，不能删除" }
        groups.deleteGroup(groupId)
    }
    fun setGroupTagEnabled(groupId: String, enabled: Boolean) = mutateWithService("正在更新组 Tag 开关…", "组 Tag 开关已更新") {
        groups.setGroupTagEnabled(groupId, enabled)
    }
    fun updateGroupDefaults(groupId: String, prefix: String, tools: List<String>) = mutateWithService("正在保存组默认配置…", "组默认配置已保存") {
        groups.updateGroupDefaults(groupId, prefix, tools)
    }

    fun chooseDirectory(initial: String? = null, selected: (String) -> Unit) = choose({ pathPicker.pickDirectory(initial) }) { it?.let(selected) }
    fun chooseFile(initial: String? = null, selected: (String) -> Unit) = choose({ pathPicker.pickFile(initial) }) { it?.let(selected) }
    fun chooseDirectories(initial: String? = null, selected: (List<String>) -> Unit) = choose({ pathPicker.pickDirectories(initial) }) {
        it?.takeIf(List<String>::isNotEmpty)?.let(selected)
    }

    fun loadRemoteBranches(repositoryId: String, remote: String = "origin", force: Boolean = false) {
        val key = "$repositoryId|$remote"
        val current = remoteBranches[key]
        if (!force && (current is RemoteBranchesState.Loading || current is RemoteBranchesState.Loaded)) return
        val repository = session.config.repositories.firstOrNull { it.id == repositoryId }
            ?: return showError(IllegalArgumentException("找不到仓库：$repositoryId"))
        remoteBranches = remoteBranches + (key to RemoteBranchesState.Loading)
        scope.launch {
            val result = withContext(ioDispatcher) { runCatching { branchCatalog.list(Path.of(repository.rootPath), remote) } }
            remoteBranches = remoteBranches + (key to result.fold(
                onSuccess = { RemoteBranchesState.Loaded(it) },
                onFailure = { RemoteBranchesState.Failed(it.message ?: "远程分支加载失败") },
            ))
        }
    }

    fun remoteBranchState(repositoryId: String, remote: String): RemoteBranchesState =
        remoteBranches["$repositoryId|$remote"] ?: RemoteBranchesState.Idle

    fun addRepository(groupId: String, directory: String, strategy: WorkspaceStrategy): Boolean = operations.run(
        "正在添加服务…",
        "服务已添加",
        block = { groups.addRepository(groupId, Path.of(directory), strategy) },
        onSuccess = applyConfig,
    )

    fun addRepositories(groupId: String, paths: List<String>, onCompleted: () -> Unit = {}): Boolean = operations.run(
        "正在批量添加仓库…",
        "仓库批量添加完成",
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

    private fun mutate(active: String, success: String, transform: (AppConfig) -> AppConfig): Boolean = operations.run(
        active,
        success,
        block = { configStore.save(transform(session.config)); configStore.load() },
        onSuccess = applyConfig,
    )

    private fun mutateWithService(
        active: String,
        success: String,
        onCompleted: () -> Unit = {},
        block: () -> AppConfig,
    ): Boolean = operations.run(active, success, block) {
        applyConfig(it)
        onCompleted()
    }

    private fun <T> choose(pick: suspend () -> T?, complete: (T?) -> Unit) {
        if (pathPickerBusy) return
        pathPickerBusy = true
        scope.launch {
            runCatching { pick() }.onSuccess(complete).onFailure(showError)
            pathPickerBusy = false
        }
    }
}
