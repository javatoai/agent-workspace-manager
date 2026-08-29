package com.snowball.awm.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsExperienceTest {
    @Test
    fun `settings navigation is grouped and uses user facing names`() {
        val sections = settingsNavigationSections()

        assertEquals(
            listOf("环境概览", "外观", "目录", "服务与仓库", "开发工具", "协作说明"),
            sections.filter { it.category == SettingsSectionCategory.BASIC }.map { it.label },
        )
        assertEquals(
            listOf("Meegle", "Genbu", "AWM CLI"),
            sections.filter { it.category == SettingsSectionCategory.INTEGRATIONS }.map { it.label },
        )
        assertEquals(
            listOf("Git", "诊断与日志"),
            sections.filter { it.category == SettingsSectionCategory.ADVANCED }.map { it.label },
        )
        assertFalse(sections.any { it.key == "branches" })
        assertEquals("git", normalizeSettingsSection("branches", sections.map { it.key }.toSet()))
    }

    @Test
    fun `onboarding keeps only incomplete first task steps actionable`() {
        val steps = settingsOnboardingSteps(
            SettingsOnboardingProgress(
                taskRootReady = true,
                repositoryCount = 1,
                serviceCount = 0,
                taskCount = 0,
            ),
        )

        assertTrue(steps[0].completed)
        assertTrue(steps[1].completed)
        assertFalse(steps[2].completed)
        assertFalse(steps[3].completed)
        assertEquals("services", steps[2].targetSection)
        assertEquals("tasks", steps[3].targetSection)
    }
}
