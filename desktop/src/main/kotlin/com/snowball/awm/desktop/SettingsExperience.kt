package com.snowball.awm.desktop

internal data class SettingsNavigationSection(
    val key: String,
    val label: String,
)

internal fun settingsNavigationSections(): List<SettingsNavigationSection> = listOf(
    SettingsNavigationSection("basic", "外观"),
    SettingsNavigationSection("paths", "目录"),
    SettingsNavigationSection("groups", "服务与仓库"),
    SettingsNavigationSection("tools", "开发工具"),
    SettingsNavigationSection("agents", "协作说明"),
    SettingsNavigationSection("feishu", "Meegle"),
    SettingsNavigationSection("genbu", "Genbu"),
    SettingsNavigationSection("cli", "AWM CLI"),
    SettingsNavigationSection("git", "Git"),
    SettingsNavigationSection("logs", "诊断与日志"),
)
