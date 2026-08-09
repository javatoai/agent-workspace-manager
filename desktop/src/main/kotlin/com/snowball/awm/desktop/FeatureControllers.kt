package com.snowball.awm.desktop

import com.snowball.awm.core.AgentConflictResolution
import com.snowball.awm.core.GroupServiceConfig
import com.snowball.awm.core.ServiceWorkspace
import com.snowball.awm.core.TaskManifest

/** Task-facing application use cases exposed to Compose. */
class TaskController internal constructor(private val app: DesktopApplication) {
    fun select(task: TaskManifest) = app.selectTask(task)
    fun refresh() = app.refresh()
    fun create(
        name: String,
        branch: String,
        groupId: String,
        serviceIds: List<String>,
        requirementLink: String,
        notes: String,
        toolIds: List<String>,
    ) = app.createTask(name, branch, groupId, serviceIds, requirementLink, notes, toolIds)
    fun refreshGitStatus() = app.refreshCurrentTaskGitStatus()
}

/** Settings and repository catalog use cases. */
class SettingsController internal constructor(private val app: DesktopApplication) {
    fun updateService(groupId: String, service: GroupServiceConfig) = app.updateService(groupId, service)
    fun addRepositories(groupId: String, paths: List<String>) = app.addRepositories(groupId, paths)
}

/** Editable Agent instruction use cases and conflict resolution. */
class AgentInstructionsController internal constructor(private val app: DesktopApplication) {
    fun resolveConflict(resolution: AgentConflictResolution) = app.resolveAgentConflict(resolution)
    fun onWindowFocused() = app.onWindowFocused()
}

/** Delivery actions stay separate from task lifecycle orchestration. */
class DeliveryController internal constructor(private val app: DesktopApplication) {
    fun build(task: TaskManifest, workspace: ServiceWorkspace) = app.buildTag(task, workspace)
    fun buildBatch(task: TaskManifest, workspaces: List<ServiceWorkspace>) = app.buildTags(task, workspaces)
}
