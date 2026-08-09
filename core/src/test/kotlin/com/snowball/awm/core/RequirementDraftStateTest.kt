package com.snowball.awm.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RequirementDraftStateTest {
    @Test
    fun `metadata fills untouched task name`() {
        val linked = RequirementDraftState().changeRequirement(
            "https://project.feishu.cn/obt/userstory/detail/7060612727",
            "feature/zhangsan_{num}_",
        )
        val result = linked.applyMetadata(linked.requirementLink, RequirementMetadata("优化支付流程", null))
        assertEquals("优化支付流程", result.taskName)
        assertEquals("feature/zhangsan_7060612727_", result.branch)
        assertFalse(result.metadataLoading)
    }

    @Test
    fun `manual name and branch are never overwritten`() {
        val state = RequirementDraftState()
            .changeRequirement("https://project.feishu.cn/obt/userstory/detail/1", "feature/{num}_")
            .editName("人工名称")
            .editBranch("feature/manual")
            .changeRequirement("https://project.feishu.cn/obt/userstory/detail/2", "feature/{num}_")
            .applyMetadata("https://project.feishu.cn/obt/userstory/detail/2", RequirementMetadata("异步标题", null))
        assertEquals("人工名称", state.taskName)
        assertEquals("feature/manual", state.branch)
    }

    @Test
    fun `stale metadata result is ignored`() {
        val current = RequirementDraftState().changeRequirement(
            "https://project.feishu.cn/obt/userstory/detail/2",
            "feature/{num}_",
        )
        assertEquals(current, current.applyMetadata("https://project.feishu.cn/obt/userstory/detail/1", RequirementMetadata("旧标题", null)))
    }
}
