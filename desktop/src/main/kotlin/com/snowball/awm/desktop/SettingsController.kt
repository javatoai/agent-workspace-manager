package com.snowball.awm.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.snowball.awm.core.AppConfig
import com.snowball.awm.core.AwmTime
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
import com.snowball.awm.core.MeegleCommandSource
import com.snowball.awm.core.MeegleExecutable
import com.snowball.awm.core.normalizeMeegleExecutablePath
import com.snowball.awm.core.GitCommandSource
import com.snowball.awm.core.GitExecutable
import com.snowball.awm.core.GenbuCommandSource
import com.snowball.awm.core.GenbuExecutable
import com.snowball.awm.core.GenbuDetectionAuditEvent
import com.snowball.awm.core.normalizeGenbuExecutablePath
import com.snowball.awm.core.normalizeGitExecutablePath
import com.snowball.awm.core.MeegleProjectSummary
import com.snowball.awm.core.LocalGitEnvironmentInspector
import com.snowball.awm.core.LocalGitEnvironmentSnapshot
import com.snowball.awm.core.RemoteBranchCatalog
import com.snowball.awm.core.RepositoryRemoteCatalog
import com.snowball.awm.core.GitRepositoryRemoteCatalog
import com.snowball.awm.core.TaskManifest
import com.snowball.awm.core.ThemePreference
import com.snowball.awm.core.WorkspaceStrategy
import com.snowball.awm.core.validateRequirementMaterialsSubdirectory
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
    val genbu: GenbuSettingsState,
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
    data class Loading(val previous: MeegleCliStatus? = null) : MeegleCliState
    data class Ready(val status: MeegleCliStatus) : MeegleCliState
    data class Failed(val message: String) : MeegleCliState
}

sealed interface LocalGitSettingsState {
    data object Idle : LocalGitSettingsState
    data class Loading(val previous: LocalGitEnvironmentSnapshot? = null) : LocalGitSettingsState
    data class Loaded(val snapshot: LocalGitEnvironmentSnapshot) : LocalGitSettingsState
    data class Failed(val message: String) : LocalGitSettingsState
}

sealed interface GenbuSettingsState {
    data object Idle : GenbuSettingsState
    data object Loading : GenbuSettingsState
    data class Loaded(val command: String, val source: GenbuCommandSource, val detectedAt: String) : GenbuSettingsState
    data class Failed(val message: String) : GenbuSettingsState
}

sealed interface RepositoryRemotesState {
    data object Idle : RepositoryRemotesState
    data object Loading : RepositoryRemotesState
    data class Loaded(val remotes: List<String>) : RepositoryRemotesState
    data class Failed(val message: String) : RepositoryRemotesState
}

private data class MeegleExecutableAutoSave(
    val config: AppConfig,
    val savedDetectedPath: Boolean,
)

private data class GitExecutableAutoSave(
    val config: AppConfig,
    val savedDetectedPath: Boolean,
)

