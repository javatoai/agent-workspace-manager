package com.snowball.awm.desktop

import com.snowball.awm.core.AppConfig
import com.snowball.awm.core.MeegleProjectConfig
import com.snowball.awm.core.RequirementMetadata
import com.snowball.awm.core.RequirementMetadataProvider
import com.snowball.awm.core.TaskManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RequirementControllerTest {
    @Test
    fun `same key shares in flight request and success cache while force bypasses cache`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val calls = AtomicInteger()
        val clock = MutableClock()
        val coordinator = RequirementMetadataCoordinator(
            provider = RequirementMetadataProvider { RequirementMetadata("title", "开发中").also { calls.incrementAndGet() } },
            scope = this,
            ioDispatcher = dispatcher,
            clock = clock,
        )

        val first = async { coordinator.fetch(LINK, "project") }
        val second = async { coordinator.fetch(LINK, "project") }
        advanceUntilIdle()
        assertIs<RequirementFetchResult.Success>(first.await())
        assertIs<RequirementFetchResult.Success>(second.await())
        assertEquals(1, calls.get())

        coordinator.fetch(LINK, "project")
        assertEquals(1, calls.get())
        clock.advance(Duration.ofMinutes(4).plusSeconds(59))
        coordinator.fetch(LINK, "project")
        assertEquals(1, calls.get())
        clock.advance(Duration.ofSeconds(2))
        val expired = async { coordinator.fetch(LINK, "project") }
        advanceUntilIdle()
        expired.await()
        assertEquals(2, calls.get())
        val forced = async { coordinator.fetch(LINK, "project", force = true) }
        advanceUntilIdle()
        forced.await()
        assertEquals(3, calls.get())
    }

    @Test
    fun `failed result backs off and stale task result is discarded`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val calls = AtomicInteger()
        val clock = MutableClock()
        val session = AppSessionStore(
            AppConfig(meegleProjects = listOf(MeegleProjectConfig("project", "obt"))),
            listOf(task("one", LINK)),
        )
        val coordinator = RequirementMetadataCoordinator(
            provider = RequirementMetadataProvider { calls.incrementAndGet(); null },
            scope = this,
            ioDispatcher = dispatcher,
            clock = clock,
        )
        val controller = RequirementController(session, this, coordinator)

        controller.refresh(session.tasks.single())
        session.tasks = emptyList()
        controller.reconcileTasks()
        advanceUntilIdle()
        assertTrue(controller.states.isEmpty())

        val first = async { coordinator.fetch(LINK, "project") }
        advanceUntilIdle()
        assertIs<RequirementFetchResult.Failure>(first.await())
        coordinator.fetch(LINK, "project")
        assertEquals(1, calls.get())
        clock.advance(Duration.ofSeconds(31))
        val retried = async { coordinator.fetch(LINK, "project") }
        advanceUntilIdle()
        retried.await()
        assertEquals(2, calls.get())
    }

    @Test
    fun `metadata coordinator limits concurrent providers to four`() = runBlocking {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val coordinator = RequirementMetadataCoordinator(
            provider = RequirementMetadataProvider {
                val current = active.incrementAndGet()
                maximum.accumulateAndGet(current, ::maxOf)
                Thread.sleep(40)
                active.decrementAndGet()
                RequirementMetadata(status = "开发中")
            },
            scope = scope,
            ioDispatcher = Dispatchers.Default,
        )
        try {
            (1..12).map { index ->
                async { coordinator.fetch("https://project.feishu.cn/obt/bug/detail/$index", "project") }
            }.awaitAll()
            assertTrue(maximum.get() <= 4, "maximum concurrency was ${maximum.get()}")
        } finally {
            scope.cancel()
        }
    }

    private fun task(name: String, link: String) = TaskManifest(
        folderName = name,
        taskDirectoryName = name,
        featureBranch = "feature/$name",
        requirementLink = link,
        createdAt = "2026-08-09 00:00:00",
        updatedAt = "2026-08-09 00:00:00",
        services = emptyList(),
    )

    private companion object {
        const val LINK = "https://project.feishu.cn/obt/userstory/detail/7060612727"
    }

    private class MutableClock(
        private var current: Instant = Instant.parse("2026-08-09T00:00:00Z"),
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = current
        fun advance(duration: Duration) { current = current.plus(duration) }
    }
}
