package com.snowball.awm.desktop

import com.snowball.awm.core.DevelopmentToolType
import com.snowball.awm.core.ServiceWorkspace
import com.snowball.awm.core.WorkspaceGitHealth
import com.snowball.awm.core.WorkspaceGitHealthState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TaskDetailHeaderPresentationTest {
    @Test
    fun `short participant list stays inline and long list collapses to count`() {
        assertEquals(
            ParticipantSummary("测试：张三、李四", "测试：张三、李四"),
            participantSummary("测试", listOf("张三", "李四")),
        )
        assertEquals(
            ParticipantSummary("产品 3 人", "产品：张三、李四、王五"),
            participantSummary("产品", listOf("张三", "李四", "王五")),
        )
    }

    @Test
    fun `participant summary removes blank and duplicate names`() {
        assertEquals(
            ParticipantSummary("测试：张三", "测试：张三"),
            participantSummary("测试", listOf("", " 张三 ", "张三")),
        )
        assertNull(participantSummary("测试", listOf("", "  ")))
    }

    @Test
    fun `actual branch summary prefers runtime branches deduplicates and marks fallbacks`() {
        val first = workspace("one", "feature/expected-one")
        val duplicate = workspace("two", "feature/expected-two")
        val unavailable = workspace("three", "feature/unverified")

        val summary = actualBranchSummary(listOf(first, duplicate, unavailable)) { workspace ->
            when (workspace.moduleId) {
                "one", "two" -> WorkspaceGitHealth(WorkspaceGitHealthState.READY, actualBranch = "feature/current")
                else -> WorkspaceGitHealth(WorkspaceGitHealthState.FAILED)
            }
        }

        assertEquals(
            listOf(
                ActualBranchSummaryItem("feature/current", verified = true),
                ActualBranchSummaryItem("feature/unverified", verified = false),
            ),
            summary,
        )
        assertEquals(listOf("feature/current", "feature/unverified（未验证）"), summary.map(ActualBranchSummaryItem::displayText))
    }

    @Test
    fun `header hidden branches use exact case sensitive matching`() {
        val master = workspace("master", "master")
        val capitalized = workspace("capitalized", "Master")
        val feature = workspace("feature", "feature/one")

        val visible = visibleActualBranchSummary(
            listOf(master, capitalized, feature),
            health = { WorkspaceGitHealth(WorkspaceGitHealthState.READY, actualBranch = it.branch) },
            hiddenBranches = listOf("master"),
        )

        assertEquals(listOf("Master", "feature/one"), visible.map(ActualBranchSummaryItem::branch))
    }

    private fun workspace(moduleId: String, branch: String) = ServiceWorkspace(
        repositoryId = "repo-$moduleId",
        serviceName = moduleId,
        repositoryPath = "D:/repo-$moduleId",
        worktreePath = "D:/task/$moduleId",
        developmentTool = DevelopmentToolType.INTELLIJ_IDEA,
        branch = branch,
        moduleId = moduleId,
    )
}
