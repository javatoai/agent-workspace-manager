package com.snowball.awm.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.nio.file.Files

class TaskDetailLayoutTest {
    @Test
    fun `ready materials actions belong to the work data group`() {
        val directory = Files.createTempDirectory("awm-materials-actions-").toFile()
        try {
            assertEquals(RequirementMaterialsActionGroup.WORK_DATA, requirementMaterialsActionGroupFor(directory.absolutePath))
            Files.delete(directory.toPath())
            assertNull(requirementMaterialsActionGroupFor(directory.absolutePath))
        } finally {
            directory.deleteRecursively()
        }
        assertNull(requirementMaterialsActionGroupFor(null))
        assertNull(requirementMaterialsActionGroupFor("   "))
    }

    @Test
    fun `workspace summary and actions always remain in one horizontal card row`() {
        assertEquals(WorkspaceCardLayout.SIDE_BY_SIDE, workspaceCardLayout(720f))
        assertEquals(WorkspaceCardLayout.SIDE_BY_SIDE, workspaceCardLayout(919f))
        assertEquals(WorkspaceCardLayout.SIDE_BY_SIDE, workspaceCardLayout(920f))
        assertEquals(WorkspaceCardLayout.SIDE_BY_SIDE, workspaceCardLayout(1200f))
    }

    @Test
    fun `short branch keeps copy and git status immediately adjacent`() {
        val allocation = workspaceBranchRowAllocation(
            availableWidth = 1_000,
            naturalBranchWidth = 420,
            copyWidth = 30,
            statusWidth = 150,
            gapWidth = 7,
        )

        assertEquals(420, allocation.branchWidth)
        assertEquals(427, allocation.copyX)
        assertEquals(464, allocation.statusX)
    }

    @Test
    fun `long branch shrinks before copy and git status`() {
        val allocation = workspaceBranchRowAllocation(
            availableWidth = 1_000,
            naturalBranchWidth = 900,
            copyWidth = 30,
            statusWidth = 150,
            gapWidth = 7,
        )

        assertEquals(806, allocation.branchWidth)
        assertEquals(813, allocation.copyX)
        assertEquals(850, allocation.statusX)
    }
}
