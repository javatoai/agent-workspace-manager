package com.snowball.awm.desktop

import com.snowball.awm.core.TagOperation
import com.snowball.awm.core.TagOperationState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TagPresentationTest {
    @Test
    fun `tag history filter matches service task tag branch and message`() {
        val operation = operation(TagOperationState.FAILED)

        assertTrue(tagHistoryMatchesQuery(operation, "operation-center"))
        assertTrue(tagHistoryMatchesQuery(operation, "task-42"))
        assertTrue(tagHistoryMatchesQuery(operation, "v1.2.3"))
        assertTrue(tagHistoryMatchesQuery(operation, "release/test"))
        assertTrue(tagHistoryMatchesQuery(operation, "冲突"))
        assertFalse(tagHistoryMatchesQuery(operation, "unmatched"))
    }

    @Test
    fun `only failed partial and conflict operations are problem records`() {
        assertTrue(tagOperationIsProblem(operation(TagOperationState.FAILED)))
        assertTrue(tagOperationIsProblem(operation(TagOperationState.PARTIAL)))
        assertTrue(tagOperationIsProblem(operation(TagOperationState.CONFLICT)))
        assertFalse(tagOperationIsProblem(operation(TagOperationState.SUCCESS)))
    }

    private fun operation(state: TagOperationState) = TagOperation(
        operationId = "operation-42-$state",
        folderName = "task-42",
        serviceName = "operation-center",
        repositoryId = "operation-center",
        sourceBranch = "feature/task-42",
        targetBranch = "release/test",
        remote = "origin",
        state = state,
        createdAt = "2026-08-20 10:00:00",
        updatedAt = "2026-08-20 10:00:00",
        tag = "v1.2.3",
        message = "合并存在冲突",
    )
}
