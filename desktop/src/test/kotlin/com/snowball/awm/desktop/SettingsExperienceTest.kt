package com.snowball.awm.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SettingsExperienceTest {
    @Test
    fun `settings navigation is flat and omits the environment overview`() {
        val sections = settingsNavigationSections()

        assertEquals(
            listOf("外观", "目录", "服务与仓库", "开发工具", "协作说明", "Meegle", "Genbu", "AWM CLI", "Git", "诊断与日志"),
            sections.map { it.label },
        )
        assertFalse(sections.any { it.key == "overview" })
        assertFalse(sections.any { it.key == "branches" })
        assertEquals("git", normalizeSettingsSection("branches", sections.map { it.key }.toSet()))
    }
}
