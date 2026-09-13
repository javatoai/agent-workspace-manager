package com.snowball.awm.desktop

import com.snowball.awm.core.AppConfig
import com.snowball.awm.core.DevelopmentToolType
import com.snowball.awm.core.ServiceWorkspace
import com.snowball.awm.core.WorkspaceHealth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceToolbarPresentationTest {
    @Test
    fun `toolbar shows one shortcut for the workspace configured development tool`() {
        val presentation = workspaceToolbarPresentationFor(
            workspace = workspace(developmentTool = DevelopmentToolType.VISUAL_STUDIO_CODE),
            canBuildTag = true,
            showAddModule = true,
            config = AppConfig(),
        )

        assertEquals(DevelopmentToolType.VISUAL_STUDIO_CODE, presentation.developmentTool)
        assertEquals("使用 Visual Studio Code 打开", presentation.developmentToolActionLabel)
    }

    @Test
    fun `toolbar conditionally exposes tag module and retry groups`() {
        val ready = workspace(health = WorkspaceHealth.READY)
        val readyPresentation = workspaceToolbarPresentationFor(
            ready,
            canBuildTag = false,
            showAddModule = false,
            config = AppConfig(),
        )
        assertFalse(readyPresentation.showTagAction)
        assertFalse(readyPresentation.showAddModuleAction)
        assertFalse(readyPresentation.showRetryAction)

        val failed = workspace(health = WorkspaceHealth.FAILED, groupServiceId = "orders")
        val failedPresentation = workspaceToolbarPresentationFor(
            failed,
            canBuildTag = true,
            showAddModule = true,
            config = AppConfig(),
        )
        assertTrue(failedPresentation.showTagAction)
        assertTrue(failedPresentation.showAddModuleAction)
        assertTrue(failedPresentation.showRetryAction)
    }

    @Test
    fun `toolbar independently exposes optional path and git action groups`() {
        val workspace = workspace()
        listOf(
            false to false,
            false to true,
            true to false,
            true to true,
        ).forEach { (showPath, showGit) ->
            val presentation = workspaceToolbarPresentationFor(
                workspace = workspace,
                canBuildTag = true,
                showAddModule = true,
                config = AppConfig(
                    showWorkspacePathActionGroup = showPath,
                    showWorkspaceGitActionGroup = showGit,
                ),
            )

            assertEquals(showPath, presentation.showPathActionGroup)
            assertEquals(showGit, presentation.showGitActionGroup)
            assertTrue(presentation.showTagAction)
            assertTrue(presentation.showAddModuleAction)
            assertFalse(presentation.showRetryAction)
        }
    }

    private fun workspace(
        developmentTool: DevelopmentToolType = DevelopmentToolType.INTELLIJ_IDEA,
        health: WorkspaceHealth = WorkspaceHealth.READY,
        groupServiceId: String = "orders",
    ) = ServiceWorkspace(
        repositoryId = "repo-orders",
        serviceName = "orders",
        repositoryPath = "/tmp/orders",
        worktreePath = "/tmp/task/orders",
        developmentTool = developmentTool,
        branch = "feature/orders",
        health = health,
        groupServiceId = groupServiceId,
    )
}
