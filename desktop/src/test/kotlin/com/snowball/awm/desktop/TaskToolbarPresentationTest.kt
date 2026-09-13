package com.snowball.awm.desktop

import com.snowball.awm.core.AppConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskToolbarPresentationTest {
    @Test
    fun `task toolbar action groups are hidden by default`() {
        val presentation = taskDetailToolbarPresentationFor(AppConfig())

        assertFalse(presentation.showPathActionGroup)
        assertFalse(presentation.showGitActionGroup)
    }

    @Test
    fun `task toolbar independently exposes optional path and git action groups`() {
        listOf(
            false to false,
            false to true,
            true to false,
            true to true,
        ).forEach { (showPath, showGit) ->
            val presentation = taskDetailToolbarPresentationFor(
                AppConfig(
                    showTaskDetailPathActionGroup = showPath,
                    showTaskDetailGitActionGroup = showGit,
                ),
            )

            assertEquals(showPath, presentation.showPathActionGroup)
            assertEquals(showGit, presentation.showGitActionGroup)
        }
    }

    @Test
    fun `task toolbar path and git controls remain distinct`() {
        val pathOnly = taskDetailToolbarPresentationFor(AppConfig(showTaskDetailPathActionGroup = true))
        val gitOnly = taskDetailToolbarPresentationFor(AppConfig(showTaskDetailGitActionGroup = true))

        assertTrue(pathOnly.showPathActionGroup)
        assertFalse(pathOnly.showGitActionGroup)
        assertFalse(gitOnly.showPathActionGroup)
        assertTrue(gitOnly.showGitActionGroup)
    }
}
