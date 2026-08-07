package com.snowball.taskwt.desktop

import com.snowball.taskwt.core.AppConfig
import com.snowball.taskwt.core.ApplicationPaths
import com.snowball.taskwt.core.ConfigStore
import com.snowball.taskwt.core.ManifestStore
import com.snowball.taskwt.core.RepositoryConfig
import com.snowball.taskwt.core.RepositoryInspector
import com.snowball.taskwt.core.RequirementInfoClient
import com.snowball.taskwt.core.TaskManifest
import com.snowball.taskwt.core.WorkspaceStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class AppControllerTest {
    @Test
    fun `startup reads persisted arrays and selects newest task without external refresh`() {
        val root = Files.createTempDirectory("taskwt-desktop-startup")
        val paths = ApplicationPaths(root.resolve("home"))
        val taskRoot = root.resolve("tasks")
        val configStore = ConfigStore(paths)
        val manifests = ManifestStore()
        configStore.save(
            AppConfig(
                taskRoot = taskRoot.toString(),
                repositories = listOf(
                    RepositoryConfig("repo-1", "service", root.resolve("missing").toString(), "missing"),
                ),
            ),
        )
        manifests.save(taskRoot.resolve("older"), task("older", "2026-08-01T00:00:00Z"))
        manifests.save(taskRoot.resolve("newer"), task("newer", "2026-08-02T00:00:00Z"))
        var repositoryInspections = 0
        var remoteRequests = 0
        val controller = AppController(
            paths = paths,
            configStore = configStore,
            manifests = manifests,
            repositoryInspector = RepositoryInspector {
                repositoryInspections++
                error("startup must not inspect repositories")
            },
            requirementInfoClient = RequirementInfoClient {
                remoteRequests++
                error("startup must not call Meegle")
            },
        )

        assertEquals(NavigationItem.TASKS, controller.navigation)
        assertEquals(listOf("newer", "older"), controller.tasks.map { it.folderName })
        assertEquals("newer", controller.selectedTask?.folderName)
        assertEquals(0, repositoryInspections)
        assertEquals(0, remoteRequests)
        controller.close()
    }

    private fun task(name: String, updatedAt: String) = TaskManifest(
        folderName = name,
        taskDirectoryName = name,
        featureBranch = "feature/$name",
        createdAt = updatedAt,
        updatedAt = updatedAt,
        status = WorkspaceStatus.READY,
        services = emptyList(),
    )
}
