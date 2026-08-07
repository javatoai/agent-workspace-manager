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
    fun `rejects legacy manifests instead of silently hiding them`() {
        val taskDirectory = temporary.resolve("legacy-task")
        Files.createDirectories(taskDirectory)
        Files.writeString(
            taskDirectory.resolve(ManifestStore.FILE_NAME),
            """{"schemaVersion":1}""",
        )
        val store = ManifestStore()

        assertThrows(IllegalArgumentException::class.java) { store.load(taskDirectory) }
        assertThrows(IllegalArgumentException::class.java) { store.list(temporary) }
    }

    @Test
    fun `rejects manifests without a schema version`() {
        val taskDirectory = temporary.resolve("missing-version")
        Files.createDirectories(taskDirectory)
        Files.writeString(taskDirectory.resolve(ManifestStore.FILE_NAME), "{}")

        assertThrows(IllegalArgumentException::class.java) { ManifestStore().load(taskDirectory) }
    }
}
