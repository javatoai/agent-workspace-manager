package com.snowball.awm.desktop

import com.snowball.awm.core.AppConfig
import com.snowball.awm.core.TaskManifest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FeatureControllersTest {
    @Test
    fun `task controller is independently injectable and exposes immutable snapshot`() {
        val task = TaskManifest(
            folderName = "task",
            taskDirectoryName = "task",
            featureBranch = "feature/task",
            createdAt = "2026-08-09 00:00:00",
            updatedAt = "2026-08-09 00:00:00",
            services = emptyList(),
        )
        var selected: TaskManifest? = null
        var refreshed = false
        val controller = TaskController(
            stateProvider = { TaskUiState(AppConfig(), listOf(task), selected, emptyMap(), busy = false) },
            selectAction = { selected = it },
            refreshAction = { refreshed = true },
            createAction = { _, _, _, _, _, _, _ -> true },
            archiveAction = {},
            restoreAction = {},
            deleteAction = { _, _ -> },
            addServicesAction = { _, _ -> },
            retryAction = { _, _ -> },
        )

        controller.select(task)
        controller.refresh()

        assertEquals(task, controller.state.selectedTask)
        assertTrue(refreshed)
        assertEquals(listOf(task), controller.state.tasks)
    }
}
