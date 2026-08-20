package com.snowball.awm.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class WindowPreferencesTest {
    @Test
    fun `small floating window sizes are preserved`() {
        assertEquals(
            LogicalWindowSize(1200f, 720f),
            WindowPreferences.physicalToLogical(1200, 720, 1.0, 1.0),
        )
    }

    @Test
    fun `new window starts with a large desktop size`() {
        assertEquals(1600, WindowPreferences.Snapshot().width)
        assertEquals(980, WindowPreferences.Snapshot().height)
    }

    @Test
    fun `physical window size is persisted as logical size without enforcing a minimum`() {
        assertEquals(
            LogicalWindowSize(1280f, 720f),
            WindowPreferences.physicalToLogical(1920, 1080, 1.5, 1.5),
        )
    }

    @Test
    fun `settings selection restores supported keys and maps legacy advanced to feishu`() {
        val supported = setOf("basic", "paths", "groups", "agents", "tools", "branches", "git", "feishu", "logs")

        assertEquals("logs", normalizeSettingsSection("logs", supported))
        assertEquals("paths", normalizeSettingsSection("paths", supported))
        assertEquals("feishu", normalizeSettingsSection("advanced", supported))
        assertEquals("basic", normalizeSettingsSection("unknown", supported))
    }
}
