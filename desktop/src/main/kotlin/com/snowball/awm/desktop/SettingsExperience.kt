package com.snowball.awm.desktop

internal enum class SettingsSectionCategory(val displayName: String) {
    BASIC("基础设置"),
    INTEGRATIONS("集成"),
    ADVANCED("高级设置"),
}

internal data class SettingsNavigationSection(
    val key: String,
    val label: String,
    val category: SettingsSectionCategory,
)

internal fun settingsNavigationSections(): List<SettingsNavigationSection> = listOf(
    SettingsNavigationSection("overview", "环境概览", SettingsSectionCategory.BASIC),
    SettingsNavigationSection("basic", "外观", SettingsSectionCategory.BASIC),
    SettingsNavigationSection("paths", "目录", SettingsSectionCategory.BASIC),
    SettingsNavigationSection("groups", "服务与仓库", SettingsSectionCategory.BASIC),
    SettingsNavigationSection("tools", "开发工具", SettingsSectionCategory.BASIC),
    SettingsNavigationSection("agents", "协作说明", SettingsSectionCategory.BASIC),
    SettingsNavigationSection("feishu", "Meegle", SettingsSectionCategory.INTEGRATIONS),
    SettingsNavigationSection("genbu", "Genbu", SettingsSectionCategory.INTEGRATIONS),
    SettingsNavigationSection("cli", "AWM CLI", SettingsSectionCategory.INTEGRATIONS),
    SettingsNavigationSection("git", "Git", SettingsSectionCategory.ADVANCED),
    SettingsNavigationSection("logs", "诊断与日志", SettingsSectionCategory.ADVANCED),
)

internal data class SettingsOnboardingProgress(
    val taskRootReady: Boolean,
    val repositoryCount: Int,
    val serviceCount: Int,
    val taskCount: Int,
)

internal data class SettingsOnboardingStep(
    val title: String,
    val detail: String,
    val completed: Boolean,
    val targetSection: String,
)

internal fun settingsOnboardingSteps(progress: SettingsOnboardingProgress): List<SettingsOnboardingStep> = listOf(
    SettingsOnboardingStep(
        title = "确认任务目录",
        detail = "AWM 会把任务工作区统一放在任务根目录中。",
        completed = progress.taskRootReady,
        targetSection = "paths",
    ),
    SettingsOnboardingStep(
        title = "添加 Git 仓库",
        detail = "选择至少一个本地主仓库，AWM 不会递归扫描磁盘。",
        completed = progress.repositoryCount > 0,
        targetSection = "services",
    ),
    SettingsOnboardingStep(
        title = "配置第一个服务",
        detail = "把仓库加入任务组，并选择 Worktree 或独立克隆策略。",
        completed = progress.serviceCount > 0,
        targetSection = "services",
    ),
    SettingsOnboardingStep(
        title = "创建第一个任务",
        detail = "只需填写任务名称并选择服务；其他集成都可以稍后配置。",
        completed = progress.taskCount > 0,
        targetSection = "tasks",
    ),
)
