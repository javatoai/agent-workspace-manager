package com.snowball.taskwt.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class EventLogTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `writes one json object per event without source contents`() {
        val clock = Clock.fixed(Instant.parse("2026-07-30T08:00:00Z"), ZoneOffset.UTC)
        val paths = ApplicationPaths(temporary.resolve("home"))
        val sink = JsonlEventSink(paths, clock)

        sink.info(
            event = "task.create.completed",
            message = "任务创建完成",
            metadata = mapOf("folderName" to "OBT-1"),
            clock = clock,
        )

        val log = paths.logs.resolve("application-2026-07-30.jsonl")
        assertTrue(Files.exists(log))
        val lines = Files.readAllLines(log)
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("\"folderName\":\"OBT-1\""))
        assertTrue(lines.single().contains("\"event\":\"task.create.completed\""))
    }
}
