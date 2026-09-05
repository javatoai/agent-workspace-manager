package com.snowball.awm.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.snowball.awm.core.DeliveryTarget
import com.snowball.awm.core.GenbuStageStatus
import com.snowball.awm.core.GenbuTagProbeService
import com.snowball.awm.core.ServiceWorkspace
import com.snowball.awm.core.TagOperation
import com.snowball.awm.core.TagHistoryItem
import com.snowball.awm.core.TagWorkspaceCheck
import com.snowball.awm.core.TaskManifest
import com.snowball.awm.core.GitTagDeliveryAdapter
import com.snowball.awm.core.WorkspaceHealth
import com.snowball.awm.core.WorkspaceStrategy
import com.snowball.awm.core.selectionKey
import java.nio.file.Path
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val retryableInterruptedTagStates = setOf(
    com.snowball.awm.core.TagOperationState.CREATED,
    com.snowball.awm.core.TagOperationState.PREFLIGHT_PASSED,
    com.snowball.awm.core.TagOperationState.SOURCE_BRANCH_PUSHED,
)

data class DeliveryUiState(
    val history: List<TagOperation>,
    val historyItems: List<TagHistoryItem>,
)

/** Tag delivery use cases isolated from task lifecycle and desktop platform actions. */
class DeliveryController internal constructor(
    private val session: AppSessionStore,
    private val adapter: GitTagDeliveryAdapter,
    private val operations: OperationRunner,
    private val taskDirectory: (TaskManifest) -> Path,
    private val refreshGitStatus: () -> Unit,
    private val genbuTagProbes: GenbuTagProbeService,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private var historyItems by mutableStateOf(adapter.historyItems(session.config, session.tasks))
    private var history by mutableStateOf(historyItems.flatMap(::operationsIn))
    private var genbuProbeJob: Job? = null
    private var genbuProbeRefreshing by mutableStateOf(false)
    private var workspaceChecks by mutableStateOf<Map<String, TagWorkspaceCheck>>(emptyMap())
    private val genbuProbeMutex = Mutex()

    val state: DeliveryUiState get() = DeliveryUiState(history, historyItems)
    val isGenbuProbeRefreshing: Boolean get() = genbuProbeRefreshing
    fun workspaceCheck(operationId: String): TagWorkspaceCheck? = workspaceChecks[operationId]

    fun canBuild(task: TaskManifest, workspace: ServiceWorkspace): Boolean {
        val group = session.config.groups.firstOrNull { it.id == task.groupId } ?: return false
        if (!group.tagEnabled || workspace.health !in setOf(WorkspaceHealth.READY, WorkspaceHealth.READY_WITH_WARNINGS)) return false
        if (!workspace.tagEnabled) return false
        // The read-only Tag preflight determines whether a branch write is needed.
        // Core policy enforcement runs after that preflight and before every write.
        return true
    }

    fun build(task: TaskManifest, workspace: ServiceWorkspace): Boolean = operations.run(
        "正在构建 ${workspace.moduleName.ifBlank { workspace.serviceName }} 测试Tag…",
        "测试Tag操作已完成",
        block = { adapter.executeTag(DeliveryTarget(session.config, taskDirectory(task), workspace.selectionKey)) },
        onSuccess = { reloadHistory(); refreshGitStatus() },
    )

    fun buildBatch(task: TaskManifest, workspaces: List<ServiceWorkspace>): Boolean = operations.run(
        "正在批量构建测试Tag…",
        "批量测试Tag操作已完成",
        block = { adapter.executeBatch(session.config, taskDirectory(task), workspaces.map(ServiceWorkspace::selectionKey)) },
        onSuccess = { reloadHistory(); refreshGitStatus() },
    )

    /**
     * Retries a conflict using the original operation id so the core service
     * updates the existing record instead of creating a second history row.
     * The workspace is resolved by the caller; the core adapter performs the
     * authoritative state and branch checks before any Git write.
     */
    fun retryConflict(task: TaskManifest, operation: TagOperation): Boolean {
        require(operation.state == com.snowball.awm.core.TagOperationState.CONFLICT) { "只有冲突测试Tag可以重试" }
        return operations.run(
            "正在重试 ${operation.serviceName} 测试Tag…",
            "测试Tag重试已完成",
            block = {
                adapter.resumeConflict(
                    DeliveryTarget(session.config, taskDirectory(task), "${operation.groupServiceId}:${operation.moduleId}"),
                    operation.operationId,
                )
            },
            onSuccess = { reloadHistory(); refreshGitStatus() },
        )
    }

    /** Checks only the current feature worktree before a conflict retry. */
    fun inspectConflictWorkspace(task: TaskManifest, operation: TagOperation): Boolean {
        require(tagOperationCanInspectWorkspace(operation)) { "当前测试Tag无需检测工作区" }
        return operations.run(
            "正在检测 ${operation.serviceName} 工作区…",
            "工作区检测完成",
            block = {
                adapter.inspectWorkspace(
                    DeliveryTarget(session.config, taskDirectory(task), "${operation.groupServiceId}:${operation.moduleId}"),
                )
            },
            onSuccess = { check -> workspaceChecks = workspaceChecks + (operation.operationId to check) },
        )
    }

    /** Re-runs a safely interrupted operation while retaining its history row. */
    fun retryInterrupted(task: TaskManifest, operation: TagOperation): Boolean {
        require(operation.state in retryableInterruptedTagStates) { "只有构建中断的测试Tag可以重试" }
        return operations.run(
            "正在重新构建 ${operation.serviceName} 测试Tag…",
            "测试Tag重试已完成",
            block = {
                adapter.resumeInterrupted(
                    DeliveryTarget(session.config, taskDirectory(task), "${operation.groupServiceId}:${operation.moduleId}"),
                    operation.operationId,
                )
            },
            onSuccess = { reloadHistory(); refreshGitStatus() },
        )
    }

    /** Retries a locally failed build on the same history record. */
    fun retryFailed(task: TaskManifest, operation: TagOperation): Boolean {
        require(operation.state == com.snowball.awm.core.TagOperationState.FAILED) { "只有失败的测试Tag可以重试" }
        return operations.run(
            "正在重试 ${operation.serviceName} 测试Tag…",
            "测试Tag重试已完成",
            block = {
                adapter.resumeFailed(
                    DeliveryTarget(session.config, taskDirectory(task), "${operation.groupServiceId}:${operation.moduleId}"),
                    operation.operationId,
                )
            },
            onSuccess = { reloadHistory(); refreshGitStatus() },
        )
    }

    /** Pushes the already-created local Tag of a partially completed build. */
    fun resumePartial(task: TaskManifest, operation: TagOperation): Boolean {
        require(operation.state == com.snowball.awm.core.TagOperationState.PARTIAL) { "只有部分完成的测试Tag可以继续构建" }
        return operations.run(
            "正在继续构建 ${operation.serviceName} 测试Tag…",
            "测试Tag继续构建已完成",
            block = {
                adapter.resumePartial(
                    DeliveryTarget(session.config, taskDirectory(task), "${operation.groupServiceId}:${operation.moduleId}"),
                    operation.operationId,
                )
            },
            onSuccess = { reloadHistory(); refreshGitStatus() },
        )
    }

    /** Rebuilds a Genbu-failed Tag with the next version on the same history record. */
    fun retag(task: TaskManifest, operation: TagOperation): Boolean {
        require(
            operation.state == com.snowball.awm.core.TagOperationState.SUCCESS &&
                operation.genbuStatus.build == GenbuStageStatus.FAILED,
        ) { "只有 Genbu 构建失败的测试Tag可以重新打Tag" }
        return operations.run(
            "正在重新打 ${operation.serviceName} 测试Tag…",
            "重新打Tag已完成",
            block = {
                adapter.retag(
                    DeliveryTarget(session.config, taskDirectory(task), "${operation.groupServiceId}:${operation.moduleId}"),
                    operation.operationId,
                )
            },
            onSuccess = { reloadHistory(); refreshGitStatus() },
        )
    }

    fun reloadHistory() {
        historyItems = adapter.historyItems(session.config, session.tasks)
        history = historyItems.flatMap(::operationsIn)
    }

    fun clearHistory(): Boolean = operations.run(
        "正在清除Tag构建历史…",
        "Tag构建历史已清除",
        block = { adapter.clearHistory(session.config, session.tasks) },
        onSuccess = {
            reloadHistory()
        },
    )

    /** Deletes only explicit local history records while keeping Genbu probe writes serialized. */
    fun deleteHistory(operationIds: Set<String>): Boolean {
        if (operationIds.isEmpty()) return false
        return operations.run(
            "正在删除Tag构建记录…",
            "已删除 ${operationIds.size} 条Tag构建记录",
            block = {
                runBlocking {
                    genbuProbeMutex.withLock {
                        adapter.deleteHistory(session.config, session.tasks, operationIds)
                    }
                }
            },
            onSuccess = {
                reloadHistory()
            },
        )
    }

    /** Poll only while the Tag page is visible; completed or superseded records are skipped in core policy. */
    fun setGenbuTagProbeVisible(visible: Boolean) {
        if (!visible) {
            genbuProbeJob?.cancel()
            genbuProbeJob = null
            return
        }
        if (genbuProbeJob?.isActive == true) return
        genbuProbeJob = scope.launch {
            while (isActive) {
                runGenbuProbe(force = false)
                delay(GENBU_PROBE_INTERVAL_MILLIS)
            }
        }
    }

    fun refreshGenbuTagProbes(): Boolean {
        if (genbuProbeRefreshing) return false
        scope.launch {
            genbuProbeRefreshing = true
            try {
                runGenbuProbe(force = true)
            } finally {
                genbuProbeRefreshing = false
            }
        }
        return true
    }

    private suspend fun runGenbuProbe(force: Boolean) {
        val changed = genbuProbeMutex.withLock {
            runCatching {
                withContext(ioDispatcher) { genbuTagProbes.probe(session.config, session.tasks, force) }
            }.getOrDefault(false)
        }
        if (changed) reloadHistory()
    }

    companion object {
        private const val GENBU_PROBE_INTERVAL_MILLIS = 30_000L

        private fun operationsIn(item: TagHistoryItem): List<TagOperation> = item.operations
    }
}
