package com.snowball.awm.core

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigRecoveryTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `restore recovers malformed current configuration and preserves original bytes separately`() {
        val paths = ApplicationPaths(temporary.resolve("home"))
        val store = ConfigStore(paths)
        store.save(AppConfig(taskRoot = "D:/first"))
        store.save(AppConfig(taskRoot = "D:/second"))
        val backup = store.backups().single().path
        val damaged = "{ malformed current configuration\r\n".toByteArray()
        Files.write(paths.config, damaged)

        assertEquals("D:/first", store.restore(backup).taskRoot)
        assertEquals("D:/first", store.load().taskRoot)
        assertContentEquals(damaged, Files.readAllBytes(corruptCopies(paths).single()))
        assertEquals(listOf(backup), store.backups().map { it.path })
    }

    @Test
    fun `import preview is read only and explicit import preserves malformed bytes`() {
        val source = validImport()
        val paths = ApplicationPaths(temporary.resolve("home"))
        Files.createDirectories(paths.home)
        val damaged = "{ damaged configuration".toByteArray()
        Files.write(paths.config, damaged)
        val store = ConfigStore(paths)

        assertTrue(store.previewImport(source).changes.any { it.contains("先保留原文件") })
        assertContentEquals(damaged, Files.readAllBytes(paths.config))
        assertFalse(Files.exists(paths.backups))
        assertFailsWith<SerializationException> { store.save(AppConfig()) }
        assertContentEquals(damaged, Files.readAllBytes(paths.config))

        assertEquals("D:/imported", store.importFrom(source).taskRoot)
        assertContentEquals(damaged, Files.readAllBytes(corruptCopies(paths).single()))
        assertTrue(store.backups().isEmpty())
    }

    @Test
    fun `preview does not create a missing configuration or default task directory`() {
        val source = validImport()
        val paths = ApplicationPaths(temporary.resolve("new-home"))
        ConfigStore(paths).previewImport(source)
        assertFalse(Files.exists(paths.home))
    }

    @Test
    fun `explicit recovery still refuses incompatible current versions`() {
        val source = validImport()
        for ((index, version) in listOf("2.0.0", "0.9.0").withIndex()) {
            val paths = ApplicationPaths(temporary.resolve("version-$index"))
            val store = ConfigStore(paths)
            store.save(AppConfig(taskRoot = "D:/first"))
            store.save(AppConfig(taskRoot = "D:/second"))
            val backup = store.backups().single().path
            val original = """{"schemaVersion":"$version","taskRoot":"D:/protected"}"""
            Files.writeString(paths.config, original)

            assertFailsWith<UnsupportedConfigVersionException> { store.previewImport(source) }
            assertFailsWith<UnsupportedConfigVersionException> { store.importFrom(source) }
            assertFailsWith<UnsupportedConfigVersionException> { store.restore(backup) }
            assertEquals(original, Files.readString(paths.config))
            assertTrue(corruptCopies(paths).isEmpty())
        }
    }

    @Test
    fun `invalid import leaves damaged current configuration and backups untouched`() {
        val paths = ApplicationPaths(temporary.resolve("home"))
        Files.createDirectories(paths.home)
        Files.writeString(paths.config, "{ damaged current")
        val source = temporary.resolve("invalid.json")
        Files.writeString(source, "{ invalid import")

        assertFailsWith<SerializationException> { ConfigStore(paths).importFrom(source) }
        assertEquals("{ damaged current", Files.readString(paths.config))
        assertFalse(Files.exists(paths.backups))
    }

    @Test
    fun `rapid saves preserve each backup instead of replacing a same millisecond file`() {
        val store = ConfigStore(ApplicationPaths(temporary.resolve("home")))
        repeat(10) { store.save(AppConfig(taskRoot = "D:/tasks-$it")) }
        assertEquals(9, store.backups().size)
        assertEquals(9, store.backups().map { Files.readString(it.path) }.distinct().size)
    }

    private fun validImport(): Path {
        val sourcePaths = ApplicationPaths(temporary.resolve("source"))
        ConfigStore(sourcePaths).save(AppConfig(taskRoot = "D:/imported"))
        return sourcePaths.config
    }

    private fun corruptCopies(paths: ApplicationPaths): List<Path> = Files.list(paths.backups).use { entries ->
        entries.filter { it.fileName.toString().startsWith("config-corrupt-") }.toList()
    }
}
