package com.snowball.taskwt.core

import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ConfigStoreTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `missing config returns empty first launch state`() {
        val store = ConfigStore(ApplicationPaths(temporary.resolve("home")))
        assertFalse(store.exists())
        assertEquals(emptyList<String>(), store.load().scanRoots)
        assertEquals(null, store.load().taskRoot)
    }

    @Test
    fun `round trips user configuration`() {
        val store = ConfigStore(ApplicationPaths(temporary.resolve("home")))
        val expected = AppConfig(
            scanRoots = listOf("D:\\workspace_idea"),
            taskRoot = "D:\\task-worktrees",
            theme = ThemePreference.DARK,
        )
        store.save(expected)
        assertEquals(expected, store.load())
    }

    @Test
    fun `accepts legacy missing and future config schema versions`() {
        val paths = ApplicationPaths(temporary.resolve("home"))
        Files.createDirectories(paths.home)
        val store = ConfigStore(paths)

        Files.writeString(paths.config, """{"schemaVersion":1}""")
        assertEquals(1, store.load().schemaVersion)

        Files.writeString(paths.config, """{}""")
        assertEquals(CURRENT_APP_CONFIG_SCHEMA_VERSION, store.load().schemaVersion)

        Files.writeString(paths.config, """{"schemaVersion":99}""")
        assertEquals(99, store.load().schemaVersion)
    }

    @Test
    fun `saves configuration with a non current schema version`() {
        val store = ConfigStore(ApplicationPaths(temporary.resolve("home")))
        store.save(AppConfig(schemaVersion = 1))

        assertEquals(1, store.load().schemaVersion)
    }

    @Test
    fun `rejects unknown config fields`() {
        val paths = ApplicationPaths(temporary.resolve("home"))
        Files.createDirectories(paths.home)
        Files.writeString(
            paths.config,
            """{"schemaVersion":$CURRENT_APP_CONFIG_SCHEMA_VERSION,"legacyField":true}""",
        )

        assertThrows(SerializationException::class.java) { ConfigStore(paths).load() }
    }
}
