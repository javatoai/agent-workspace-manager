package com.snowball.awm.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.snowball.awm.core.AppConfig
import com.snowball.awm.core.TaskManifest
import com.snowball.awm.core.TaskLifecycleStatus

/** Owns navigation and selection independently from feature controllers. */
class AppSessionStore(
    initialConfig: AppConfig,
    initialTasks: List<TaskManifest>,
) {
    var config by mutableStateOf(initialConfig)
        internal set
    var tasks by mutableStateOf(initialTasks)
        internal set
    private var navigationState by mutableStateOf(NavigationItem.TASKS)
    var navigation: NavigationItem
        get() = navigationState
        set(value) {
            navigationState = value
            reconcileSelection()
        }
    var selectedTask by mutableStateOf(initialTasks.firstOrNull { it.lifecycleStatus == TaskLifecycleStatus.ACTIVE })
        internal set

    fun replaceTasks(newTasks: List<TaskManifest>, preferredFolder: String? = selectedTask?.folderName) {
        tasks = newTasks
        selectedTask = preferredFolder?.let { folder -> newTasks.firstOrNull { it.folderName == folder } }
        reconcileSelection()
    }

    private fun reconcileSelection() {
        val archived = when (navigationState) {
            NavigationItem.TASKS -> false
            NavigationItem.ARCHIVED -> true
            else -> return
        }
        val current = selectedTask
        if (current == null || (current.lifecycleStatus == TaskLifecycleStatus.ARCHIVED) != archived) {
            selectedTask = tasks.firstOrNull {
                (it.lifecycleStatus == TaskLifecycleStatus.ARCHIVED) == archived
            }
        }
    }
}

/** Centralizes operation progress and one-shot user feedback. */
class OperationCoordinator(initialError: String? = null) {
    var busy by mutableStateOf(false)
        internal set
    var activeMessage by mutableStateOf<String?>(null)
        internal set
    var statusMessage by mutableStateOf<String?>(null)
        internal set
    var errorMessage by mutableStateOf(initialError)
        internal set

    fun begin(message: String): Boolean {
        if (busy) return false
        busy = true
        activeMessage = message
        return true
    }

    fun succeed(message: String) {
        busy = false
        activeMessage = null
        statusMessage = message
        errorMessage = null
    }

    fun fail(error: Throwable) {
        busy = false
        activeMessage = null
        errorMessage = error.message ?: error::class.simpleName ?: "操作失败"
    }

    fun dismiss() {
        statusMessage = null
        errorMessage = null
    }
}
