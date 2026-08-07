package com.snowball.taskwt.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

class AgentDocumentServiceTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `task document combines three levels and preserves the direct edit area`() {
        val paths = ApplicationPaths(temporary.resolve("home"))
        val service = AgentDocumentService(paths)
        service.saveGlobal("全局约定")
        service.saveGroup("growth", "增长组约定")
        val taskDirectory = temporary.resolve("tasks").resolve("OBT-123")
        val manifest = manifest(groupId = "growth", updatedAt = "2026-08-08T00:00:00Z")

        service.writeTaskDocument(taskDirectory, manifest, emptyList(), "任务第一次说明")
        val first = Files.readString(taskDirectory.resolve("AGENTS.md"))
        assertTrue(first.indexOf("全局约定") < first.indexOf("增长组约定"))
        assertTrue(first.indexOf("增长组约定") < first.indexOf("任务第一次说明"))

        val directlyEdited = first.replace("任务第一次说明", "模型直接追加的任务说明")
        Files.writeString(taskDirectory.resolve("AGENTS.md"), directlyEdited)
        service.saveGlobal("更新后的全局约定")
        service.writeTaskDocument(
            taskDirectory,
            manifest.copy(updatedAt = "2026-08-08T01:00:00Z"),
            emptyList(),
        )

        val refreshed = Files.readString(taskDirectory.resolve("AGENTS.md"))
        assertTrue(refreshed.contains("更新后的全局约定"))
        assertTrue(refreshed.contains("模型直接追加的任务说明"))
        assertTrue(refreshed.contains("2026-08-08T01:00:00Z"))
    }

    @Test
    fun `malformed task markers stop regeneration without changing the file`() {
        val paths = ApplicationPaths(temporary.resolve("home"))
        val service = AgentDocumentService(paths)
        val taskDirectory = temporary.resolve("tasks").resolve("broken")
        Files.createDirectories(taskDirectory)
        val malformed = "人工文件，没有 TaskWT 标记"
        Files.writeString(taskDirectory.resolve("AGENTS.md"), malformed)

        assertThrows(AgentDocumentFormatException::class.java) {
            service.writeTaskDocument(taskDirectory, manifest(), emptyList())
        }
        assertEquals(malformed, Files.readString(taskDirectory.resolve("AGENTS.md")))
    }

    @Test
    fun `reserved markers are rejected in user maintained content`() {
        val service = AgentDocumentService(ApplicationPaths(temporary.resolve("home-reserved")))

        kotlin.test.assertFailsWith<IllegalArgumentException> {
            service.saveGlobal("do not write ${AgentDocumentService.TASK_NOTES_BEGIN}")
        }
    }

    private fun manifest(
        groupId: String = DEFAULT_GROUP_ID,
        updatedAt: String = Instant.EPOCH.toString(),
    ) = TaskManifest(
        folderName = "OBT-123",
        taskDirectoryName = "OBT-123",
        featureBranch = "feature/OBT-123",
        requirementLink = "需求",
        createdAt = Instant.EPOCH.toString(),
        updatedAt = updatedAt,
        status = WorkspaceStatus.READY,
        services = emptyList(),
        groupId = groupId,
    )
}
