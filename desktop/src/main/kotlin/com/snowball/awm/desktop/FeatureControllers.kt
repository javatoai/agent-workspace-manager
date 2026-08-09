package com.snowball.awm.desktop

import com.snowball.awm.core.AgentConflictResolution
import com.snowball.awm.core.AppConfig
import com.snowball.awm.core.BatchRepositoryAddResult
import com.snowball.awm.core.GroupServiceConfig
import com.snowball.awm.core.ServiceWorkspace
import com.snowball.awm.core.TagOperation
import com.snowball.awm.core.TaskManifest
import com.snowball.awm.core.WorkspaceGitHealth

/** Immutable snapshot consumed by task presentation code. */
data class TaskUiState(
    val config: AppConfig,
    val tasks: List<TaskManifest>,
    val selectedTask: TaskManifest?,
    val gitHealth: Map<String, WorkspaceGitHealth>,
    val busy: Boolean,
)

class TaskController internal constructor(
    private val stateProvider: () -> TaskUiState,
    private val selectAction: (TaskManifest) -> Unit,
    private val refreshAction: () -> Unit,
    private val createAction: (String, String, String, List<String>, String, String, List<String>) -> Boolean,
    private val archiveAction: (TaskManifest) -> Unit,
    private val restoreAction: (TaskManifest) -> Unit,
    private val deleteAction: (TaskManifest, Boolean) -> Unit,
    private val addServicesAction: (TaskManifest, List<String>) -> Unit,
    private val retryAction: (TaskManifest, List<String>?) -> Unit,
) {
    val state: TaskUiState get() = stateProvider()
    fun select(task: TaskManifest) = selectAction(task)
    fun refresh() = refreshAction()
    fun create(name: String, branch: String, groupId: String, serviceIds: List<String>, link: String, notes: String, toolIds: List<String>): Boolean =
        createAction(name, branch, groupId, serviceIds, link, notes, toolIds)
    fun archive(task: TaskManifest) = archiveAction(task)
    fun restore(task: TaskManifest) = restoreAction(task)
    fun delete(task: TaskManifest, discardChanges: Boolean) = deleteAction(task, discardChanges)
    fun addServices(task: TaskManifest, serviceIds: List<String>) = addServicesAction(task, serviceIds)
    fun retry(task: TaskManifest, serviceIds: List<String>? = null) = retryAction(task, serviceIds)
}

data class SettingsUiState(
    val config: AppConfig,
    val remoteBranches: Map<String, RemoteBranchesState>,
    val repositoryAddResult: BatchRepositoryAddResult?,
    val pathPickerBusy: Boolean,
)

class SettingsController internal constructor(
    private val stateProvider: () -> SettingsUiState,
    private val updateServiceAction: (String, GroupServiceConfig) -> Boolean,
    private val addRepositoriesAction: (String, List<String>) -> Boolean,
) {
    val state: SettingsUiState get() = stateProvider()
    fun updateService(groupId: String, service: GroupServiceConfig): Boolean = updateServiceAction(groupId, service)
    fun addRepositories(groupId: String, paths: List<String>): Boolean = addRepositoriesAction(groupId, paths)
}

data class AgentInstructionsUiState(val revision: Long, val hasConflict: Boolean)

class AgentInstructionsController internal constructor(
    private val stateProvider: () -> AgentInstructionsUiState,
    private val resolveAction: (AgentConflictResolution) -> Unit,
    private val focusAction: () -> Unit,
) {
    val state: AgentInstructionsUiState get() = stateProvider()
    fun resolveConflict(resolution: AgentConflictResolution) = resolveAction(resolution)
    fun onWindowFocused() = focusAction()
}

data class DeliveryUiState(
    val result: TagOperation?,
    val batchResults: List<TagOperation>?,
    val history: List<TagOperation>,
)

class DeliveryController internal constructor(
    private val stateProvider: () -> DeliveryUiState,
    private val buildAction: (TaskManifest, ServiceWorkspace) -> Unit,
    private val buildBatchAction: (TaskManifest, List<ServiceWorkspace>) -> Boolean,
) {
    val state: DeliveryUiState get() = stateProvider()
    fun build(task: TaskManifest, workspace: ServiceWorkspace) = buildAction(task, workspace)
    fun buildBatch(task: TaskManifest, workspaces: List<ServiceWorkspace>): Boolean = buildBatchAction(task, workspaces)
}