private data class GenbuExecutableAutoSave(
    val config: AppConfig,
    val savedDetectedPath: Boolean,
)

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
    private val meegleExecutable: MeegleExecutable = MeegleExecutable.pathFallback(),
    private val gitExecutable: GitExecutable = GitExecutable.pathFallback(),
    private val genbuExecutable: GenbuExecutable = GenbuExecutable.pathFallback(),
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
    private var genbu by mutableStateOf<GenbuSettingsState>(GenbuSettingsState.Idle)
    private var genbuJob: Job? = null
    private var localGitJob: Job? = null
    private var genbuCommand by mutableStateOf(genbuExecutable.current() to genbuExecutable.source())
    private var saveStates by mutableStateOf<Map<String, SettingsSaveState>>(emptyMap())

    val state: SettingsUiState
        get() = SettingsUiState(session.config, remoteBranches, repositoryRemotes, repositoryAddResult, pathPickerBusy, meegleProjects, meegleCli, localGit, genbu, saveStates)

    fun saveState(key: String): SettingsSaveState = saveStates[key] ?: SettingsSaveState.IDLE

    fun updateMeegleProjects(projects: List<MeegleProjectConfig>, onFailure: (Throwable) -> Unit = {}): Boolean = mutate(
        "正在保存飞书需求配置…",
        "飞书需求配置已保存",
        onFailure,
        "feishu",
        settingsOperations,
    ) { it.copy(meegleProjects = projects) }

    fun updateMeegleExecutablePath(raw: String, onFailure: (Throwable) -> Unit = {}): Boolean = mutate(
        "正在保存 Meegle 命令路径…",
        "Meegle 命令路径已保存",
        onFailure,
        "feishu",
        settingsOperations,
        onCompleted = { refreshMeegleStatus(force = true) },
    ) { config ->
        config.copy(meegleExecutablePath = normalizeMeegleExecutablePath(raw))
    }

    /** The currently effective Meegle command and where it came from; safe on the UI thread. */
    fun meegleCommandResolution(): Pair<String, MeegleCommandSource> =
        meegleExecutable.current() to meegleExecutable.source()

    fun updateGitExecutablePath(raw: String, onFailure: (Throwable) -> Unit = {}): Boolean = mutate(
        "正在保存 Git 命令路径…",
        "Git 命令路径已保存",
        onFailure,
        "git",
        settingsOperations,
        onCompleted = { refreshLocalGit(force = true) },
    ) { config ->
        config.copy(gitExecutablePath = normalizeGitExecutablePath(raw))
    }

    /** The currently effective Git command and where it came from; safe on the UI thread. */
    fun gitCommandResolution(): Pair<String, GitCommandSource> =
        gitExecutable.current() to gitExecutable.source()

    fun genbuCommandResolution(): Pair<String, GenbuCommandSource> = genbuCommand

    /** Lightweight window-focus refresh: detects once, persists a first-time result, writes no audit. */
    fun refreshGenbuCommandResolution() {
        val existingGenbuPath = session.config.genbuExecutablePath
        val shouldAutoDetect = existingGenbuPath.isNullOrBlank()
        scope.launch {
            val (autoSave, resolution) = withContext(ioDispatcher) {
                runCatching {
                    val autoSave = autoSaveGenbuExecutablePath(shouldAutoDetect, existingGenbuPath)
                    autoSave to (genbuExecutable.current() to genbuExecutable.source())
                }.getOrElse {
                    null to (genbuExecutable.current() to genbuExecutable.source())
                }
            }
            autoSave?.let {
                applyConfig(it.config)
                if (it.savedDetectedPath) {
                    setSaveState("production-tag", SettingsSaveState.SAVED)
                    showStatus("已自动检测并保存 Genbu 命令路径")
                }
            }
            genbuCommand = resolution
        }
    }

    fun updateProductionTagSettings(
        enabled: Boolean,
        rawGenbuPath: String,
        onFailure: (Throwable) -> Unit = {},
    ): Boolean = mutate(
        "正在保存生产 Tag 设置…",
        "生产 Tag 设置已保存",
        onFailure,
        "production-tag",
        settingsOperations,
        onCompleted = { refreshGenbu(force = true) },
    ) { config ->
        val normalized = normalizeGenbuExecutablePath(rawGenbuPath)
        config.copy(
            productionTagBuildEnabled = enabled,
            genbuExecutablePath = normalized,
            genbuExecutableAutoDetected = config.genbuExecutableAutoDetected &&
                normalized == config.genbuExecutablePath,
        )
    }

    fun updateProductionTagEnabled(
        enabled: Boolean,
        onFailure: (Throwable) -> Unit = {},
    ): Boolean = mutate(
        "正在保存生产 Tag 开关…",
        "生产 Tag 开关已保存",
        onFailure,
        "production-tag",
        settingsOperations,
    ) { config -> config.copy(productionTagBuildEnabled = enabled) }

    fun refreshGenbu(force: Boolean = false) {
        if (!force && genbu is GenbuSettingsState.Loading) return
        if (force) genbuJob?.cancel()
        val existingGenbuPath = session.config.genbuExecutablePath
        val shouldAutoDetect = existingGenbuPath.isNullOrBlank() ||
            session.config.genbuExecutableAutoDetected
        genbu = GenbuSettingsState.Loading
        genbuJob = scope.launch {
            val (autoSave, result) = withContext(ioDispatcher) {
                val autoSaveResult = runCatching { autoSaveGenbuExecutablePath(shouldAutoDetect, existingGenbuPath) }
                if (autoSaveResult.isFailure) {
                    null to Result.failure(autoSaveResult.exceptionOrNull()!!)
                } else {
                    autoSaveResult.getOrNull() to runCatching {
                        // A fresh detection result is authoritative; the in-memory configured
                        // path still holds the pre-save value until applyConfig runs.
                        val saved = autoSaveResult.getOrNull()?.config?.genbuExecutablePath
                        val command = saved ?: genbuExecutable.probe()
                        val source = if (saved != null) GenbuCommandSource.PROBED else genbuExecutable.source()
                        check(source != GenbuCommandSource.PATH_FALLBACK) {
                            "未自动检测到 Genbu，请手动选择 genbu 可执行文件"
                        }
                        command to source
                    }
                }
            }
            autoSave?.let {
                applyConfig(it.config)
                if (it.savedDetectedPath) {
                    setSaveState("production-tag", SettingsSaveState.SAVED)
                    showStatus("已自动检测并保存 Genbu 命令路径")
                }
            }
            val detectedAt = AwmTime.format(java.time.Instant.now())
            val resolved = result.fold(
                onSuccess = { (command, source) -> GenbuSettingsState.Loaded(
                    command,
                    if (session.config.genbuExecutableAutoDetected) GenbuCommandSource.PROBED else source,
                    detectedAt,
                ) },
                onFailure = { GenbuSettingsState.Failed(it.message ?: "检测 Genbu 失败") },
            )
            val audit = when (resolved) {
                is GenbuSettingsState.Loaded -> GenbuDetectionAuditEvent(
                    detectedAt = detectedAt,
                    status = "LOADED",
                    command = resolved.command,
                    source = resolved.source.name,
                )
                is GenbuSettingsState.Failed -> GenbuDetectionAuditEvent(
                    detectedAt = detectedAt,
                    status = "FAILED",
                    message = resolved.message,
                )
                else -> error("Genbu 检测结束状态不合法")
            }
            val auditedConfig = try {
                withContext(ioDispatcher) {
                    configStore.update { current ->
                        current.copy(genbuDetectionAudit = (current.genbuDetectionAudit + audit).takeLast(100))
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                genbu = GenbuSettingsState.Failed("Genbu 检测结果无法写入审计：${error.message ?: "未知错误"}")
                return@launch
            }
            applyConfig(auditedConfig)
            genbu = resolved
        }
    }

    private fun autoSaveGenbuExecutablePath(shouldAutoDetect: Boolean, existingPath: String?): GenbuExecutableAutoSave? {
        if (!shouldAutoDetect) return null
        // A still-valid auto-detected path stays authoritative; rescanning while it works could
        // silently swap in a different copy sitting earlier in the scan order. Only a broken
        // path (deleted/moved installation) triggers a fresh scan that may replace it.
        val existingValid = !existingPath.isNullOrBlank() &&
            runCatching { normalizeGenbuExecutablePath(existingPath) }.getOrNull() != null
        if (existingValid) return null
        val detected = genbuExecutable.detect() ?: return null
        val normalized = normalizeGenbuExecutablePath(detected)
            ?: error("自动探测到的 Genbu 命令路径为空")
        var savedDetectedPath = false
        val updated = configStore.update { current ->
            val replaceable = current.genbuExecutablePath.isNullOrBlank() || current.genbuExecutableAutoDetected
            if (replaceable && current.genbuExecutablePath != normalized) {
                savedDetectedPath = true
                current.copy(genbuExecutablePath = normalized, genbuExecutableAutoDetected = true)
            } else current
        }
        return GenbuExecutableAutoSave(updated, savedDetectedPath)
    }

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
        onSuccess = { applyConfig(it); reloadTasks(); setSaveState("paths", SettingsSaveState.SAVED) },
        onFailure = { setSaveState("paths", SettingsSaveState.FAILED); onFailure(it) },
    ).also { started -> setSaveState("paths", if (started) SettingsSaveState.SAVING else SettingsSaveState.FAILED) }

    /** Saves and normalizes the optional requirement-materials root independently. */
    fun updateRequirementMaterialsRoot(value: String, onFailure: (Throwable) -> Unit = {}): Boolean = settingsOperations.run(
        "正在保存需求资料根目录…",
        "需求资料根目录已保存",
        block = {
            val normalized = value.trim().takeIf(String::isNotEmpty)?.let {
                val path = Path.of(it).toAbsolutePath().normalize()
                Files.createDirectories(path)
                path.toString()
            }
            configStore.update { it.copy(requirementMaterialsRoot = normalized) }
        },
        onSuccess = { applyConfig(it); setSaveState("requirement-materials-root", SettingsSaveState.SAVED) },
        onFailure = { setSaveState("requirement-materials-root", SettingsSaveState.FAILED); onFailure(it) },
    ).also { started -> setSaveState("requirement-materials-root", if (started) SettingsSaveState.SAVING else SettingsSaveState.FAILED) }

    /** Saves the optional single-segment child directory independently. */
    fun updateRequirementMaterialsSubdirectory(value: String, onFailure: (Throwable) -> Unit = {}): Boolean = mutate(
        "正在保存需求资料子目录…",
        "需求资料子目录已保存",
        onFailure,
        "requirement-materials-subdirectory",
        settingsOperations,
    ) { config ->
        config.copy(requirementMaterialsSubdirectory = validateRequirementMaterialsSubdirectory(value))
    }

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
        val shouldAutoDetect = session.config.gitExecutablePath.isNullOrBlank()
        localGit = LocalGitSettingsState.Loading((localGit as? LocalGitSettingsState.Loaded)?.snapshot)
        localGitJob = scope.launch {
            val (autoSave, result) = withContext(ioDispatcher) {
                val autoSaveResult = runCatching { autoSaveGitExecutablePath(shouldAutoDetect) }
                if (autoSaveResult.isFailure) {
                    null to Result.failure(autoSaveResult.exceptionOrNull()!!)
                } else {
                    autoSaveResult.getOrNull() to runCatching { localGitInspector.inspect() }
                }
            }
            autoSave?.let {
                applyConfig(it.config)
                if (it.savedDetectedPath) {
                    setSaveState("git", SettingsSaveState.SAVED)
                    showStatus("已自动检测并保存 Git 命令路径")
                }
            }
            if (shouldAutoDetect && autoSave == null && result.isFailure) {
                setSaveState("git", SettingsSaveState.FAILED)
            }
            localGit = result.fold(
                onSuccess = { LocalGitSettingsState.Loaded(it) },
                onFailure = { LocalGitSettingsState.Failed(it.message ?: "读取本地 Git 信息失败") },
            )
        }
    }

    private fun autoSaveGitExecutablePath(shouldAutoDetect: Boolean): GitExecutableAutoSave? {
        if (!shouldAutoDetect) return null
        val detected = gitExecutable.probe()
        if (gitExecutable.source() != GitCommandSource.PROBED) return null
        val normalized = normalizeGitExecutablePath(detected)
            ?: error("自动探测到的 Git 命令路径为空")
        var savedDetectedPath = false
        val updated = configStore.update { current ->
            if (current.gitExecutablePath.isNullOrBlank()) {
                savedDetectedPath = true
                current.copy(gitExecutablePath = normalized)
            } else {
                current
            }
        }
        return GitExecutableAutoSave(updated, savedDetectedPath)
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
        val shouldAutoDetect = session.config.meegleExecutablePath.isNullOrBlank()
        meegleCli = MeegleCliState.Loading((meegleCli as? MeegleCliState.Ready)?.status)
        scope.launch {
            val (autoSave, result) = withContext(ioDispatcher) {
                val autoSaveResult = runCatching { autoSaveMeegleExecutablePath(shouldAutoDetect) }
                if (autoSaveResult.isFailure) {
                    null to Result.failure(autoSaveResult.exceptionOrNull()!!)
                } else {
                    autoSaveResult.getOrNull() to runCatching { meegleCliService.status() }
                }
            }
            autoSave?.let {
                applyConfig(it.config)
                if (it.savedDetectedPath) {
                    setSaveState("feishu", SettingsSaveState.SAVED)
                    showStatus("已自动检测并保存 Meegle 命令路径")
                }
            }
            if (shouldAutoDetect && autoSave == null && result.isFailure) {
                setSaveState("feishu", SettingsSaveState.FAILED)
            }
            meegleCli = result.fold(
                onSuccess = { MeegleCliState.Ready(it) },
                onFailure = { MeegleCliState.Failed(it.message ?: "检查 Meegle CLI 状态失败") },
            )
            if (result.getOrNull()?.authenticated == true) loadMeegleProjects(force = true)
        }
    }

    private fun autoSaveMeegleExecutablePath(shouldAutoDetect: Boolean): MeegleExecutableAutoSave? {
        if (!shouldAutoDetect) return null
        val detected = meegleExecutable.probe()
        if (meegleExecutable.source() != MeegleCommandSource.PROBED) return null
        val normalized = normalizeMeegleExecutablePath(detected)
            ?: error("自动探测到的 Meegle 命令路径为空")
        var savedDetectedPath = false
        val updated = configStore.update { current ->
            if (current.meegleExecutablePath.isNullOrBlank()) {
                savedDetectedPath = true
                current.copy(meegleExecutablePath = normalized)
            } else {
                current
            }
        }
        return MeegleExecutableAutoSave(updated, savedDetectedPath)
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
        onCompleted: () -> Unit = {},
        transform: (AppConfig) -> AppConfig,
    ): Boolean = runner.run(
        active,
        success,
        block = { configStore.update(transform) },
        onSuccess = { applyConfig(it); saveKey?.let { key -> setSaveState(key, SettingsSaveState.SAVED) }; onCompleted() },
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
