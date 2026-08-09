package com.snowball.awm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TaskBranchInfoFormatterTest {
    @Test
    fun `formats requirement link before service branches in task order`() {
        val task = TaskManifest(
            folderName = "苹果月报",
            taskDirectoryName = "苹果月报",
            featureBranch = "feature/apple-report",
            requirementLink = "https://project.feishu.cn/obt/userstory/detail/7060612727",
            createdAt = "2026-08-05T00:00:00Z",
            updatedAt = "2026-08-05T00:00:00Z",
            lifecycleStatus = TaskLifecycleStatus.ACTIVE,
            services = listOf(
                workspace("api-service", "feature/api"),
                workspace("job-manager", "feature/job"),
            ),
        )

        assertEquals(
            """需求链接：https://project.feishu.cn/obt/userstory/detail/7060612727

api-service：feature/api
job-manager：feature/job""",
            TaskBranchInfoFormatter.format(task),
        )
    }

    @Test
    fun `omits link heading when requirement link is blank`() {
        val task = TaskManifest(
            folderName = "任务",
            taskDirectoryName = "任务",
            featureBranch = "feature/task",
            createdAt = "2026-08-05T00:00:00Z",
            updatedAt = "2026-08-05T00:00:00Z",
            lifecycleStatus = TaskLifecycleStatus.ACTIVE,
            services = listOf(workspace("api-service", "feature/task")),
        )

        assertEquals("api-service：feature/task", TaskBranchInfoFormatter.format(task))
    }

    private fun workspace(name: String, branch: String) = ServiceWorkspace(
        repositoryId = name,
        serviceName = name,
        repositoryPath = "D:/$name",
        worktreePath = "D:/task/$name",
        ideType = IdeType.IDEA,
        branch = branch,
        health = WorkspaceHealth.READY,
    )
}
