package com.snowball.awm.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TagMergeConflictMessageTest {
    @Test
    fun `conflict message names the actual feature to target merge direction`() {
        assertEquals(
            "自动将 feature/task-42 合入 origin/release/test 时检测到冲突，请手工解决后提交并推送 origin/release/test，再重试",
            tagMergeConflictMessage("feature/task-42", "origin", "release/test"),
        )
    }
}
