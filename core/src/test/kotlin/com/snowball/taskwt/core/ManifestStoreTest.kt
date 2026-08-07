package com.snowball.taskwt.core

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ManifestStoreTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `legacy manifests are preserved and reported but not imported`() {
        val taskDirectory = temporary.resolve("legacy-task")
        Files.createDirectories(taskDirectory)
        Files.writeString(
            taskDirectory.resolve(ManifestStore.FILE_NAME),
            """{"schemaVersion":1}""",
        )
        val store = ManifestStore()

        assertThrows(IllegalArgumentException::class.java) { store.load(taskDirectory) }
        val scan = store.scan(temporary)
        kotlin.test.assertTrue(scan.current.isEmpty())
        kotlin.test.assertEquals(listOf(taskDirectory), scan.ignoredLegacyDirectories)
        kotlin.test.assertEquals("""{"schemaVersion":1}""", Files.readString(taskDirectory.resolve(ManifestStore.FILE_NAME)))
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
