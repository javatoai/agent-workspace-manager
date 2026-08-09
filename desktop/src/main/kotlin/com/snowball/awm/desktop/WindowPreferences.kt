package com.snowball.awm.desktop

import java.util.prefs.Preferences

/**
 * Stores presentation-only desktop preferences outside config.json so changing
 * window geometry never changes the product configuration schema.
 */
internal object WindowPreferences {
    private val preferences = Preferences.userRoot().node("com/snowball/awm/window")

    data class Snapshot(
        val width: Int = 1600,
        val height: Int = 980,
        val maximized: Boolean = false,
        val settingsSection: String = "basic",
    )

    fun load(): Snapshot = Snapshot(
        width = preferences.getInt("width", 1600).coerceAtLeast(1200),
        height = preferences.getInt("height", 980).coerceAtLeast(720),
        maximized = preferences.getBoolean("maximized", false),
        settingsSection = preferences.get("settingsSection", "basic"),
    )

    fun saveWindow(width: Int, height: Int, maximized: Boolean) {
        if (!maximized) {
            preferences.putInt("width", width.coerceAtLeast(1200))
            preferences.putInt("height", height.coerceAtLeast(720))
        }
        preferences.putBoolean("maximized", maximized)
    }

    fun saveSettingsSection(section: String) {
        preferences.put("settingsSection", section)
    }
}
