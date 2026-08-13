package com.snowball.awm.desktop

import java.util.prefs.Preferences
import kotlin.math.roundToInt

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
        width = preferences.getInt("width", 1600),
        height = preferences.getInt("height", 980),
        maximized = preferences.getBoolean("maximized", false),
        settingsSection = preferences.get("settingsSection", "basic"),
    )

    fun saveWindow(width: Int, height: Int, maximized: Boolean) {
        if (!maximized) {
            preferences.putInt("width", width)
            preferences.putInt("height", height)
        }
        preferences.putBoolean("maximized", maximized)
    }

    fun savePhysicalWindow(
        width: Int,
        height: Int,
        maximized: Boolean,
        scaleX: Double,
        scaleY: Double,
    ) {
        val logical = physicalToLogical(width, height, scaleX, scaleY)
        saveWindow(logical.widthDp.roundToInt(), logical.heightDp.roundToInt(), maximized)
    }

    fun saveSettingsSection(section: String) {
        preferences.put("settingsSection", section)
    }

    internal fun physicalToLogical(
        width: Int,
        height: Int,
        scaleX: Double,
        scaleY: Double,
    ): LogicalWindowSize = LogicalWindowSize(
        (width / scaleX.coerceAtLeast(1.0)).roundToInt().toFloat(),
        (height / scaleY.coerceAtLeast(1.0)).roundToInt().toFloat(),
    )
}

internal data class LogicalWindowSize(val widthDp: Float, val heightDp: Float)
