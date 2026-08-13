package com.snowball.awm.desktop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperationRunnerTest {
    @Test
    fun `failure details include cause and suppressed errors`() {
        val cause = IllegalStateException("git stderr")
        val error = IllegalArgumentException("创建失败", cause).apply { addSuppressed(IllegalStateException("回滚失败")) }

        val details = OperationFailureDetails.format(error)

        assertEquals("创建失败\n\n原因：git stderr\n\n附加失败：回滚失败", details)
    }

    @Test
    fun `completion callback failure is reported instead of showing success`() = runTest {
        val recorded = mutableListOf<Throwable>()
        val coordinator = OperationCoordinator(onError = recorded::add)
        val runner = OperationRunner(coordinator, this, StandardTestDispatcher(testScheduler))

        runner.run("进行中", "已完成", block = { "result" }) {
            error("刷新界面失败")
        }
        testScheduler.advanceUntilIdle()

        assertFalse(coordinator.busy)
        assertEquals(null, coordinator.statusMessage)
        assertEquals("刷新界面失败", coordinator.errorMessage)
        assertEquals(listOf("刷新界面失败"), recorded.map { it.message })
    }

    @Test
    fun `rejected operation invokes failure callback so an auto save draft can roll back`() = runTest {
        val coordinator = OperationCoordinator()
        val runner = OperationRunner(coordinator, this, Dispatchers.IO)
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val rejected = mutableListOf<Throwable>()

        runner.run("正在保存第一项", "第一项已保存", block = {
            started.complete(Unit)
            release.await()
        })
        started.await()

        val accepted = runner.run(
            "正在保存第二项",
            "第二项已保存",
            block = { error("忙碌时不应执行第二项") },
            onFailure = rejected::add,
        )

        assertFalse(accepted)
        assertEquals(listOf("另一个操作正在执行，请稍候"), rejected.map { it.message })
        assertTrue(coordinator.busy)

        release.countDown()
        withTimeout(5_000) {
            while (coordinator.busy) delay(10)
        }
    }

    @Test
    fun `cancelling an interruptible operation waits for worker shutdown`() = runTest {
        val coordinator = OperationCoordinator()
        val runner = OperationRunner(coordinator, this, Dispatchers.IO)
        val started = CompletableDeferred<Unit>()

        runner.run("正在执行…", "不应成功", cancellable = true, block = {
            started.complete(Unit)
            Thread.sleep(60_000)
        })
        started.await()

        assertTrue(runner.cancel())
        assertTrue(coordinator.busy)
        assertTrue(coordinator.cancelling)
        withTimeout(5_000) {
            while (coordinator.busy) delay(10)
        }

        assertFalse(coordinator.busy)
        assertEquals("操作已取消", coordinator.statusMessage)
        assertEquals(null, coordinator.errorMessage)
    }
}
