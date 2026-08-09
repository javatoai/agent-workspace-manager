package com.snowball.awm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TagOutputFormatterTest {
    @Test
    fun `formats successful tags in input order without a success heading`() {
        val first = operation("android-transit-service", "3.11.69.beta-1")
        val second = operation("api-service", "2.4.34.beta-6")

        assertEquals(
            """需求链接：https://project.feishu.cn/obt/userstory/detail/7035269559

android-transit-service · 3.11.69.beta-1
api-service · 2.4.34.beta-6

Tag已构建完毕，辛苦UAT环境发布以上版本""",
            TagOutputFormatter.format(
                "https://project.feishu.cn/obt/userstory/detail/7035269559",
                listOf(first, second),
                includeFailures = false,
            ),
        )
    }

    @Test
    fun `omits blank requirement link and includes failures in detailed output`() {
        val success = operation("api-service", "2.4.34.beta-6")
        val failure = operation("job-manager", null).copy(
            state = TagOperationState.FAILED,
            message = "远端推送失败",
        )

        assertEquals(
            """api-service · 2.4.34.beta-6

构建失败：
job-manager · FAILED · 远端推送失败

Tag未全部构建成功，请处理失败项后重试""",
            TagOutputFormatter.format("", listOf(success, failure), includeFailures = true),
        )
    }

    private fun operation(serviceName: String, tag: String?): TagOperation = TagOperation(
        operationId = serviceName,
        folderName = "TASK-1",
        serviceName = serviceName,
        repositoryId = serviceName,
        featureBranch = "feature/TASK-1",
        testBranch = "release/test",
        remote = "origin",
        state = TagOperationState.SUCCESS,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        tag = tag,
    )
}
