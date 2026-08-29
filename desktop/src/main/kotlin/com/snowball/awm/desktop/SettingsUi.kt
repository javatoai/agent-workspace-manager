package com.snowball.awm.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.Subject
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.snowball.awm.core.AgentTaskTemplate
import com.snowball.awm.core.ApplicationEventClipboard
import com.snowball.awm.core.MeegleCommandSource
import com.snowball.awm.core.GitCommandSource
import com.snowball.awm.core.GenbuCommandSource
import com.snowball.awm.core.ConfigStore
import com.snowball.awm.core.DevelopmentToolConfig
import com.snowball.awm.core.DevelopmentToolType
import com.snowball.awm.core.GroupConfig
import com.snowball.awm.core.GitConfigValue
import com.snowball.awm.core.LocalGitEnvironmentSnapshot
import com.snowball.awm.core.CommandVersionStatus
import com.snowball.awm.core.MeegleProjectConfig
import com.snowball.awm.core.ThemePreference
import com.snowball.awm.core.TaskRootMigrationMode
import com.snowball.awm.core.TaskRootMigrationPhase
import com.snowball.awm.core.TaskRootMigrationProgress
import java.nio.file.Files
import java.nio.file.Path

@Composable
internal fun SettingsScreen(controller: DesktopApplication) {
    var taskRoot by remember(controller.config.taskRoot) { mutableStateOf(controller.config.taskRoot.orEmpty()) }
    var requirementMaterialsRoot by remember(controller.config.requirementMaterialsRoot) {
        mutableStateOf(controller.config.requirementMaterialsRoot.orEmpty())
    }
    var requirementMaterialsSubdirectory by remember(controller.config.requirementMaterialsSubdirectory) {
        mutableStateOf(controller.config.requirementMaterialsSubdirectory.orEmpty())
    }
    val developmentToolPaths = remember(controller.config.developmentTools) {
        mutableStateMapOf<DevelopmentToolType, String>().apply {
            DevelopmentToolType.entries.forEach { type ->
                put(type, controller.config.developmentTools.firstOrNull { it.type == type }?.path.orEmpty())
            }
        }
    }
    var defaultDevelopmentTool by remember(controller.config.defaultDevelopmentTool) {
        mutableStateOf(controller.config.defaultDevelopmentTool)
    }
    var allowTemporaryDevelopmentToolSelection by remember(controller.config.allowTemporaryDevelopmentToolSelection) {
        mutableStateOf(controller.config.allowTemporaryDevelopmentToolSelection)
    }
    var terminal by remember(controller.config.terminalExecutable) { mutableStateOf(controller.config.terminalExecutable.orEmpty()) }
    var hiddenBranchInput by remember { mutableStateOf("") }
    var hiddenTaskDetailBranches by remember(controller.config.hiddenTaskDetailBranches) {
        mutableStateOf(controller.config.hiddenTaskDetailBranches)
    }
    var blockedGitBranchInput by remember { mutableStateOf("") }
    var blockedGitWriteBranches by remember(controller.config.blockedGitWriteBranches) {
        mutableStateOf(controller.config.blockedGitWriteBranches)
    }
    var genbuPath by remember(controller.config.genbuExecutablePath) {
        mutableStateOf(controller.config.genbuExecutablePath.orEmpty())
    }
    val meegleProjects = remember(controller.config.meegleProjects) {
        mutableStateMapOf<Int, MeegleProjectConfig>().apply {
            controller.config.meegleProjects.forEachIndexed { index, project -> put(index, project) }
        }
    }
    var meegleMenuExpanded by remember { mutableStateOf(false) }
    var backupMenuExpanded by remember { mutableStateOf(false) }
    var restoreBackup by remember { mutableStateOf<ConfigStore.Backup?>(null) }
    var importPreview by remember { mutableStateOf<ConfigStore.ImportPreview?>(null) }
    var newGroup by remember { mutableStateOf(false) }
    var renameGroup by remember { mutableStateOf<GroupConfig?>(null) }
    var deleteGroupTarget by remember { mutableStateOf<GroupConfig?>(null) }
    var newTemplate by remember { mutableStateOf(false) }
    var editTemplate by remember { mutableStateOf<AgentTaskTemplate?>(null) }
    var deleteTemplateTarget by remember { mutableStateOf<AgentTaskTemplate?>(null) }
    var agentGroupId by remember(controller.config.groups) { mutableStateOf(controller.config.groups.first().id) }
    var agentScope by remember { mutableStateOf("global") }
    var globalAgents by remember(controller.agentRevision) { mutableStateOf(controller.readGlobalAgents()) }
    val groupAgentDrafts = remember(controller.config.groups, controller.agentRevision) {
        mutableStateMapOf<String, String>().apply {
            controller.config.groups.forEach { group -> put(group.id, controller.readGroupAgents(group.id)) }
        }
    }
    val sections = remember { settingsNavigationSections() }
    val initialSection = remember { WindowPreferences.load().settingsSection }
    var selectedSection by remember { mutableStateOf(normalizeSettingsSection(initialSection, sections.map { it.key }.toSet())) }
    var advancedExpanded by remember {
        mutableStateOf(sections.any { it.key == selectedSection && it.category == SettingsSectionCategory.ADVANCED })
    }
    fun navigateToSection(key: String) {
        when (key) {
            "tasks" -> controller.navigation = NavigationItem.TASKS
            "services" -> controller.navigation = NavigationItem.SERVICES
            else -> {
                selectedSection = key
                WindowPreferences.saveSettingsSection(key)
            }
        }
    }
    fun saving(key: String) = controller.settingsSaveState(key) == SettingsSaveState.SAVING
    fun currentToolConfigs(): List<DevelopmentToolConfig> = DevelopmentToolType.entries.mapNotNull { type ->
        developmentToolPaths[type]?.trim()?.takeIf(String::isNotBlank)?.let { DevelopmentToolConfig(type, it) }
    }
    fun saveDevelopmentTools() {
        controller.updateDevelopmentTools(
            currentToolConfigs(),
            defaultDevelopmentTool,
            terminal,
            allowTemporaryDevelopmentToolSelection,
        ) {
            DevelopmentToolType.entries.forEach { type ->
                developmentToolPaths[type] = controller.config.developmentTools.firstOrNull { it.type == type }?.path.orEmpty()
            }
            defaultDevelopmentTool = controller.config.defaultDevelopmentTool
            allowTemporaryDevelopmentToolSelection = controller.config.allowTemporaryDevelopmentToolSelection
            terminal = controller.config.terminalExecutable.orEmpty()
        }
    }
    fun saveMeegleProjects() {
        controller.updateMeegleProjects(meegleProjects.toSortedMap().values.toList()) {
            meegleProjects.clear()
            controller.config.meegleProjects.forEachIndexed { index, project -> meegleProjects[index] = project }
        }
    }
    fun saveHiddenBranches(updated: List<String>) {
        val previous = hiddenTaskDetailBranches
        hiddenTaskDetailBranches = updated
        controller.updateHiddenTaskDetailBranches(updated) {
            hiddenTaskDetailBranches = previous
        }
    }
    fun saveBlockedGitBranches(updated: List<String>) {
        val previous = blockedGitWriteBranches
        blockedGitWriteBranches = updated
        controller.settingsController.updateBlockedGitWriteBranches(updated) {
            blockedGitWriteBranches = previous
        }
    }

    LaunchedEffect(selectedSection) {
        if (selectedSection == "overview") {
            controller.refreshCliInstallationStatus()
            controller.refreshMeegleStatus()
            controller.refreshLocalGit()
            controller.refreshGenbu()
        }
        if (selectedSection == "paths") controller.refreshConfigFileSnapshot()
        if (selectedSection == "cli") controller.refreshCliInstallationStatus()
        if (selectedSection == "feishu") controller.refreshMeegleStatus()
        if (selectedSection == "git") controller.refreshLocalGit()
        if (selectedSection == "genbu") controller.refreshGenbu()
    }
    DisposableEffect(selectedSection) {
        onDispose { if (selectedSection == "feishu") controller.cancelMeegleProjectLoad() }
    }

    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().fillMaxHeight().align(Alignment.TopStart)
                .padding(start = 28.dp, end = 28.dp, bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                Modifier.width(250.dp).fillMaxHeight(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                LazyColumn(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SettingsSectionCategory.entries.forEach { category ->
                        item(key = "category-${category.name}") {
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable(enabled = category == SettingsSectionCategory.ADVANCED) {
                                        advancedExpanded = !advancedExpanded
                                    }
                                    .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    category.displayName,
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (category == SettingsSectionCategory.ADVANCED) {
                                    Icon(
                                        if (advancedExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                                        if (advancedExpanded) "收起高级设置" else "展开高级设置",
                                        Modifier.size(17.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        val visibleSections = sections.filter {
                            it.category == category && (category != SettingsSectionCategory.ADVANCED || advancedExpanded)
                        }
                        items(visibleSections, key = { it.key }) { section ->
                            Surface(
                                Modifier.fillMaxWidth().clickable { navigateToSection(section.key) },
                                color = if (selectedSection == section.key) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Text(
                                    section.label,
                                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    fontWeight = if (selectedSection == section.key) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selectedSection == section.key) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
            controller.configurationLoadError?.let { error ->
                item {
                    val snapshot = controller.configFileSnapshot
                    OutlinedCard(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("配置加载失败", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold)
                            SelectionContainer {
                                Text(
                                    "未使用默认配置覆盖磁盘文件。\n文件：${snapshot.path}\n原因：$error\n请先备份该文件；确认无需保留时，可在文件管理器中手动删除它，然后重新打开 AWM。",
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { controller.copyText(snapshot.path.toString(), "主配置路径已复制") }) { Text("复制路径") }
                                OutlinedButton(onClick = controller::revealConfigFile, enabled = snapshot.exists) { Text("定位文件") }
                                OutlinedButton(onClick = { controller.copyText(controller.configurationRecoveryGuidance(), "恢复指引已复制") }) { Text("复制恢复指引") }
                            }
                        }
                    }
                }
            }
            if (selectedSection == "overview") item {
                SettingsOverviewSection(controller, ::navigateToSection)
            }
            if (selectedSection == "basic") item {
                SettingsBasicSection(
                    controller = controller,
                    saving = saving("basic"),
                )
            }
            if (selectedSection == "paths") item {
                SettingsPathsSection(
                    controller = controller,
                    taskRoot = taskRoot,
                    onTaskRootChange = { taskRoot = it },
                    requirementMaterialsRoot = requirementMaterialsRoot,
                    onRequirementMaterialsRootChange = { requirementMaterialsRoot = it },
                    requirementMaterialsSubdirectory = requirementMaterialsSubdirectory,
                    onRequirementMaterialsSubdirectoryChange = { requirementMaterialsSubdirectory = it },
                    saving = saving("paths"),
                    backupMenuExpanded = backupMenuExpanded,
                    onBackupMenuExpandedChange = { backupMenuExpanded = it },
                    onRestoreBackup = { restoreBackup = it },
                    onImportPreview = { importPreview = it },
                )
            }
            if (selectedSection == "groups") item {
                SettingsGroupsSection(
                    controller = controller,
                    onNewGroup = { newGroup = true },
                    onRenameGroup = { renameGroup = it },
                    onDeleteGroup = { deleteGroupTarget = it },
                )
            }
            if (selectedSection == "agents") item {
                SettingsAgentsSection(
                    controller = controller,
                    agentScope = agentScope,
                    onAgentScopeChange = { agentScope = it },
                    agentGroupId = agentGroupId,
                    onAgentGroupIdChange = { agentGroupId = it },
                    globalAgents = globalAgents,
                    onGlobalAgentsChange = { globalAgents = it },
                    groupAgentDrafts = groupAgentDrafts,
                )
            }
            if (selectedSection == "agents") item {
                SettingsTaskTemplatesSection(
                    controller = controller,
                    onNewTemplate = { newTemplate = true },
                    onEditTemplate = { editTemplate = it },
                    onDeleteTemplate = { deleteTemplateTarget = it },
                )
            }
            if (selectedSection == "tools") item {
                SettingsToolsSection(
                    controller = controller,
                    developmentToolPaths = developmentToolPaths,
                    defaultDevelopmentTool = defaultDevelopmentTool,
                    onDefaultDevelopmentToolChange = { defaultDevelopmentTool = it },
                    allowTemporaryDevelopmentToolSelection = allowTemporaryDevelopmentToolSelection,
                    onAllowTemporaryDevelopmentToolSelectionChange = { allowTemporaryDevelopmentToolSelection = it },
                    terminal = terminal,
                    onTerminalChange = { terminal = it },
                    saving = saving("tools"),
                    onSaveDevelopmentTools = ::saveDevelopmentTools,
                )
            }
            if (selectedSection == "cli") item {
                SettingsCliSection(controller)
            }
            if (selectedSection == "git") item {
                SettingsGitSection(
                    controller = controller,
                    blockedGitBranchInput = blockedGitBranchInput,
                    onBlockedGitBranchInputChange = { blockedGitBranchInput = it },
                    blockedGitWriteBranches = blockedGitWriteBranches,
                    saving = saving("git-write-policy"),
                    onSaveBlockedGitBranches = ::saveBlockedGitBranches,
                )
            }
            if (selectedSection == "git") item {
                SettingsBranchesSection(
                    controller = controller,
                    hiddenBranchInput = hiddenBranchInput,
                    onHiddenBranchInputChange = { hiddenBranchInput = it },
                    hiddenTaskDetailBranches = hiddenTaskDetailBranches,
                    saving = saving("branches"),
                    onSaveHiddenBranches = ::saveHiddenBranches,
                )
            }
            if (selectedSection == "genbu") item {
                SettingsGenbuSection(
                    controller = controller,
                    genbuPath = genbuPath,
                    onGenbuPathChange = { genbuPath = it },
                    saving = saving("genbu"),
                )
            }
            if (selectedSection == "feishu") item {
                SettingsFeishuSection(
                    controller = controller,
                    meegleProjects = meegleProjects,
                    meegleMenuExpanded = meegleMenuExpanded,
                    onMeegleMenuExpandedChange = { meegleMenuExpanded = it },
                    saving = saving("feishu"),
                    onSaveMeegleProjects = ::saveMeegleProjects,
                )
            }
            if (selectedSection == "logs") item {
                SettingsLogsSection(controller)
            }
            }
        }
    }
    when (val migration = controller.taskRootMigrationState) {
        TaskRootMigrationUiState.Idle -> Unit
        is TaskRootMigrationUiState.Preview -> TaskRootMigrationDialog(
            preview = migration.preview,
            migrating = false,
            onDismiss = {
                controller.cancelTaskRootMigration()
                taskRoot = controller.config.taskRoot.orEmpty()
            },
            onConfirm = { controller.confirmTaskRootMigration() },
        )
        is TaskRootMigrationUiState.Migrating -> TaskRootMigrationDialog(
            preview = migration.preview,
            migrating = true,
            progress = migration.progress,
            onDismiss = {},
            onConfirm = {},
        )
    }
    if (newGroup) NameDialog("创建组", "", onDismiss = { newGroup = false }) {
        controller.addGroup(it) { newGroup = false }
    }
    renameGroup?.let { group -> NameDialog("重命名组", group.name, onDismiss = { renameGroup = null }) {
        controller.renameGroup(group.id, it) { renameGroup = null }
    } }
    deleteGroupTarget?.let { group ->
        ConfirmDialog(
            title = "删除空组？",
            message = "将删除组“${group.name}”。仅当组内没有服务且没有任务引用时才会执行，此操作不会删除任何仓库目录。",
            confirmLabel = "删除组",
            destructive = true,
            enabled = !controller.settingsBusy,
            onDismiss = { deleteGroupTarget = null },
            onConfirm = { controller.deleteGroup(group.id) { deleteGroupTarget = null } },
        )
    }
    if (newTemplate) {
        TaskTemplateDialog("新建模板", "", "", onDismiss = { newTemplate = false }) { name, content ->
            if (controller.saveAgentTaskTemplate(null, name, content)) newTemplate = false
        }
    }
    editTemplate?.let { template ->
        TaskTemplateDialog("编辑模板", template.name, template.content, onDismiss = { editTemplate = null }) { name, content ->
            if (controller.saveAgentTaskTemplate(template.id, name, content)) editTemplate = null
        }
    }
    deleteTemplateTarget?.let { template ->
        ConfirmDialog(
            title = "删除模板？",
            message = "将删除模板“${template.name}”。已创建任务中的人工说明不受影响。",
            confirmLabel = "删除模板",
            destructive = true,
            enabled = !controller.settingsBusy,
            onDismiss = { deleteTemplateTarget = null },
            onConfirm = { if (controller.deleteAgentTaskTemplate(template.id)) deleteTemplateTarget = null },
        )
    }
    restoreBackup?.let { backup ->
        ConfirmDialog(
            title = "恢复配置备份？",
            message = "将先备份当前配置，再恢复 ${backup.path.fileName}。任务目录不会被删除。",
            confirmLabel = "恢复备份",
            enabled = !controller.busy,
            onDismiss = { restoreBackup = null },
            onConfirm = { if (controller.restoreConfigBackup(backup.path.toString())) restoreBackup = null },
        )
    }
    importPreview?.let { preview ->
        ConfirmDialog(
            title = "导入此配置？",
            message = buildString {
                appendLine("来源：${preview.source}")
                if (preview.changes.isEmpty()) appendLine("配置内容没有变化") else preview.changes.forEach { appendLine("• $it") }
                if (preview.invalidDevelopmentTools.isNotEmpty()) {
                    append("当前电脑路径无效，导入后需重新选择：${preview.invalidDevelopmentTools.joinToString { it.displayName }}")
                }
            }.trim(),
            confirmLabel = "导入配置",
            enabled = !controller.busy,
            onDismiss = { importPreview = null },
            onConfirm = { if (controller.importConfig(preview.source.toString())) importPreview = null },
        )
    }
}

@Composable
private fun TaskRootMigrationDialog(
    preview: com.snowball.awm.core.TaskRootMigrationPreview,
    migrating: Boolean,
    progress: TaskRootMigrationProgress? = null,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val mode = when (preview.mode) {
        TaskRootMigrationMode.DIRECT_SWITCH -> "直接切换"
        TaskRootMigrationMode.SAME_FILE_STORE -> "同磁盘移动"
        TaskRootMigrationMode.CROSS_FILE_STORE -> "跨磁盘复制并校验"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (migrating) "正在迁移任务目录" else "迁移任务并切换目录？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("AWM 将迁移全部任务，校验 Git 状态和任务清单后才更新配置。迁移期间请关闭这些工作区的 IDE 和终端。")
                Text("原目录：${preview.sourceRoot}", style = MaterialTheme.typography.bodySmall)
                Text("新目录：${preview.targetRoot}", style = MaterialTheme.typography.bodySmall)
                Text("方式：$mode · ${preview.taskCount} 个任务 · ${preview.workspaceCount} 个工作区 · ${formatByteSize(preview.totalBytes)}")
                if (migrating) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(taskRootMigrationProgressLabel(progress), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !migrating) {
                Text(if (migrating) "迁移中…" else "迁移并切换")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !migrating) { Text("取消") }
        },
    )
}

private fun taskRootMigrationProgressLabel(progress: TaskRootMigrationProgress?): String = when (progress?.phase) {
    null, TaskRootMigrationPhase.PREPARING -> "正在准备迁移…"
    TaskRootMigrationPhase.TRANSFERRING_AND_VERIFYING -> progress.let {
        "正在迁移并校验 ${it.currentTask.orEmpty()}（${it.completedTasks + 1}/${it.totalTasks}）"
    }
    TaskRootMigrationPhase.UPDATING_CONFIG -> "任务已校验，正在更新配置…"
    TaskRootMigrationPhase.CLEANING_SOURCE -> "配置已生效，正在清理旧目录…"
    TaskRootMigrationPhase.COMPLETED -> "迁移完成"
}

private fun formatByteSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes.toDouble() / (1024L * 1024L * 1024L))
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes.toDouble() / (1024L * 1024L))
    bytes >= 1024L -> "%.1f KB".format(bytes.toDouble() / 1024L)
    else -> "$bytes B"
}

internal fun normalizeSettingsSection(stored: String, supported: Set<String>): String = when (stored) {
    "advanced" -> "feishu"
    "branches" -> if ("git" in supported) "git" else "overview"
    "genbu" -> if ("genbu" in supported) "genbu" else "overview"
    in supported -> stored
    else -> "overview"
}

private enum class CliDetectionPhase { IDLE, LOADING, READY, FAILED }

private fun genbuSourceLabel(source: GenbuCommandSource): String = when (source) {
    GenbuCommandSource.CONFIGURED -> "手动配置"
    GenbuCommandSource.PROBED -> "自动探测"
    GenbuCommandSource.PATH_FALLBACK -> "PATH 回退"
}

private fun gitSourceLabel(source: GitCommandSource): String = when (source) {
    GitCommandSource.CONFIGURED -> "手动配置"
    GitCommandSource.PROBED -> "自动探测"
    GitCommandSource.PATH_FALLBACK -> "PATH 回退"
}

private fun meegleSourceLabel(source: MeegleCommandSource): String = when (source) {
    MeegleCommandSource.CONFIGURED -> "手动配置"
    MeegleCommandSource.PROBED -> "自动探测"
    MeegleCommandSource.PATH_FALLBACK -> "PATH 回退"
}

@Composable
private fun CliCommandPanel(
    controller: DesktopApplication,
    title: String,
    command: String,
    source: String,
    version: CommandVersionStatus?,
    phase: CliDetectionPhase,
    failure: String? = null,
    configuredPath: String,
    pathLabel: String,
    pathPlaceholder: String,
    saving: Boolean,
    onPathChange: (String) -> Unit,
    onSavePath: (String) -> Unit,
    onChoosePath: (String) -> Unit,
    onRefresh: () -> Unit,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    var pathInput by remember(title, configuredPath) { mutableStateOf(configuredPath) }
    val pathChanged = pathInput.trim() != configuredPath.trim()
    val phaseLabel = when (phase) {
        CliDetectionPhase.IDLE -> "尚未检测"
        CliDetectionPhase.LOADING -> "检测中"
        CliDetectionPhase.READY -> "检测成功"
        CliDetectionPhase.FAILED -> "检测失败"
    }
    val phaseColor = when (phase) {
        CliDetectionPhase.READY -> SuccessGreen
        CliDetectionPhase.FAILED -> MaterialTheme.colorScheme.error
        CliDetectionPhase.IDLE, CliDetectionPhase.LOADING -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(phaseLabel, color = phaseColor, style = MaterialTheme.typography.labelSmall)
            }
            GitEnvironmentGrid(
                listOf(
                    GitEnvironmentField("版本号", version?.version ?: "不可用", monospace = true),
                    GitEnvironmentField("当前命令", command.ifBlank { "未解析" }, monospace = true),
                    GitEnvironmentField("命令来源", source),
                    GitEnvironmentField("检测状态", failure ?: phaseLabel),
                ),
            )
            version?.error?.let { error ->
                Text("版本检测失败：$error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRefresh, enabled = phase != CliDetectionPhase.LOADING && !saving) {
                    Icon(Icons.Outlined.Refresh, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("重新检测")
                }
                OutlinedButton(
                    onClick = { controller.copyCliCommandPath(command) },
                    enabled = command.isNotBlank() && !saving,
                ) {
                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("复制命令路径")
                }
                OutlinedButton(
                    onClick = { controller.runCliInTerminal(command) },
                    enabled = command.isNotBlank() && !saving,
                ) {
                    Icon(Icons.Outlined.Terminal, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("在终端中运行")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("手动配置", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = pathInput,
                    onValueChange = {
                        pathInput = it
                        onPathChange(it)
                    },
                    modifier = Modifier.weight(1f).onFocusChanged { focus ->
                        if (!focus.isFocused && pathInput.trim() != configuredPath.trim()) onSavePath(pathInput)
                    },
                    label = { Text(pathLabel) },
                    placeholder = { Text(pathPlaceholder) },
                    supportingText = { Text("留空时使用自动探测或 PATH 回退。") },
                    singleLine = true,
                    readOnly = controller.busy || saving,
                )
                OutlinedButton(onClick = { onChoosePath(pathInput) }, enabled = !controller.pathPickerBusy && !controller.busy && !saving) {
                    Icon(Icons.Outlined.Folder, null)
                    Text("选择")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (configuredPath.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            pathInput = ""
                            onPathChange("")
                            onSavePath("")
                        },
                        enabled = !controller.busy && !saving,
                    ) {
                        Text("恢复自动")
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Button(onClick = { onSavePath(pathInput) }, enabled = pathChanged && !controller.busy && !saving) {
                    Text("保存手动配置")
                }
            }
            extra()
        }
    }
}

@Composable
private fun SettingsBasicSection(
    controller: DesktopApplication,
    saving: Boolean,
) {
    SettingsCard("外观", "调整本机界面的显示方式。") {
        AutoSaveStatus(controller, "basic")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("界面主题", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(14.dp))
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemePreference.entries.forEach { theme ->
                    FilterChip(
                        controller.config.theme == theme,
                        { controller.setTheme(theme) },
                        label = { Text(theme.displayName) },
                        enabled = !controller.busy && !saving,
                    )
                }
            }
        }
    }
}

private enum class OverviewTone { READY, ATTENTION, NEUTRAL }

private data class EnvironmentOverviewItem(
    val label: String,
    val status: String,
    val detail: String,
    val actionLabel: String,
    val tone: OverviewTone,
    val action: () -> Unit,
)

@Composable
private fun SettingsOverviewSection(
    controller: DesktopApplication,
    navigateToSection: (String) -> Unit,
) {
    val taskRoot = controller.config.taskRoot?.let { runCatching { Path.of(it) }.getOrNull() }
    val taskRootReady = taskRoot != null && Files.isDirectory(taskRoot) && Files.isWritable(taskRoot)
    val serviceCount = controller.config.groups.sumOf { it.services.size }
    val onboarding = settingsOnboardingSteps(
        SettingsOnboardingProgress(
            taskRootReady = taskRootReady,
            repositoryCount = controller.config.repositories.size,
            serviceCount = serviceCount,
            taskCount = controller.tasks.size,
        ),
    )
    if (onboarding.any { !it.completed }) {
        SettingsCard("首次使用", "完成最短路径即可创建任务；Meegle、Genbu、资料目录和高级 Git 规则都可以稍后配置。") {
            onboarding.forEachIndexed { index, step ->
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (step.completed) SuccessGreen.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            if (step.completed) "✓" else "${index + 1}",
                            Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            color = if (step.completed) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(step.title, fontWeight = FontWeight.SemiBold)
                        Text(step.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!step.completed) {
                        OutlinedButton(onClick = { navigateToSection(step.targetSection) }) {
                            Text(if (step.targetSection == "tasks") "去创建" else "去配置")
                        }
                    }
                }
            }
        }
    }

    val configuredTools = controller.config.developmentTools
    val validToolCount = configuredTools.count { runCatching { Files.exists(Path.of(it.path)) }.getOrDefault(false) }
    val invalidToolCount = configuredTools.size - validToolCount
    val terminal = controller.terminalCommandResolution()
    val gitState = controller.localGitSettingsState
    val gitResolution = controller.gitCommandResolution()
    val meegleState = controller.meegleCliState
    val genbuState = controller.genbuSettingsState
    val cli = controller.cliInstallationStatus
    val items = buildList {
        add(
            EnvironmentOverviewItem(
                label = "任务目录",
                status = if (taskRootReady) "正常" else "需要处理",
                detail = taskRoot?.toString() ?: "尚未配置任务根目录",
                actionLabel = if (taskRootReady) "查看" else "更换目录",
                tone = if (taskRootReady) OverviewTone.READY else OverviewTone.ATTENTION,
                action = { navigateToSection("paths") },
            ),
        )
        add(
            EnvironmentOverviewItem(
                label = "Git",
                status = when (gitState) {
                    is LocalGitSettingsState.Loaded -> "可用"
                    is LocalGitSettingsState.Loading -> "检测中"
                    is LocalGitSettingsState.Failed -> "检测失败"
                    LocalGitSettingsState.Idle -> "尚未检测"
                },
                detail = "${gitResolution.first} · ${gitSourceLabel(gitResolution.second)}",
                actionLabel = if (gitState is LocalGitSettingsState.Failed) "修复" else "查看",
                tone = if (gitState is LocalGitSettingsState.Loaded) OverviewTone.READY else OverviewTone.ATTENTION,
                action = { navigateToSection("git") },
            ),
        )
        add(
            EnvironmentOverviewItem(
                label = "开发工具",
                status = "$validToolCount/${DevelopmentToolType.entries.size} 可用",
                detail = when {
                    invalidToolCount > 0 -> "$invalidToolCount 个已配置路径失效"
                    configuredTools.isEmpty() -> "尚未检测到开发工具"
                    else -> "未配置的类型会在启动后静默探测"
                },
                actionLabel = if (invalidToolCount > 0) "修复" else "管理",
                tone = when {
                    invalidToolCount > 0 || configuredTools.isEmpty() -> OverviewTone.ATTENTION
                    else -> OverviewTone.READY
                },
                action = { navigateToSection("tools") },
            ),
        )
        add(
            EnvironmentOverviewItem(
                label = "终端",
                status = when {
                    controller.config.terminalExecutable.isNullOrBlank() -> "自动"
                    terminal.available -> "手动配置 · 可用"
                    else -> "路径无效"
                },
                detail = "${terminal.displayName} · ${terminal.command}",
                actionLabel = if (terminal.available) "管理" else "修复",
                tone = if (terminal.available) OverviewTone.READY else OverviewTone.ATTENTION,
                action = { navigateToSection("tools") },
            ),
        )
        val meegleStatus = when (meegleState) {
            is MeegleCliState.Ready -> meegleState.status
            is MeegleCliState.Loading -> meegleState.previous
            MeegleCliState.Idle, is MeegleCliState.Failed -> null
        }
        add(
            EnvironmentOverviewItem(
                label = "Meegle",
                status = when {
                    meegleStatus?.authenticated == true -> "已登录"
                    meegleStatus?.installed == true -> "需要登录"
                    meegleState is MeegleCliState.Loading -> "检测中"
                    else -> "未就绪"
                },
                detail = if (controller.config.meegleProjects.isEmpty()) "尚未选择项目" else "已配置 ${controller.config.meegleProjects.size} 个项目",
                actionLabel = if (meegleStatus?.installed == true && !meegleStatus.authenticated) "立即登录" else "配置",
                tone = if (meegleStatus?.authenticated == true) OverviewTone.READY else OverviewTone.NEUTRAL,
                action = {
                    if (meegleStatus?.installed == true && !meegleStatus.authenticated) controller.loginMeegle()
                    else navigateToSection("feishu")
                },
            ),
        )
        add(
            EnvironmentOverviewItem(
                label = "AWM CLI",
                status = if (cli.installed) "已安装 ${cli.version.orEmpty()}".trim() else "未安装",
                detail = cli.message,
                actionLabel = if (!cli.installed && cli.supported && cli.bundledPayloadAvailable) "立即安装" else "查看",
                tone = if (cli.installed) OverviewTone.READY else OverviewTone.NEUTRAL,
                action = {
                    if (!cli.installed && cli.supported && cli.bundledPayloadAvailable) controller.installCli()
                    else navigateToSection("cli")
                },
            ),
        )
        add(
            EnvironmentOverviewItem(
                label = "需求资料目录",
                status = if (controller.config.requirementMaterialsConfigured) "已启用" else "未启用",
                detail = if (controller.config.requirementMaterialsConfigured) "创建任务时会预览并创建或复用资料目录" else "可选功能，不影响任务创建",
                actionLabel = "配置",
                tone = if (controller.config.requirementMaterialsConfigured) OverviewTone.READY else OverviewTone.NEUTRAL,
                action = { navigateToSection("paths") },
            ),
        )
        add(
            EnvironmentOverviewItem(
                label = "Genbu",
                status = when (genbuState) {
                    is GenbuSettingsState.Loaded -> "可用"
                    GenbuSettingsState.Loading -> "检测中"
                    is GenbuSettingsState.Failed -> "未就绪"
                    GenbuSettingsState.Idle -> "未启用"
                },
                detail = if (controller.enabledGenbuProbeServiceCount > 0) "${controller.enabledGenbuProbeServiceCount} 个服务已启用状态探测" else "可选集成",
                actionLabel = "配置",
                tone = if (genbuState is GenbuSettingsState.Loaded) OverviewTone.READY else OverviewTone.NEUTRAL,
                action = { navigateToSection("genbu") },
            ),
        )
    }
    SettingsCard("环境状态", "显示当前实际使用的本机能力；可选集成未配置不会阻止创建任务。") {
        items.chunked(2).forEach { rowItems ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                rowItems.forEach { item -> EnvironmentOverviewCard(item, Modifier.weight(1f)) }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun EnvironmentOverviewCard(item: EnvironmentOverviewItem, modifier: Modifier = Modifier) {
    val statusColor = when (item.tone) {
        OverviewTone.READY -> SuccessGreen
        OverviewTone.ATTENTION -> MaterialTheme.colorScheme.error
        OverviewTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    OutlinedCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(item.label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text(item.status, style = MaterialTheme.typography.labelSmall, color = statusColor)
            }
            Text(
                item.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = item.action, modifier = Modifier.align(Alignment.End)) { Text(item.actionLabel) }
        }
    }
}

@Composable
private fun SettingsGenbuSection(
    controller: DesktopApplication,
    genbuPath: String,
    onGenbuPathChange: (String) -> Unit,
    saving: Boolean,
) {
    val state = controller.genbuSettingsState
    val (command, source) = controller.genbuCommandResolution()
    val loaded = state as? GenbuSettingsState.Loaded
    val phase = when (state) {
        GenbuSettingsState.Idle -> CliDetectionPhase.IDLE
        GenbuSettingsState.Loading -> CliDetectionPhase.LOADING
        is GenbuSettingsState.Loaded -> CliDetectionPhase.READY
        is GenbuSettingsState.Failed -> CliDetectionPhase.FAILED
    }
    SettingsCard("Genbu", "配置并检查生产版本查询命令。") {
        AutoSaveStatus(controller, "genbu")
        CliCommandPanel(
            controller = controller,
            title = "Genbu 命令",
            command = command,
            source = genbuSourceLabel(source),
            version = loaded?.version,
            phase = phase,
            failure = (state as? GenbuSettingsState.Failed)?.message,
            configuredPath = genbuPath,
            pathLabel = "Genbu 可执行文件路径",
            pathPlaceholder = if (System.getProperty("os.name").startsWith("Windows", true))
                "例如 C:\\tools\\genbu.exe" else "例如 /usr/local/bin/genbu",
            saving = saving,
            onPathChange = onGenbuPathChange,
            onSavePath = { raw ->
                controller.updateGenbuExecutablePath(raw) { onGenbuPathChange(controller.config.genbuExecutablePath.orEmpty()) }
            },
            onChoosePath = { initial -> controller.chooseApplication(initial) { onGenbuPathChange(it) } },
            onRefresh = { controller.refreshGenbu(force = true) },
        )
    }
}

@Composable
private fun SettingsPathsSection(
    controller: DesktopApplication,
    taskRoot: String,
    onTaskRootChange: (String) -> Unit,
    requirementMaterialsRoot: String,
    onRequirementMaterialsRootChange: (String) -> Unit,
    requirementMaterialsSubdirectory: String,
    onRequirementMaterialsSubdirectoryChange: (String) -> Unit,
    saving: Boolean,
    backupMenuExpanded: Boolean,
    onBackupMenuExpandedChange: (Boolean) -> Unit,
    onRestoreBackup: (ConfigStore.Backup) -> Unit,
    onImportPreview: (ConfigStore.ImportPreview) -> Unit,
) {
    val materialsSaving = controller.settingsSaveState("requirement-materials-root") == SettingsSaveState.SAVING ||
        controller.settingsSaveState("requirement-materials-subdirectory") == SettingsSaveState.SAVING
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsCard("任务路径设置", "选择 AWM 扫描任务的根目录。") {
            AutoSaveStatus(controller, "paths")
            PathField(
                "任务根目录",
                taskRoot,
                onTaskRootChange,
                !controller.pathPickerBusy && !controller.busy && !saving,
                Modifier.onFocusChanged { focus ->
                    if (!focus.isFocused && taskRoot.isNotBlank() && taskRoot != controller.config.taskRoot.orEmpty()) {
                        controller.updateTaskRoot(taskRoot) { onTaskRootChange(controller.config.taskRoot.orEmpty()) }
                    }
                },
            ) {
                controller.chooseDirectory(taskRoot) { selected ->
                    onTaskRootChange(selected)
                    controller.updateTaskRoot(selected) { onTaskRootChange(controller.config.taskRoot.orEmpty()) }
                }
            }
            TaskManifestIssues(controller)
        }
        SettingsCard("需求资料目录设置", "需求编号已填写且以下两项均不为空时，AWM 会创建或复用需求资料目录；Agent CLI 会在同一目录补充过程文档。") {
            AutoSaveStatus(controller, "requirement-materials-root")
            AutoSaveStatus(controller, "requirement-materials-subdirectory")
            PathField(
                "需求资料根目录",
                requirementMaterialsRoot,
                onRequirementMaterialsRootChange,
                !controller.pathPickerBusy && !controller.busy && !materialsSaving,
                Modifier.onFocusChanged { focus ->
                    if (!focus.isFocused && requirementMaterialsRoot != controller.config.requirementMaterialsRoot.orEmpty()) {
                        controller.updateRequirementMaterialsRoot(requirementMaterialsRoot) {
                            onRequirementMaterialsRootChange(controller.config.requirementMaterialsRoot.orEmpty())
                        }
                    }
                },
            ) {
                controller.chooseDirectory(requirementMaterialsRoot.ifBlank { null }) { selected ->
                    onRequirementMaterialsRootChange(selected)
                    controller.updateRequirementMaterialsRoot(selected) {
                        onRequirementMaterialsRootChange(controller.config.requirementMaterialsRoot.orEmpty())
                    }
                }
            }
            OutlinedTextField(
                value = requirementMaterialsSubdirectory,
                onValueChange = onRequirementMaterialsSubdirectoryChange,
                modifier = Modifier.fillMaxWidth().onFocusChanged { focus ->
                    if (!focus.isFocused && requirementMaterialsSubdirectory != controller.config.requirementMaterialsSubdirectory.orEmpty()) {
                        controller.updateRequirementMaterialsSubdirectory(requirementMaterialsSubdirectory) {
                            onRequirementMaterialsSubdirectoryChange(controller.config.requirementMaterialsSubdirectory.orEmpty())
                        }
                    }
                },
                label = { Text("需求资料子目录") },
                placeholder = { Text("例如：研发") },
                supportingText = { Text("可留空；非空时只能填写一个安全的 Windows 目录名，保存时会自动去除首尾空格。") },
                singleLine = true,
                enabled = !controller.busy && !materialsSaving,
            )
            Text(
                if (controller.config.requirementMaterialsConfigured) "需求资料目录功能已配置" else "根路径和子目录名均填写后才会启用需求资料目录功能",
                color = if (controller.config.requirementMaterialsConfigured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SettingsCard("系统主配置文件", "只读预览 AWM 的全局配置文件，并管理配置备份。") {
            ConfigFilePreview(controller)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("配置操作", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = controller::exportConfig, enabled = !controller.busy) { Text("导出配置") }
                OutlinedButton(
                    onClick = {
                        controller.chooseFile(null) { selected ->
                            runCatching { controller.previewConfigImport(selected) }
                                .onSuccess { onImportPreview(it) }
                                .onFailure(controller::showError)
                        }
                    },
                    enabled = !controller.busy && !controller.pathPickerBusy,
                ) { Text("导入配置") }
                Box {
                    OutlinedButton(onClick = { onBackupMenuExpandedChange(true) }, enabled = !controller.busy) { Text("恢复备份") }
                    AwmDropdownMenu(backupMenuExpanded, onDismissRequest = { onBackupMenuExpandedChange(false) }) {
                        val backups = controller.configBackups()
                        if (backups.isEmpty()) DropdownMenuItem(text = { Text("暂无配置备份") }, onClick = {}, enabled = false)
                        backups.forEach { backup ->
                            DropdownMenuItem(
                                text = { Text(backup.path.fileName.toString()) },
                                onClick = { onBackupMenuExpandedChange(false); onRestoreBackup(backup) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCliSection(controller: DesktopApplication) {
    val status = controller.cliInstallationStatus
    var confirmUninstall by remember { mutableStateOf(false) }
    SettingsCard("AWM CLI", "将绿色包内置的 Agent CLI 安装为当前用户可用的 awm 命令。") {
        Text("安装状态", style = MaterialTheme.typography.titleSmall)
        SelectionContainer {
            Text(status.message, style = MaterialTheme.typography.bodyMedium)
        }
        status.commandPath?.let { commandPath ->
            SelectionContainer {
                Text(
                    "命令入口：$commandPath",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (status.supported) {
            Text(
                "安装会复制绿色包中的 CLI 与 Java 运行时至当前用户的 LOCALAPPDATA，并将其命令目录加入用户 PATH；不需要管理员权限。完成后请重开终端；若从 Codex、IDE 或 Windows Terminal 打开终端，请重启对应应用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = controller::installCli,
                    enabled = status.bundledPayloadAvailable && (status.installed || !status.uninstallAvailable) && !controller.settingsBusy,
                ) {
                    Icon(Icons.Outlined.Terminal, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (status.installed) "更新 CLI" else "安装 CLI")
                }
                OutlinedButton(onClick = controller::refreshCliInstallationStatus, enabled = !controller.settingsBusy) {
                    Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("刷新状态")
                }
                OutlinedButton(
                    onClick = { confirmUninstall = true },
                    enabled = status.uninstallAvailable && !controller.settingsBusy,
                ) {
                    Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("卸载 CLI")
                }
            }
        } else {
            Text(
                "macOS/Linux 的绿色包内提供 bin/awm；该一键安装入口目前只适用于 Windows。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (confirmUninstall) {
        ConfirmDialog(
            title = "卸载 AWM CLI？",
            message = "将删除 AWM 安装的所有 CLI 版本和随附运行时，并从当前用户 PATH 移除 awm。不会删除任务、配置、项目文件或系统 Java。",
            confirmLabel = "卸载 CLI",
            destructive = true,
            enabled = !controller.settingsBusy,
            onDismiss = { confirmUninstall = false },
            onConfirm = {
                controller.uninstallCli()
                confirmUninstall = false
            },
        )
    }
}

@Composable
private fun SettingsGroupsSection(
    controller: DesktopApplication,
    onNewGroup: () -> Unit,
    onRenameGroup: (GroupConfig) -> Unit,
    onDeleteGroup: (GroupConfig) -> Unit,
) {
    SettingsCard("服务与仓库", "按任务组维护仓库和服务；只能删除没有服务和任务引用的空组。") {
        AutoSaveStatus(controller, "groups")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = { controller.navigation = NavigationItem.SERVICES }) {
                Text("打开服务仓库")
            }
        }
        controller.config.groups.forEachIndexed { index, group ->
            GroupSettingsRow(
                controller = controller,
                group = group,
                index = index,
                groupCount = controller.config.groups.size,
                onRename = { onRenameGroup(group) },
                onDelete = { onDeleteGroup(group) },
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = onNewGroup) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("创建组") }
        }
    }
}

@Composable
private fun SettingsAgentsSection(
    controller: DesktopApplication,
    agentScope: String,
    onAgentScopeChange: (String) -> Unit,
    agentGroupId: String,
    onAgentGroupIdChange: (String) -> Unit,
    globalAgents: String,
    onGlobalAgentsChange: (String) -> Unit,
    groupAgentDrafts: MutableMap<String, String>,
) {
    SettingsCard("全局与组说明", "磁盘中的全局/组 AGENTS.md 是唯一准确来源，保存后会同步相关任务。") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(agentScope == "global", { onAgentScopeChange("global") }, label = { Text("全局") })
            controller.config.groups.forEach { group ->
                FilterChip(agentScope == group.id, {
                    onAgentScopeChange(group.id)
                    onAgentGroupIdChange(group.id)
                }, label = { Text(group.name) })
            }
        }
        val isGlobal = agentScope == "global"
        val agentPath = if (isGlobal) controller.globalAgentsPath else controller.groupAgentsPath(agentGroupId)
        Text(
            if (isGlobal) "对所有任务生效；也可直接编辑 ~/awm/agents/global/AGENTS.md，程序会自动同步。"
            else "仅对当前组生效；也可直接编辑 ~/awm/agents/groups/<groupId>/AGENTS.md，程序会自动同步。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), shape = RoundedCornerShape(10.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(agentPath, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = {
                    if (isGlobal) controller.revealGlobalAgents() else controller.revealGroupAgents(agentGroupId)
                }) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("打开位置") }
                ActionIconButton("复制 Agent 文件完整路径", { controller.copyText(agentPath, "完整路径已复制") }) {
                    Icon(Icons.Outlined.ContentCopy, "复制完整路径")
                }
            }
        }
        if (isGlobal) {
            OutlinedTextField(globalAgents, {
                onGlobalAgentsChange(it)
                controller.markGlobalAgentsEdited(it)
            }, Modifier.fillMaxWidth(), minLines = 10, readOnly = controller.busy)
        } else {
            OutlinedTextField(groupAgentDrafts[agentGroupId].orEmpty(), {
                groupAgentDrafts[agentGroupId] = it
                controller.markGroupAgentsEdited(agentGroupId, it)
            }, Modifier.fillMaxWidth(), minLines = 10, readOnly = controller.busy)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = {
                    if (isGlobal) controller.saveGlobalAgents(globalAgents)
                    else controller.saveGroupAgents(agentGroupId, groupAgentDrafts[agentGroupId].orEmpty())
                },
                enabled = !controller.busy,
            ) { Text(if (isGlobal) "保存全局说明" else "保存组说明") }
        }
    }
}

@Composable
private fun SettingsTaskTemplatesSection(
    controller: DesktopApplication,
    onNewTemplate: () -> Unit,
    onEditTemplate: (AgentTaskTemplate) -> Unit,
    onDeleteTemplate: (AgentTaskTemplate) -> Unit,
) {
    SettingsCard("任务说明模板", "创建任务时可勾选一个模板自动填充任务人工说明；模板修改不影响已创建的任务。") {
        if (controller.agentTaskTemplates.isEmpty()) {
            Text("还没有模板。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        controller.agentTaskTemplates.forEach { template ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(template.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            template.content.lineSequence().firstOrNull().orEmpty(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ActionIconButton("删除模板", { onDeleteTemplate(template) }, enabled = !controller.busy) { Icon(Icons.Outlined.Delete, "删除") }
                    ActionIconButton("编辑模板", { onEditTemplate(template) }, enabled = !controller.busy) { Icon(Icons.Outlined.Edit, "编辑") }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = onNewTemplate, enabled = !controller.busy) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("新建模板")
            }
        }
    }
}

@Composable
private fun TaskTemplateDialog(
    title: String,
    initialName: String,
    initialContent: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var content by remember { mutableStateOf(initialContent) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(20.dp).widthIn(max = 560.dp).heightIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("模板名称") }, singleLine = true)
                    OutlinedTextField(
                        content,
                        { content = it },
                        Modifier.fillMaxWidth().heightIn(max = 320.dp),
                        label = { Text("模板内容") },
                        supportingText = { Text("创建任务勾选后填充到任务人工说明，仍可继续编辑。") },
                        minLines = 8,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onSave(name, content) }, enabled = name.isNotBlank() && content.isNotBlank()) { Text("保存") }
                }
            }
        }
    }
}

@Composable
private fun SettingsToolsSection(
    controller: DesktopApplication,
    developmentToolPaths: MutableMap<DevelopmentToolType, String>,
    defaultDevelopmentTool: DevelopmentToolType,
    onDefaultDevelopmentToolChange: (DevelopmentToolType) -> Unit,
    allowTemporaryDevelopmentToolSelection: Boolean,
    onAllowTemporaryDevelopmentToolSelectionChange: (Boolean) -> Unit,
    terminal: String,
    onTerminalChange: (String) -> Unit,
    saving: Boolean,
    onSaveDevelopmentTools: () -> Unit,
) {
    SettingsCard(
        "开发工具",
        "启动后会在后台静默探测尚未填写的工具路径；已有路径即使失效也不会被覆盖。未配置的工具不会出现在临时打开列表中。",
    ) {
        AutoSaveStatus(controller, "tools")
        DevelopmentToolType.entries.forEach { type ->
            val value = developmentToolPaths[type].orEmpty()
            val valid = value.isNotBlank() && runCatching { Files.exists(Path.of(value)) }.getOrDefault(false)
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(type.displayName, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Text(
                            when {
                                valid -> "已配置 · 可用"
                                value.isNotBlank() -> "路径无效"
                                else -> "等待自动探测"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = when {
                                valid -> SuccessGreen
                                value.isNotBlank() -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    OutlinedTextField(
                        value,
                        { developmentToolPaths[type] = it },
                        Modifier.fillMaxWidth().onFocusChanged { focus ->
                            val configured = controller.config.developmentTools.firstOrNull { it.type == type }?.path.orEmpty()
                            if (!focus.isFocused && developmentToolPaths[type].orEmpty().trim() != configured) onSaveDevelopmentTools()
                        },
                        label = { Text("应用路径") },
                        placeholder = { Text("留空时由 AWM 自动探测") },
                        singleLine = true,
                        readOnly = controller.busy || saving,
                    )
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { controller.chooseApplication(value) { developmentToolPaths[type] = it; onSaveDevelopmentTools() } },
                            enabled = !controller.pathPickerBusy && !controller.busy && !saving,
                        ) { Icon(Icons.Outlined.Folder, null); Spacer(Modifier.width(4.dp)); Text("手动选择") }
                        OutlinedButton(
                            onClick = { controller.testDevelopmentTool(type, value) },
                            enabled = valid && !controller.busy,
                        ) { Text("测试打开") }
                        if (value.isNotBlank()) {
                            OutlinedButton(
                                onClick = { controller.resetDevelopmentToolToAutomatic(type) },
                                enabled = !controller.busy && !saving,
                            ) { Text("恢复自动") }
                        } else {
                            OutlinedButton(
                                onClick = controller::redetectDevelopmentTools,
                                enabled = !controller.busy && !saving,
                            ) { Icon(Icons.Outlined.Refresh, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("重新检测") }
                        }
                    }
                }
            }
        }
        Text("全局默认开发工具", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DevelopmentToolType.entries.forEach { type ->
                FilterChip(
                    selected = defaultDevelopmentTool == type,
                    onClick = { onDefaultDevelopmentToolChange(type); onSaveDevelopmentTools() },
                    label = { Text(type.displayName) },
                    enabled = !controller.busy && !saving,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("临时选择开发工具", style = MaterialTheme.typography.titleSmall)
                Text(
                    "默认关闭；开启后，任务工具栏和每个工作区的打开按钮旁显示开发工具下拉选择。不会在创建任务后自动打开服务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = allowTemporaryDevelopmentToolSelection,
                onCheckedChange = { enabled ->
                    onAllowTemporaryDevelopmentToolSelectionChange(enabled)
                    onSaveDevelopmentTools()
                },
                enabled = !controller.busy && !saving,
            )
        }
        val terminalResolution = controller.terminalCommandResolution()
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("终端", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            controller.config.terminalExecutable.isNullOrBlank() -> "自动"
                            terminalResolution.available -> "手动配置 · 可用"
                            else -> "路径无效"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (terminalResolution.available) SuccessGreen else MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    "当前使用：${terminalResolution.displayName} · ${terminalResolution.command}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PathField(
                    "自定义终端", terminal, onTerminalChange, !controller.pathPickerBusy && !controller.busy && !saving,
                    Modifier.onFocusChanged { focus ->
                        if (!focus.isFocused && terminal.trim() != controller.config.terminalExecutable.orEmpty()) onSaveDevelopmentTools()
                    },
                ) {
                    val selected: (String) -> Unit = { path -> onTerminalChange(path); onSaveDevelopmentTools() }
                    if (terminalUsesApplicationPicker(System.getProperty("os.name"))) {
                        controller.chooseApplication(terminal, selected)
                    } else {
                        controller.chooseFile(terminal, selected)
                    }
                }
                if (terminal.isNotBlank()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        OutlinedButton(
                            onClick = {
                                onTerminalChange("")
                                onSaveDevelopmentTools()
                            },
                            enabled = !controller.busy && !saving,
                        ) { Text("恢复自动") }
                    }
                }
            }
        }
    }
}

internal fun terminalUsesApplicationPicker(osName: String): Boolean = osName.startsWith("Mac", ignoreCase = true)

@Composable
private fun SettingsBranchesSection(
    controller: DesktopApplication,
    hiddenBranchInput: String,
    onHiddenBranchInputChange: (String) -> Unit,
    hiddenTaskDetailBranches: List<String>,
    saving: Boolean,
    onSaveHiddenBranches: (List<String>) -> Unit,
) {
    SettingsCard("分支", "配置任务详情头部不展示的分支名；按完整名称区分大小写精确匹配。") {
        AutoSaveStatus(controller, "branches")
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = hiddenBranchInput,
                onValueChange = onHiddenBranchInputChange,
                modifier = Modifier.weight(1f),
                label = { Text("不展示的分支名") },
                placeholder = { Text("例如 master") },
                singleLine = true,
            )
            OutlinedButton(
                onClick = {
                    val branch = hiddenBranchInput.trim()
                    onSaveHiddenBranches(hiddenTaskDetailBranches + branch)
                    onHiddenBranchInputChange("")
                },
                enabled = hiddenBranchInput.trim().isNotEmpty() && hiddenBranchInput.trim() !in hiddenTaskDetailBranches && !saving,
            ) {
                Icon(Icons.Outlined.Add, null, Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text("添加")
            }
        }
        if (hiddenTaskDetailBranches.isEmpty()) {
            Text("尚未配置；所有实际分支都会显示在任务详情头部。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                hiddenTaskDetailBranches.forEach { branch ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Row(Modifier.padding(start = 10.dp, end = 3.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            SelectionContainer { Text(branch, style = MaterialTheme.typography.bodyMedium) }
                            ActionIconButton(
                                "删除不展示分支 $branch",
                                { onSaveHiddenBranches(hiddenTaskDetailBranches - branch) },
                                Modifier.size(28.dp),
                                enabled = !saving,
                            ) { Icon(Icons.Outlined.Delete, "删除", Modifier.size(15.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsGitSection(
    controller: DesktopApplication,
    blockedGitBranchInput: String,
    onBlockedGitBranchInputChange: (String) -> Unit,
    blockedGitWriteBranches: List<String>,
    saving: Boolean,
    onSaveBlockedGitBranches: (List<String>) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsCard("Git 环境", "自动探测或配置 Git 命令，并读取本机身份和全局配置。") {
            AutoSaveStatus(controller, "git")
            GitEnvironmentPanel(controller, controller.localGitSettingsState)
        }
        SettingsCard("分支写保护", "保护指定分支，避免在 AWM 内执行 Git 写操作。") {
            AutoSaveStatus(controller, "git-write-policy")
        Text("分支写保护", style = MaterialTheme.typography.titleSmall)
        Text("在以下实际当前分支上禁用 Commit、Push、Commit & Push，以及需要写入分支的测试Tag流程。按完整名称忽略大小写匹配。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                blockedGitBranchInput,
                onBlockedGitBranchInputChange,
                Modifier.weight(1f),
                label = { Text("受保护分支") },
                placeholder = { Text("例如 master") },
                singleLine = true,
            )
            OutlinedButton(
                onClick = {
                    onSaveBlockedGitBranches(blockedGitWriteBranches + blockedGitBranchInput.trim())
                    onBlockedGitBranchInputChange("")
                },
                enabled = blockedGitBranchInput.isNotBlank() && blockedGitWriteBranches.none { it.equals(blockedGitBranchInput.trim(), true) } && !saving,
            ) { Icon(Icons.Outlined.Add, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("添加") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blockedGitWriteBranches.forEach { branch ->
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Row(Modifier.padding(start = 10.dp, end = 3.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(branch)
                        ActionIconButton("删除写保护分支 $branch", { onSaveBlockedGitBranches(blockedGitWriteBranches - branch) }, Modifier.size(28.dp), enabled = !saving) {
                            Icon(Icons.Outlined.Delete, null, Modifier.size(15.dp))
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun GitEnvironmentPanel(controller: DesktopApplication, state: LocalGitSettingsState) {
    val snapshot = displayedGitSnapshot(state)
    val refreshing = state is LocalGitSettingsState.Loading
    val (command, source) = controller.gitCommandResolution()
    CliCommandPanel(
        controller = controller,
        title = "Git 命令",
        command = command,
        source = gitSourceLabel(source),
        version = snapshot?.gitVersion?.let { CommandVersionStatus(command = command, version = it) },
        phase = when (state) {
            LocalGitSettingsState.Idle -> CliDetectionPhase.IDLE
            is LocalGitSettingsState.Loading -> CliDetectionPhase.LOADING
            is LocalGitSettingsState.Loaded -> CliDetectionPhase.READY
            is LocalGitSettingsState.Failed -> CliDetectionPhase.FAILED
        },
        failure = (state as? LocalGitSettingsState.Failed)?.message,
        configuredPath = controller.config.gitExecutablePath.orEmpty(),
        pathLabel = "Git 可执行文件路径",
        pathPlaceholder = if (System.getProperty("os.name").startsWith("Windows", true))
            "例如 C:\\Program Files\\Git\\cmd\\git.exe" else "例如 /usr/bin/git",
        saving = controller.settingsSaveState("git") == SettingsSaveState.SAVING,
        onPathChange = {},
        onSavePath = { raw ->
            controller.updateGitExecutablePath(raw) { }
        },
        onChoosePath = { initial -> controller.chooseApplication(initial) { selected -> controller.updateGitExecutablePath(selected) } },
        onRefresh = { controller.refreshLocalGit(force = true) },
        extra = {
            snapshot?.let {
                GitEnvironmentSummary(it)
                OutlinedButton(onClick = { controller.copyText(formatLocalGitSettings(it), "Git 信息已复制") }) {
                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("复制全部 Git 信息")
                }
            }
            (state as? LocalGitSettingsState.Failed)?.let { failureState ->
                OutlinedButton(onClick = { controller.copyText(failureState.message, "Git 错误已复制") }) {
                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("复制错误")
                }
            }
            if (snapshot == null && state is LocalGitSettingsState.Loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        },
    )
}


@Composable
private fun GitEnvironmentSummary(snapshot: LocalGitEnvironmentSnapshot) {
    val credentialFields = snapshot.globalCredentialHelpers.ifEmpty {
        listOf(GitConfigValue("credential.helper", "未配置", null))
    }.map { GitEnvironmentField(it.key, it.value, it.origin) }
    val globalConfigFields = visibleGlobalGitKeyConfig(snapshot).map { GitEnvironmentField(it.key, it.value, it.origin) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GitEnvironmentSection(
            "身份",
            listOf(
                GitEnvironmentField("系统用户", snapshot.systemUser.ifBlank { "未读取到" }),
                GitEnvironmentField("全局 user.name", snapshot.globalUserName?.value ?: "未配置", snapshot.globalUserName?.origin),
                GitEnvironmentField("全局 user.email", snapshot.globalUserEmail?.value ?: "未配置", snapshot.globalUserEmail?.origin),
            ),
        )
        GitEnvironmentSection("凭据与其他全局配置", credentialFields + globalConfigFields)
        snapshot.errors.forEach { error ->
            Text("全局读取错误：$error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

private data class GitEnvironmentField(
    val label: String,
    val value: String,
    val origin: String? = null,
    val monospace: Boolean = false,
)

@Composable
private fun GitEnvironmentSection(title: String, fields: List<GitEnvironmentField>) {
    Surface(
        Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            GitEnvironmentGrid(fields)
        }
    }
}

@Composable
private fun GitEnvironmentGrid(fields: List<GitEnvironmentField>) {
    fields.chunked(2).forEach { row ->
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.Top,
        ) {
            row.forEach { field ->
                GitEnvironmentValue(
                    label = field.label,
                    value = field.value,
                    origin = field.origin,
                    monospace = field.monospace,
                    modifier = Modifier.weight(1f),
                )
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun GitEnvironmentValue(
    label: String,
    value: String,
    origin: String? = null,
    monospace: Boolean = false,
    modifier: Modifier = Modifier,
) {
    SelectionContainer {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = if (monospace) FontFamily.Monospace else null)
            origin?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun ConfigFilePreview(controller: DesktopApplication) {
    val snapshot = controller.configFileSnapshot
    val content = snapshot.content
    SelectionContainer {
        Text(
            snapshot.path.toString(),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = controller::refreshConfigFileSnapshot, enabled = !controller.configFileSnapshotRefreshing) {
            Text(if (controller.configFileSnapshotRefreshing) "正在刷新" else "刷新预览")
        }
        OutlinedButton(onClick = { controller.copyText(snapshot.path.toString(), "主配置路径已复制") }) { Text("复制路径") }
        OutlinedButton(onClick = controller::revealConfigFile, enabled = snapshot.exists) { Text("定位文件") }
    }
    when {
        !snapshot.exists -> Text(
            "配置文件尚未创建；保存任一设置后会在该路径生成。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        snapshot.readError != null -> Text(
            "无法读取配置文件：${snapshot.readError}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        content != null -> Surface(
            Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 360.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            shape = RoundedCornerShape(10.dp),
        ) {
            SelectionContainer {
                Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
                    Text(content, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun TaskManifestIssues(controller: DesktopApplication) {
    val issues = controller.taskManifestIssues
    if (issues.isEmpty()) return

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("任务清单问题", style = MaterialTheme.typography.titleSmall)
            Text("这些文件未被 AWM 读取或改写。请先备份后再手工修复或删除。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = controller::refreshTaskManifestIssues) { Text("重新扫描") }
    }
    issues.forEach { issue ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(issue.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                SelectionContainer {
                    Text(issue.manifestPath, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { controller.copyText(issue.manifestPath, "任务清单路径已复制") }) { Text("复制路径") }
                    TextButton(onClick = { controller.reveal(issue.manifestPath) }) { Text("定位文件") }
                    TextButton(onClick = { controller.copyText(controller.taskManifestRecoveryGuidance(issue), "恢复指引已复制") }) { Text("复制恢复指引") }
                }
            }
        }
    }
}


@Composable
private fun SettingsFeishuSection(
    controller: DesktopApplication,
    meegleProjects: MutableMap<Int, MeegleProjectConfig>,
    meegleMenuExpanded: Boolean,
    onMeegleMenuExpandedChange: (Boolean) -> Unit,
    saving: Boolean,
    onSaveMeegleProjects: () -> Unit,
) {
    SettingsCard("Meegle", "管理创建任务时用于读取需求的 Meegle 项目。") {
        AutoSaveStatus(controller, "feishu")
        MeegleCliStatusPanel(controller)
        Text("Meegle 项目", style = MaterialTheme.typography.titleSmall)
        Text(
            "点击添加后从本机 Meegle CLI 读取项目；创建任务时会拉取已配置项目中的 Meegle 需求链接。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        meegleProjects.toSortedMap().forEach { (index, project) ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(project.simpleName, fontWeight = FontWeight.SemiBold)
                        Text(project.projectKey, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ActionIconButton("删除 Meegle 项目配置", { meegleProjects.remove(index); onSaveMeegleProjects() }, enabled = !controller.busy && !saving) {
                        Icon(Icons.Outlined.Delete, "删除项目")
                    }
                }
            }
        }
        Box {
            OutlinedButton(
                onClick = {
                    onMeegleMenuExpandedChange(true)
                    controller.refreshMeegleStatus(force = true)
                },
                enabled = (controller.meegleCliState as? MeegleCliState.Ready)?.status?.authenticated == true && !saving,
            ) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text("添加项目")
            }
            AwmDropdownMenu(
                expanded = meegleMenuExpanded,
                onDismissRequest = { onMeegleMenuExpandedChange(false) },
                modifier = Modifier.widthIn(min = 520.dp, max = 700.dp),
            ) {
                when (val state = controller.meegleProjectCatalogState) {
                    MeegleProjectCatalogState.Idle, MeegleProjectCatalogState.Loading ->
                        DropdownMenuItem(text = { Text("正在读取 Meegle 项目…") }, onClick = {}, enabled = false)
                    is MeegleProjectCatalogState.Failed ->
                        DropdownMenuItem(
                            text = { Text("读取失败：${state.message}", color = MaterialTheme.colorScheme.error) },
                            onClick = { controller.loadMeegleProjects(force = true) },
                        )
                    is MeegleProjectCatalogState.Loaded -> {
                        val selectedKeys = meegleProjects.values.map(MeegleProjectConfig::projectKey).toSet()
                        val available = state.projects.filterNot { it.projectKey in selectedKeys }
                        if (available.isEmpty()) {
                            DropdownMenuItem(text = { Text("没有可添加的项目") }, onClick = {}, enabled = false)
                        }
                        available.forEach { project ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text("${project.name} · ${project.simpleName}")
                                        Text(project.projectKey, style = MaterialTheme.typography.labelSmall)
                                    }
                                },
                                onClick = {
                                    val index = (meegleProjects.keys.maxOrNull() ?: -1) + 1
                                    meegleProjects[index] = MeegleProjectConfig(project.projectKey, project.simpleName)
                                    onMeegleMenuExpandedChange(false)
                                    onSaveMeegleProjects()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsLogsSection(controller: DesktopApplication) {
    SettingsCard("诊断与日志", "查看最近错误、打开日志目录或导出诊断包。") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("最近错误", style = MaterialTheme.typography.titleSmall)
                Text("展示 application 日志中最近 10 条 ERROR 记录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = controller::refreshErrorLog) { Text("刷新") }
            TextButton(onClick = {
                controller.copyText(
                    ApplicationEventClipboard.latest(controller.recentErrors).orEmpty(),
                    "最新错误已复制",
                )
            }, enabled = controller.recentErrors.isNotEmpty()) { Text("最新") }
            TextButton(onClick = {
                controller.copyText(
                    ApplicationEventClipboard.all(controller.recentErrors),
                    "错误日志已复制",
                )
            }, enabled = controller.recentErrors.isNotEmpty()) { Text("全部") }
            TextButton(onClick = controller::openLogDirectory) { Text("目录") }
            TextButton(onClick = controller::exportDiagnostics, enabled = !controller.busy) { Text("诊断包") }
        }
        Surface(
            Modifier.fillMaxWidth().heightIn(min = 360.dp, max = 560.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            shape = RoundedCornerShape(10.dp),
        ) {
            SelectionContainer {
                Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (controller.recentErrors.isEmpty()) {
                        Text("暂无错误记录", style = MaterialTheme.typography.bodySmall)
                    }
                    controller.recentErrors.forEach { event ->
                        Column {
                            Text("${event.timestamp} · ${event.event}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(event.message, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MeegleCliStatusPanel(controller: DesktopApplication) {
    val cliState = controller.meegleCliState
    val status = when (cliState) {
        is MeegleCliState.Ready -> cliState.status
        is MeegleCliState.Loading -> cliState.previous
        MeegleCliState.Idle, is MeegleCliState.Failed -> null
    }
    val (command, source) = controller.meegleCommandResolution()
    val saving = controller.settingsSaveState("feishu") == SettingsSaveState.SAVING
    val phase = when {
        cliState is MeegleCliState.Failed -> CliDetectionPhase.FAILED
        cliState is MeegleCliState.Loading -> CliDetectionPhase.LOADING
        cliState is MeegleCliState.Ready && cliState.status.installed -> CliDetectionPhase.READY
        cliState is MeegleCliState.Ready -> CliDetectionPhase.FAILED
        else -> CliDetectionPhase.IDLE
    }
    val failure = when (cliState) {
        is MeegleCliState.Failed -> cliState.message
        is MeegleCliState.Ready -> cliState.status.takeUnless { it.installed }?.let { "未安装或无法启动 Meegle CLI" }
        else -> null
    }
    CliCommandPanel(
        controller = controller,
        title = "Meegle CLI",
        command = command,
        source = meegleSourceLabel(source),
        version = status?.version?.let { CommandVersionStatus(command = command, version = it) },
        phase = phase,
        failure = failure,
        configuredPath = controller.config.meegleExecutablePath.orEmpty(),
        pathLabel = "Meegle 可执行文件路径",
        pathPlaceholder = if (System.getProperty("os.name").startsWith("Windows", true))
            "例如 C:\\tools\\meegle.cmd" else "例如 /opt/homebrew/bin/meegle",
        saving = saving,
        onPathChange = {},
        onSavePath = { raw -> controller.updateMeegleExecutablePath(raw) },
        onChoosePath = { initial -> controller.chooseApplication(initial) { selected -> controller.updateMeegleExecutablePath(selected) } },
        onRefresh = { controller.refreshMeegleStatus(force = true) },
        extra = {
            status?.let { current ->
                Text(
                    buildString {
                        append("站点：${current.host ?: "project.feishu.cn"}")
                        current.expiresInMinutes?.let { append(" · 凭据剩余 $it 分钟") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (current.authenticated && (current.expiresInMinutes ?: Long.MAX_VALUE) <= 5) {
                    Text("登录凭据即将过期，建议刷新或重新登录", style = MaterialTheme.typography.labelSmall, color = WarningAmber)
                }
                if (current.installed && !current.authenticated) {
                    Button(onClick = controller::loginMeegle, enabled = !controller.meegleBusy) { Text("登录 Meegle") }
                }
            }
            if (controller.meegleBusy && controller.meegleOperationCancellable) {
                TextButton(onClick = { controller.cancelMeegleOperation() }) { Text("取消登录") }
            }
            controller.meegleOperationError?.let { error ->
                SelectionContainer { Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
    )
}

@Composable
private fun AutoSaveStatus(controller: DesktopApplication, key: String) {
    val state = controller.settingsSaveState(key)
    if (state == SettingsSaveState.IDLE) return
    Text(
        when (state) {
            SettingsSaveState.IDLE -> ""
            SettingsSaveState.SAVING -> "正在自动保存…"
            SettingsSaveState.SAVED -> "已自动保存"
            SettingsSaveState.FAILED -> "自动保存失败，已恢复原值"
        },
        style = MaterialTheme.typography.labelSmall,
        color = if (state == SettingsSaveState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun GroupSettingsRow(
    controller: DesktopApplication,
    group: GroupConfig,
    index: Int,
    groupCount: Int,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var branchPrefix by remember(group.id, group.defaultBranchPrefix) { mutableStateOf(group.defaultBranchPrefix) }
    var selectedToolIds by remember(group.id, group.defaultWorkspaceToolIds) {
        mutableStateOf(group.defaultWorkspaceToolIds.toSet())
    }
    val toolOptions = controller.workspaceToolOptions(group.id)
    val defaultsSaving = controller.settingsSaveState("groups") == SettingsSaveState.SAVING
    fun saveDefaults() {
        controller.updateGroupDefaults(group.id, branchPrefix, selectedToolIds.toList()) {
            val persisted = controller.config.groups.firstOrNull { it.id == group.id } ?: group
            branchPrefix = persisted.defaultBranchPrefix
            selectedToolIds = persisted.defaultWorkspaceToolIds.toSet()
        }
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(group.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (groupCount == 1) "任务和服务界面隐藏组层级 · ${group.services.size} 个服务" else "${group.services.size} 个服务",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text("测试Tag")
                Spacer(Modifier.width(8.dp))
                Switch(group.tagEnabled, { controller.setGroupTagEnabled(group.id, it) }, enabled = !controller.busy && !defaultsSaving)
                if (groupCount > 1) {
                    ActionIconButton("上移组", { controller.moveGroup(group.id, -1) }, enabled = index > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "上移") }
                    ActionIconButton("下移组", { controller.moveGroup(group.id, 1) }, enabled = index < groupCount - 1) { Icon(Icons.Outlined.KeyboardArrowDown, "下移") }
                    ActionIconButton(
                        label = "删除空组",
                        onClick = onDelete,
                        enabled = group.services.isEmpty(),
                    ) { Icon(Icons.Outlined.Delete, "删除") }
                }
                ActionIconButton("重命名组", onRename) { Icon(Icons.Outlined.Edit, "重命名") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            OutlinedTextField(
                branchPrefix,
                { branchPrefix = it },
                Modifier.fillMaxWidth().onFocusChanged { focus ->
                    if (!focus.isFocused && branchPrefix != group.defaultBranchPrefix) saveDefaults()
                },
                label = { Text("默认分支名前缀") },
                placeholder = { Text("例如 feature/zhangsan_{num}_") },
                supportingText = { Text("{num} 会从需求链接或文本的最后一段数字解析；创建页仍可继续修改。") },
                singleLine = true,
                readOnly = controller.busy || defaultsSaving,
            )
            Text("任务完成后默认打开", style = MaterialTheme.typography.titleSmall)
            if (toolOptions.isEmpty()) {
                Text("当前没有已注册的任务工作区工具。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                toolOptions.forEach { tool ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = tool.id in selectedToolIds,
                            onCheckedChange = { checked ->
                                selectedToolIds = if (checked) selectedToolIds + tool.id else selectedToolIds - tool.id
                                saveDefaults()
                            },
                            enabled = tool.available && !controller.busy && !defaultsSaving,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(tool.displayName)
                            Text(
                                if (tool.available) tool.description else "当前不可用：${tool.unavailableReason}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (tool.available) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun visibleGlobalGitKeyConfig(snapshot: LocalGitEnvironmentSnapshot): List<GitConfigValue> =
    snapshot.globalKeyConfig.filterNot { it.key in setOf("user.name", "user.email") }

internal fun displayedGitSnapshot(state: LocalGitSettingsState): LocalGitEnvironmentSnapshot? = when (state) {
    is LocalGitSettingsState.Loaded -> state.snapshot
    is LocalGitSettingsState.Loading -> state.previous
    LocalGitSettingsState.Idle, is LocalGitSettingsState.Failed -> null
}

internal fun formatLocalGitSettings(snapshot: LocalGitEnvironmentSnapshot): String = buildString {
    appendLine("Git 可执行文件：${snapshot.gitExecutable ?: "未读取到"}")
    appendLine("Git 版本：${snapshot.gitVersion ?: "未读取到"}")
    appendLine("系统用户：${snapshot.systemUser.ifBlank { "未读取到" }}")
    appendLine("全局 user.name：${snapshot.globalUserName?.value ?: "未配置"}${snapshot.globalUserName?.origin?.let { "  [$it]" }.orEmpty()}")
    appendLine("全局 user.email：${snapshot.globalUserEmail?.value ?: "未配置"}${snapshot.globalUserEmail?.origin?.let { "  [$it]" }.orEmpty()}")
    appendLine("全局 credential.helper：${snapshot.globalCredentialHelpers.joinToString { it.value }.ifBlank { "未配置" }}")
    if (snapshot.globalKeyConfig.isNotEmpty()) {
        appendLine("全局关键配置：")
        snapshot.globalKeyConfig.forEach { appendLine("  ${it.key}=${it.value}${it.origin?.let { origin -> "  [$origin]" }.orEmpty()}") }
    }
    snapshot.errors.forEach { appendLine("全局读取错误：$it") }
}.trimEnd()

@Composable
private fun SettingsCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(10.dp)) {
                    Icon(
                        settingsCardIcon(title),
                        null,
                        Modifier.padding(9.dp).size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(11.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
        }
    }
}

private fun settingsCardIcon(title: String): ImageVector = when (title) {
    "首次使用" -> Icons.Outlined.AccountTree
    "环境状态" -> Icons.Outlined.Build
    "外观" -> Icons.Outlined.Palette
    "任务路径设置" -> Icons.Outlined.Folder
    "需求资料目录设置" -> Icons.Outlined.Folder
    "系统主配置文件" -> Icons.Outlined.Description
    "服务与仓库" -> Icons.Outlined.Group
    "全局与组说明" -> Icons.AutoMirrored.Outlined.Article
    "任务说明模板" -> Icons.Outlined.Edit
    "开发工具" -> Icons.Outlined.Build
    "AWM CLI" -> Icons.Outlined.Terminal
    "分支" -> Icons.Outlined.AccountTree
    "Git 环境" -> Icons.Outlined.Terminal
    "分支写保护" -> Icons.Outlined.Lock
    "Genbu" -> Icons.Outlined.Sell
    "Meegle" -> Icons.Outlined.Link
    "诊断与日志" -> Icons.AutoMirrored.Outlined.Subject
    else -> Icons.Outlined.Description
}

internal data class PathFieldModifierTargets(
    val row: Modifier,
    val textField: Modifier,
)

internal fun pathFieldModifierTargets(modifier: Modifier): PathFieldModifierTargets =
    PathFieldModifierTargets(row = Modifier, textField = modifier)

@Composable
private fun PathField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    chooseEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    onChoose: () -> Unit,
) {
    val modifierTargets = pathFieldModifierTargets(modifier)
    Row(modifierTargets.row, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value, onValueChange, modifierTargets.textField.weight(1f), label = { Text(label) }, singleLine = true, enabled = chooseEnabled)
        OutlinedButton(onClick = onChoose, enabled = chooseEnabled) { Icon(Icons.Outlined.Folder, null); Text("选择") }
    }
}
