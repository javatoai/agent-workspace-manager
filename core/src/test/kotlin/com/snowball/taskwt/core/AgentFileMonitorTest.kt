package com.snowball.taskwt.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentFileMonitorTest {
    @Test
    fun `external change reloads clean editor but conflicts with unsaved edit`() {
        val root = Files.createTempDirectory("agent-monitor-")
        val file = root.resolve("AGENTS.md")
        Files.writeString(file, "v1")
        val changes = mutableListOf<AgentFileChange>()
        AgentFileMonitor(changes::add, startWatchThread = false).use { monitor ->
            monitor.track(file)
            Files.writeString(file, "v2")
            monitor.checkNow()
            assertEquals("v2", assertIs<AgentFileChange.Reloaded>(changes.removeLast()).content)

            monitor.markLocalEdit(file, "local-v3")
            Files.writeString(file, "disk-v3")
            monitor.checkNow()
            val conflict = assertIs<AgentFileChange.Conflict>(changes.removeLast())
            assertEquals("local-v3", conflict.localContent)
            assertEquals("disk-v3", conflict.diskContent)

            monitor.resolve(file, AgentConflictResolution.USE_LOCAL)
            assertEquals("local-v3", Files.readString(file))
            assertTrue(monitor.snapshot(file)?.dirty == false)
        }
    }

    @Test
    fun `focus fallback compares hashes even when timestamp is unchanged`() {
        val root = Files.createTempDirectory("agent-hash-")
        val file = root.resolve("AGENTS.md")
        Files.writeString(file, "one")
        val originalTime = Files.getLastModifiedTime(file)
        val changes = mutableListOf<AgentFileChange>()
        AgentFileMonitor(changes::add, startWatchThread = false).use { monitor ->
            monitor.track(file)
            Files.writeString(file, "two")
            Files.setLastModifiedTime(file, originalTime)

            monitor.checkNow()

            assertEquals("two", assertIs<AgentFileChange.Reloaded>(changes.single()).content)
        }
    }

    @Test
    fun `save rechecks disk hash and cannot race past a pending watcher event`() {
        val root = Files.createTempDirectory("agent-save-race-")
        val file = root.resolve("AGENTS.md")
        Files.writeString(file, "v1")
        val changes = mutableListOf<AgentFileChange>()
        AgentFileMonitor(changes::add, startWatchThread = false).use { monitor ->
            monitor.track(file)
            monitor.markLocalEdit(file, "local")
            Files.writeString(file, "external")

            assertFailsWith<AgentDocumentConflictException> { monitor.save(file, "local") }
            assertEquals("external", Files.readString(file))
            assertIs<AgentFileChange.Conflict>(changes.single())
        }
    }
}
