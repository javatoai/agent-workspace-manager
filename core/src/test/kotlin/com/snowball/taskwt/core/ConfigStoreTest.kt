package com.snowball.taskwt.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
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
}
