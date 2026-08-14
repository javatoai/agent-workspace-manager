package com.snowball.awm.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.snowball.awm.core.DeliveryTarget
import com.snowball.awm.core.ServiceWorkspace
import com.snowball.awm.core.TagOperation
import com.snowball.awm.core.TaskManifest
import com.snowball.awm.core.GitTagDeliveryAdapter
import com.snowball.awm.core.WorkspaceHealth
import com.snowball.awm.core.WorkspaceStrategy
import com.snowball.awm.core.selectionKey
import java.nio.file.Path

data class DeliveryUiState(
    val result: TagOperation?,
    val batchResults: List<TagOperation>?,
    val history: List<TagOperation>,
)

/** Tag delivery use cases isolated from task lifecycle and desktop platform actions. */
class DeliveryController internal constructor(
    private val session: AppSessionStore,
    private val adapter: GitTagDeliveryAdapter,
    private val operations: OperationRunner,
    private val taskDirectory: (TaskManifest) -> Path,
    private val refreshGitStatus: () -> Unit,
) {
    private var result by mutableStateOf<TagOperation?>(null)
    private var batchResults by mutableStateOf<List<TagOperation>?>(null)
    private var history by mutableStateOf(adapter.historyOperations(session.config, session.tasks))

    val state: DeliveryUiState get() = DeliveryUiState(result, batchResults, history)

    fun canBuild(task: TaskManifest, workspace: ServiceWorkspace): Boolean {
        val group = session.config.groups.firstOrNull { it.id == task.groupId } ?: return false
        if (!group.tagEnabled || workspace.health !in setOf(WorkspaceHealth.READY, WorkspaceHealth.READY_WITH_WARNINGS)) return false
        if (!workspace.tagEnabled) return false
        // The read-only Tag preflight determines whether a branch write is needed.
        // Core policy enforcement runs after that preflight and before every write.
        return true
    }

    fun build(task: TaskManifest, workspace: ServiceWorkspace): Boolean = operations.run(
        "正在构建 ${workspace.moduleName.ifBlank { workspace.serviceName }} Tag…",
        "Tag 操作已完成",
        block = { adapter.executeTag(DeliveryTarget(session.config, taskDirectory(task), workspace.selectionKey)) },
        onSuccess = { result = it; reloadHistory(); refreshGitStatus() },
    )

    fun buildBatch(task: TaskManifest, workspaces: List<ServiceWorkspace>, onCompleted: () -> Unit = {}): Boolean = operations.run(
        "正在批量构建 Tag…",
        "批量 Tag 操作已完成",
        block = { adapter.executeBatch(session.config, taskDirectory(task), workspaces.map(ServiceWorkspace::selectionKey)) },
        onSuccess = { batchResults = it; reloadHistory(); refreshGitStatus(); onCompleted() },
    )

    fun clearResult() { result = null }
    fun clearBatchResults() { batchResults = null }
    fun reloadHistory() { history = adapter.historyOperations(session.config, session.tasks) }
}
