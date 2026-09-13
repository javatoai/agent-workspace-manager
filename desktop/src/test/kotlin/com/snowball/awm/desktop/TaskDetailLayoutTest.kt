package com.snowball.awm.desktop

import com.snowball.awm.core.LocalPushState
import com.snowball.awm.core.WorkspaceGitHealth
import com.snowball.awm.core.WorkspaceGitHealthState
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
    fun `workspace card stacks actions below the 860 dp action layout breakpoint`() {
        assertEquals(WorkspaceCardLayout.STACKED, workspaceCardLayout(0f))
        assertEquals(WorkspaceCardLayout.STACKED, workspaceCardLayout(720f))
        assertEquals(WorkspaceCardLayout.STACKED, workspaceCardLayout(859.9f))
        assertEquals(WorkspaceCardLayout.SIDE_BY_SIDE, workspaceCardLayout(860f))
        assertEquals(WorkspaceCardLayout.SIDE_BY_SIDE, workspaceCardLayout(1200f))
    }

    @Test
    fun `wide workspace cards right align actions while stacked cards retain their reading order`() {
        assertEquals(true, workspaceCardActionsAlignToEnd(WorkspaceCardLayout.SIDE_BY_SIDE))
        assertEquals(false, workspaceCardActionsAlignToEnd(WorkspaceCardLayout.STACKED))
    }

    @Test
    fun `workspace card title retains the project and module names`() {
        assertEquals("awm-test-project · default", workspaceCardTitle("awm-test-project", "default"))
        assertEquals("awm-test-project · module-2", workspaceCardTitle("awm-test-project", "module-2"))
        assertEquals("awm-test-project", workspaceCardTitle("awm-test-project", "awm-test-project"))
    }

    @Test
    fun `project name copy omits the module name`() {
        assertEquals("awm-test-project", workspaceProjectNameForCopy(" awm-test-project "))
        assertEquals("awm-test-project · module-2", workspaceCardTitle("awm-test-project", "module-2"))
    }

    @Test
    fun `short branch keeps copy directly after its natural text width`() {
        val allocation = workspaceBranchCopyAllocation(
            availableWidth = 1_000,
            naturalBranchWidth = 420,
            copyWidth = 28,
            gapWidth = 7,
        )

        assertEquals(420, allocation.branchWidth)
        assertEquals(427, allocation.copyX)
    }

    @Test
    fun `long branch reserves the copy action while its text can wrap`() {
        val allocation = workspaceBranchCopyAllocation(
            availableWidth = 500,
            naturalBranchWidth = 800,
            copyWidth = 28,
            gapWidth = 7,
        )

        assertEquals(465, allocation.branchWidth)
        assertEquals(472, allocation.copyX)
    }

    @Test
    fun `ready workspace exposes a concise git status on its third line`() {
        val health = WorkspaceGitHealth(
            state = WorkspaceGitHealthState.READY,
            dirtyFileCount = 0,
            pushState = LocalPushState.REMOTE_BRANCH_MISSING,
        )

        assertEquals(listOf("无未提交", "未发现远程分支"), workspaceGitStatusLabels(health))
    }

    @Test
    fun `problem workspace retains a dedicated repair row`() {
        val health = WorkspaceGitHealth(
            state = WorkspaceGitHealthState.FAILED,
            dirtyFileCount = 0,
            pushState = LocalPushState.FAILED,
        )
        assertEquals(WorkspaceStatusPlacement.THIRD_ROW, workspaceStatusPlacement(health))
    }

}
