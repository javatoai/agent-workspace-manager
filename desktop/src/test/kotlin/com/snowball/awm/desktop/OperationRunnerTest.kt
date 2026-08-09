package com.snowball.awm.desktop

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class OperationRunnerTest {
    @Test
    fun `completion callback failure is reported instead of showing success`() = runTest {
        val coordinator = OperationCoordinator()
        val runner = OperationRunner(coordinator, this, StandardTestDispatcher(testScheduler))

        runner.run("进行中", "已完成", block = { "result" }) {
            error("刷新界面失败")
        }
        testScheduler.advanceUntilIdle()

        assertFalse(coordinator.busy)
        assertEquals(null, coordinator.statusMessage)
        assertEquals("刷新界面失败", coordinator.errorMessage)
    }
}
