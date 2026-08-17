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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.snowball.awm.core.ApplicationEventClipboard
import com.snowball.awm.core.ConfigStore
import com.snowball.awm.core.DevelopmentToolConfig
import com.snowball.awm.core.DevelopmentToolType
import com.snowball.awm.core.GroupConfig
import com.snowball.awm.core.LocalGitEnvironmentSnapshot
import com.snowball.awm.core.MeegleProjectConfig
import com.snowball.awm.core.ThemePreference
import java.nio.file.Files
import java.nio.file.Path

@Composable
internal fun SettingsScreen(controller: DesktopApplication) {
    var taskRoot by remember(controller.config.taskRoot) { mutableStateOf(controller.config.taskRoot.orEmpty()) }
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
    var agentGroupId by remember(controller.config.groups) { mutableStateOf(controller.config.groups.first().id) }
    var agentScope by remember { mutableStateOf("global") }
    var globalAgents by remember(controller.agentRevision) { mutableStateOf(controller.readGlobalAgents()) }
    val groupAgentDrafts = remember(controller.config.groups, controller.agentRevision) {
        mutableStateMapOf<String, String>().apply {
            controller.config.groups.forEach { group -> put(group.id, controller.readGroupAgents(group.id)) }
        }
    }
    val sections = listOf(
        "basic" to "任务路径",
        "groups" to "任务组",
        "agents" to "Agent 说明",
        "tools" to "开发工具",
        "branches" to "分支",
        "git" to "Git",
        "feishu" to "飞书项目",
        "logs" to "日志",
    )
    val initialSection = remember { WindowPreferences.load().settingsSection }
    var selectedSection by remember { mutableStateOf(normalizeSettingsSection(initialSection, sections.map(Pair<String, String>::first).toSet())) }
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
        if (selectedSection == "feishu") controller.refreshMeegleStatus()
        if (selectedSection == "git") controller.refreshLocalGit()
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
                LazyColumn(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(sections, key = { it.first }) { (key, label) ->
                        Surface(
                            Modifier.fillMaxWidth().clickable {
                                selectedSection = key
                                WindowPreferences.saveSettingsSection(key)
                            },
                            color = if (selectedSection == key) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text(
                                label,
                                Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                fontWeight = if (selectedSection == key) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selectedSection == key) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            )
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
                    OutlinedCard(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.outlinedCardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("配置加载失败", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold)
                            Text(
                                "未使用默认配置覆盖磁盘文件。请修复或删除 config.json 后重新打开应用。$error",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            if (selectedSection == "basic") item {
                SettingsBasicSection(
                    controller = controller,
                    taskRoot = taskRoot,
                    onTaskRootChange = { taskRoot = it },
                    saving = saving("basic"),
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
            if (selectedSection == "branches") item {
                SettingsBranchesSection(
                    controller = controller,
                    hiddenBranchInput = hiddenBranchInput,
                    onHiddenBranchInputChange = { hiddenBranchInput = it },
                    hiddenTaskDetailBranches = hiddenTaskDetailBranches,
                    saving = saving("branches"),
                    onSaveHiddenBranches = ::saveHiddenBranches,
                )
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
            onDismiss = { deleteGroupTarget = null },
            onConfirm = { controller.deleteGroup(group.id) { deleteGroupTarget = null } },
        )
    }
    restoreBackup?.let { backup ->
        ConfirmDialog(
            title = "恢复配置备份？",
            message = "将先备份当前配置，再恢复 ${backup.path.fileName}。任务目录不会被删除。",
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
            onDismiss = { importPreview = null },
            onConfirm = { if (controller.importConfig(preview.source.toString())) importPreview = null },
        )
    }
}

internal fun normalizeSettingsSection(stored: String, supported: Set<String>): String = when (stored) {
    "advanced" -> "feishu"
    in supported -> stored
    else -> "basic"
}

@Composable
private fun SettingsBasicSection(
    controller: DesktopApplication,
    taskRoot: String,
    onTaskRootChange: (String) -> Unit,
    saving: Boolean,
    backupMenuExpanded: Boolean,
    onBackupMenuExpandedChange: (Boolean) -> Unit,
    onRestoreBackup: (ConfigStore.Backup) -> Unit,
    onImportPreview: (ConfigStore.ImportPreview) -> Unit,
) {
    SettingsCard("任务路径", "启动只读取这些本地配置，不扫描仓库。") {
        AutoSaveStatus(controller, "basic")
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("界面主题", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(14.dp))
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemePreference.entries.forEach { theme -> FilterChip(controller.config.theme == theme, { controller.setTheme(theme) }, label = { Text(theme.displayName) }, enabled = !controller.busy && !saving) }
            }
        }
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

@Composable
private fun SettingsGroupsSection(
    controller: DesktopApplication,
    onNewGroup: () -> Unit,
    onRenameGroup: (GroupConfig) -> Unit,
    onDeleteGroup: (GroupConfig) -> Unit,
) {
    SettingsCard("任务组", "任务组和组内服务均按数组顺序展示；只能删除没有服务和任务的空组。") {
        AutoSaveStatus(controller, "groups")
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
    SettingsCard("Agent 说明", "磁盘中的全局/组 AGENTS.md 是唯一准确来源，保存后会同步相关任务。") {
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
            if (isGlobal) "对所有任务生效；也可直接编辑 ~/.AgentWorkspaceManager/agents/global/AGENTS.md，程序会自动同步。"
            else "仅对当前组生效；也可直接编辑 ~/.AgentWorkspaceManager/agents/groups/<groupId>/AGENTS.md，程序会自动同步。",
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
    SettingsCard("开发工具", "工具路径完全由用户选择；未配置的工具不会出现在临时打开列表中。") {
        AutoSaveStatus(controller, "tools")
        DevelopmentToolType.entries.forEach { type ->
            val value = developmentToolPaths[type].orEmpty()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value,
                    { developmentToolPaths[type] = it },
                    Modifier.weight(1f).onFocusChanged { focus ->
                        val configured = controller.config.developmentTools.firstOrNull { it.type == type }?.path.orEmpty()
                        if (!focus.isFocused && developmentToolPaths[type].orEmpty().trim() != configured) onSaveDevelopmentTools()
                    },
                    label = { Text(type.displayName) },
                    singleLine = true,
                    readOnly = controller.busy || saving,
                )
                OutlinedButton(
                    onClick = { controller.chooseApplication(value) { developmentToolPaths[type] = it; onSaveDevelopmentTools() } },
                    enabled = !controller.pathPickerBusy && !controller.busy && !saving,
                ) { Icon(Icons.Outlined.Folder, null); Text("选择") }
                OutlinedButton(
                    onClick = { controller.testDevelopmentTool(type, value) },
                    enabled = value.isNotBlank() && !controller.busy,
                ) { Text("测试打开") }
            }
            if (value.isNotBlank() && !runCatching { Files.exists(Path.of(value)) }.getOrDefault(false)) {
                Text("路径在当前电脑无效，请重新选择", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
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
        PathField(
            "终端", terminal, onTerminalChange, !controller.pathPickerBusy && !controller.busy && !saving,
            Modifier.onFocusChanged { focus ->
                if (!focus.isFocused && terminal.trim() != controller.config.terminalExecutable.orEmpty()) onSaveDevelopmentTools()
            },
        ) { controller.chooseFile(terminal) { onTerminalChange(it); onSaveDevelopmentTools() } }
    }
}

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
    SettingsCard("Git", "只读取本机 Git 可执行文件、版本、系统用户和全局配置。") {
        val gitState = controller.localGitSettingsState
        Text("分支写保护", style = MaterialTheme.typography.titleSmall)
        Text("在以下实际当前分支上禁用 Commit、Push、Commit & Push，以及需要写入分支的 Tag 流程。按完整名称忽略大小写匹配。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { controller.refreshLocalGit(force = true) }, enabled = gitState !is LocalGitSettingsState.Loading) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text("刷新")
            }
            when (gitState) {
                is LocalGitSettingsState.Failed -> {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { controller.copyText(gitState.message, "Git 错误已复制") }) {
                        Icon(Icons.Outlined.ContentCopy, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("复制错误")
                    }
                }
                is LocalGitSettingsState.Loaded -> {
                    val content = formatLocalGitSettings(gitState.snapshot)
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { controller.copyText(content, "Git 信息已复制") }) {
                        Icon(Icons.Outlined.ContentCopy, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("复制全部")
                    }
                }
                LocalGitSettingsState.Idle, LocalGitSettingsState.Loading -> Unit
            }
        }
        when (val state = gitState) {
            LocalGitSettingsState.Idle, LocalGitSettingsState.Loading -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在读取本地 Git 信息…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is LocalGitSettingsState.Failed -> {
                SelectionContainer { Text(state.message, color = MaterialTheme.colorScheme.error) }
            }
            is LocalGitSettingsState.Loaded -> {
                val content = formatLocalGitSettings(state.snapshot)
                Surface(
                    Modifier.fillMaxWidth().heightIn(min = 280.dp, max = 460.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    SelectionContainer {
                        Text(
                            content,
                            Modifier.fillMaxSize().padding(14.dp)
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState()),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
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
    SettingsCard("飞书项目", "管理创建任务时用于读取需求的 Meegle 项目。") {
        AutoSaveStatus(controller, "feishu")
        MeegleCliStatusPanel(controller)
        Text("飞书需求项目", style = MaterialTheme.typography.titleSmall)
        Text(
            "点击添加后从本机 Meegle CLI 读取项目；创建任务时会拉取已配置项目中的飞书需求链接。",
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
                    ActionIconButton("删除飞书项目配置", { meegleProjects.remove(index); onSaveMeegleProjects() }, enabled = !controller.busy && !saving) {
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
    SettingsCard("日志", "查看应用最近错误并打开日志目录。") {
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
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Meegle CLI", fontWeight = FontWeight.SemiBold)
                when (val state = controller.meegleCliState) {
                    MeegleCliState.Idle, MeegleCliState.Loading -> Text(
                        "正在检查版本和登录状态…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    is MeegleCliState.Failed -> SelectionContainer {
                        Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    is MeegleCliState.Ready -> {
                        val status = state.status
                        if (!status.installed) {
                            Text("未安装或无法启动 Meegle CLI", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        } else {
                            Text(
                                "版本 ${status.version.orEmpty()} · ${if (status.authenticated) "已登录" else if (status.host != null) "登录已过期" else "未登录"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (status.authenticated) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                            Text(
                                buildString {
                                    append("站点：${status.host ?: "project.feishu.cn"}")
                                    status.expiresInMinutes?.let { append(" · 凭据剩余 $it 分钟") }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (status.authenticated && (status.expiresInMinutes ?: Long.MAX_VALUE) <= 5) {
                                Text("登录凭据即将过期，建议刷新或重新登录", style = MaterialTheme.typography.labelSmall, color = WarningAmber)
                            }
                        }
                    }
                }
            }
            TextButton(onClick = { controller.refreshMeegleStatus(force = true) }, enabled = !controller.busy) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(17.dp)); Spacer(Modifier.width(4.dp)); Text("刷新")
            }
            val status = (controller.meegleCliState as? MeegleCliState.Ready)?.status
            if (status?.installed == true && !status.authenticated) {
                Button(onClick = controller::loginMeegle, enabled = !controller.meegleBusy) { Text("登录飞书项目") }
            }
            if (controller.meegleBusy && controller.meegleOperationCancellable) {
                TextButton(onClick = { controller.cancelMeegleOperation() }) { Text("取消登录") }
            }
        }
        controller.meegleOperationError?.let { error ->
            SelectionContainer {
                Text(error, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
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
                Text("Tag")
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
                    Text(title.take(1), Modifier.padding(horizontal = 11.dp, vertical = 7.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
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

@Composable
private fun PathField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    chooseEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    onChoose: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value, onValueChange, modifier.weight(1f), label = { Text(label) }, singleLine = true, enabled = chooseEnabled)
        OutlinedButton(onClick = onChoose, enabled = chooseEnabled) { Icon(Icons.Outlined.Folder, null); Text("选择") }
    }
}
