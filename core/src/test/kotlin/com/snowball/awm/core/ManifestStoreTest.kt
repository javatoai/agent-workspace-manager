package com.snowball.awm.core

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ManifestStoreTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `schema five round trip preserves workspace tool launch results`() {
        val directory = temporary.resolve("tool-launch")
        val store = ManifestStore()
        val expected = TaskManifest(
            folderName = "tool-launch",
            taskDirectoryName = "tool-launch",
            featureBranch = "feature/tool-launch",
            createdAt = "2026-08-08 12:00:00",
            updatedAt = "2026-08-08 12:00:01",
            status = WorkspaceStatus.READY,
            services = emptyList(),
            workspaceToolLaunches = listOf(
                WorkspaceToolLaunch(
                    toolId = "codex",
                    status = WorkspaceToolLaunchStatus.OPENED,
                    updatedAt = "2026-08-08 12:00:01",
                ),
                WorkspaceToolLaunch(
                    toolId = "cursor",
                    status = WorkspaceToolLaunchStatus.FAILED,
                    updatedAt = "2026-08-08 12:00:01",
                    message = "not installed",
                ),
            ),
        )

        store.save(directory, expected)

        assertEquals("0.4.2", store.load(directory).schemaVersion)
        assertEquals(expected, store.load(directory))
    }

    @Test
    fun `unsupported manifests are preserved and reported but not imported`() {
        val taskDirectory = temporary.resolve("legacy-task")
        Files.createDirectories(taskDirectory)
        Files.writeString(
            taskDirectory.resolve(ManifestStore.FILE_NAME),
            """{"schemaVersion":4}""",
        )
        val store = ManifestStore()

        assertThrows(IllegalArgumentException::class.java) { store.load(taskDirectory) }
        val scan = store.scan(temporary)
        kotlin.test.assertTrue(scan.current.isEmpty())
        kotlin.test.assertEquals(listOf(taskDirectory), scan.unsupportedDirectories)
        kotlin.test.assertEquals("""{"schemaVersion":4}""", Files.readString(taskDirectory.resolve(ManifestStore.FILE_NAME)))
    }

    @Test
    fun `manifest from another patch release is compatible`() {
        val taskDirectory = temporary.resolve("compatible-patch")
        Files.createDirectories(taskDirectory)
        Files.writeString(
            taskDirectory.resolve(ManifestStore.FILE_NAME),
            """{"schemaVersion":"0.4.0","folderName":"compatible","taskDirectoryName":"compatible","featureBranch":"feature/compatible","createdAt":"2026-08-09 00:00:00","updatedAt":"2026-08-09 00:00:00","status":"READY","services":[]}""",
        )

        val store = ManifestStore()
        val manifest = store.load(taskDirectory)
        assertEquals("0.4.0", manifest.schemaVersion)
        store.save(taskDirectory, manifest)
        assertEquals(CURRENT_TASK_MANIFEST_SCHEMA_VERSION, store.load(taskDirectory).schemaVersion)
    }

    @Test
    fun `rejects manifests without a schema version`() {
        val taskDirectory = temporary.resolve("missing-version")
        Files.createDirectories(taskDirectory)
        Files.writeString(taskDirectory.resolve(ManifestStore.FILE_NAME), "{}")

        assertThrows(IllegalArgumentException::class.java) { ManifestStore().load(taskDirectory) }
    }

    @Test
    fun `corrupt manifest is reported without hiding valid tasks`() {
        val store = ManifestStore()
        val validDirectory = temporary.resolve("valid")
        store.save(
            validDirectory,
            TaskManifest(
                folderName = "valid",
                taskDirectoryName = "valid",
                featureBranch = "feature/valid",
                createdAt = "2026-08-08T00:00:00Z",
                updatedAt = "2026-08-08T00:00:00Z",
                status = WorkspaceStatus.READY,
                services = emptyList(),
            ),
        )
        val brokenDirectory = temporary.resolve("broken")
        Files.createDirectories(brokenDirectory)
        Files.writeString(brokenDirectory.resolve(ManifestStore.FILE_NAME), "{not-json")

        val scan = store.scan(temporary)

        kotlin.test.assertEquals(listOf("valid"), scan.current.map { it.second.folderName })
        kotlin.test.assertEquals(setOf(brokenDirectory), scan.failures.keys)
    }
}
