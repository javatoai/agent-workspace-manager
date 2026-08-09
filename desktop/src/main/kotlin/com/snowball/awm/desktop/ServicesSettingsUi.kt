package com.snowball.awm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.snowball.awm.core.AgentConflictResolution
import com.snowball.awm.core.BootstrapConfig
import com.snowball.awm.core.BootstrapPresets
import com.snowball.awm.core.GroupServiceConfig
import com.snowball.awm.core.BranchPrefixResolver
import com.snowball.awm.core.IdeType
import com.snowball.awm.core.IndependentCloneModuleConfig
import com.snowball.awm.core.RepositoryConfig
import com.snowball.awm.core.RemoteBranchSearch
import com.snowball.awm.core.RemoteBranchRef
import com.snowball.awm.core.GroupConfig
import com.snowball.awm.core.MeegleProjectConfig
import com.snowball.awm.core.ServiceModuleConfig
import com.snowball.awm.core.ServiceWorkspace
import com.snowball.awm.core.TaskManifest
import com.snowball.awm.core.RequirementMetadata
import com.snowball.awm.core.TaskNaming
import com.snowball.awm.core.TagOutputFormatter
import com.snowball.awm.core.RequirementDraftState
import com.snowball.awm.core.WorkspaceToolLaunchStatus
import com.snowball.awm.core.ThemePreference
import com.snowball.awm.core.TaskLifecycleStatus
import com.snowball.awm.core.WorkspaceHealth
import com.snowball.awm.core.health
import com.snowball.awm.core.WorkspaceStrategy
import com.snowball.awm.core.WorkspaceGitHealthState
import com.snowball.awm.core.LocalPushState
import com.snowball.awm.core.isHttpUrl
import com.snowball.awm.core.selectionKey
import com.snowball.awm.desktop.generated.resources.Res
import com.snowball.awm.desktop.generated.resources.app_icon
import io.github.vinceglb.filekit.FileKit
import org.jetbrains.compose.resources.painterResource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.launch
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.UUID


@Composable
internal fun ServicesScreen(controller: DesktopApplication) {
    var editTarget by remember { mutableStateOf<Pair<String, GroupServiceConfig>?>(null) }
    var removeTarget by remember { mutableStateOf<Pair<String, GroupServiceConfig>?>(null) }
    var addToGroup by remember { mutableStateOf<String?>(null) }
    var selectedGroupId by remember { mutableStateOf(controller.config.groups.firstOrNull()?.id) }
    LaunchedEffect(controller.config.groups.map(GroupConfig::id)) {
        if (selectedGroupId !in controller.config.groups.map(GroupConfig::id)) {
            selectedGroupId = controller.config.groups.firstOrNull()?.id
        }
    }
    val group = controller.config.groups.firstOrNull { it.id == selectedGroupId } ?: return
    val serviceCount = group.services.size
    val standardCount = group.services.count { it.strategy == WorkspaceStrategy.STANDARD_WORKTREE }
    LazyColumn(
        Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "service-overview") {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                controller.config.groups.forEach { candidate ->
                    FilterChip(
                        selected = candidate.id == group.id,
                        onClick = { selectedGroupId = candidate.id },
                        label = { Text("${candidate.name} · ${candidate.services.size}") },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("当前组", group.name, "服务仓库", Modifier.weight(1f))
                MetricCard("服务", serviceCount.toString(), "已配置仓库入口", Modifier.weight(1f))
                MetricCard("Worktree", standardCount.toString(), "标准隔离工作区", Modifier.weight(1f))
                MetricCard("独立克隆", (serviceCount - standardCount).toString(), "固定分支工作区", Modifier.weight(1f))
            }
        }
        item(key = "group-${group.id}") {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.36f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), shape = RoundedCornerShape(11.dp)) {
                        Icon(Icons.Outlined.Dns, null, Modifier.padding(9.dp).size(19.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(group.name, style = MaterialTheme.typography.titleMedium)
                        Text("${group.services.size} 个服务 · UAT Tag ${if (group.uatTagEnabled) "已开启" else "已关闭"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = { addToGroup = group.id }) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("添加仓库") }
                }
            }
        }
        if (group.services.isEmpty()) item(key = "empty-${group.id}") {
                OutlinedCard(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(9.dp))
                        Text("该组还没有服务，添加一个 Git 仓库开始配置。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
        }
        items(group.services, key = { "${group.id}-${it.id}" }) { service ->
            val index = group.services.indexOfFirst { it.id == service.id }
            val repository = controller.config.repositories.firstOrNull { it.id == service.repositoryId }
            ServiceCard(service, repository, index > 0, index in 0 until group.services.lastIndex,
                onEdit = { editTarget = group.id to service },
                onUp = { controller.moveService(group.id, service.id, -1) },
                onDown = { controller.moveService(group.id, service.id, 1) },
                onRemove = { removeTarget = group.id to service })
        }
    }
    addToGroup?.let { groupId -> AddRepositoryDialog(controller, onDismiss = { addToGroup = null }) { paths ->
        controller.settingsController.addRepositories(groupId, paths) { addToGroup = null }
    } }
    editTarget?.let { (groupId, service) -> ServiceEditorDialog(controller, service, onDismiss = { editTarget = null }) {
        controller.settingsController.updateService(groupId, it) { editTarget = null }
    } }
    removeTarget?.let { (groupId, service) ->
        ConfirmDialog(
            title = "从组中移除服务？",
            message = "将移除“${service.displayName}”在当前组中的配置，不会删除原仓库，也不会改动已有任务。仍被任务引用时操作会被安全阻止。",
            onDismiss = { removeTarget = null },
            onConfirm = { controller.removeService(groupId, service.id) { removeTarget = null } },
        )
    }
}

@Composable
private fun ServiceCard(
    service: GroupServiceConfig,
    repository: RepositoryConfig?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEdit: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    ElevatedCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.padding(horizontal = 17.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                Icon(
                    if (service.strategy == WorkspaceStrategy.STANDARD_WORKTREE) Icons.Outlined.AccountTree else Icons.Outlined.ContentCopy,
                    null,
                    Modifier.padding(10.dp).size(21.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(service.displayName, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    MetaPill(service.strategy.displayName)
                    if (!service.enabled) { Spacer(Modifier.width(6.dp)); MetaPill("已停用") }
                }
                Spacer(Modifier.height(3.dp))
                Text(repository?.rootPath ?: "仓库配置缺失", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (service.strategy == WorkspaceStrategy.STANDARD_WORKTREE) "${service.modules.size} 个基础分支模块" else "${service.cloneModules.size} 个固定分支模块", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ActionIconButton("上移服务", onUp, enabled = canMoveUp) { Icon(Icons.Outlined.KeyboardArrowUp, "上移") }
            ActionIconButton("下移服务", onDown, enabled = canMoveDown) { Icon(Icons.Outlined.KeyboardArrowDown, "下移") }
            OutlinedButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("配置") }
            ActionIconButton("从当前组移除服务", onRemove) { Icon(Icons.Outlined.Delete, "移除", tint = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
internal fun UatScreen(controller: DesktopApplication) {
    LazyColumn(
        Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        item {
            SectionHeader("UAT 构建历史", "构建操作请在研发任务的工作区行中执行；这里仅保留结果记录")
        }
        if (controller.tagHistory.isEmpty()) {
            item { EmptyState("还没有 UAT 构建历史", "进入研发任务，在对应工作区点击“UAT Tag”。", "前往研发任务") { controller.navigation = NavigationItem.TASKS } }
        } else {
            items(controller.tagHistory, key = { it.operationId }) { operation ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(11.dp)) {
                            Icon(Icons.Outlined.Sell, null, Modifier.padding(9.dp).size(19.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("${operation.serviceName} · ${operation.tag ?: "尚未生成 Tag"}", style = MaterialTheme.typography.titleSmall)
                            Text("${operation.folderName} · ${operation.featureBranch} → ${operation.remote}/${operation.testBranch}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            operation.message?.takeIf(String::isNotBlank)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(operation.updatedAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StatusPill(operation.state.name)
                        ActionIconButton("复制 UAT 构建记录", onClick = {
                            val copy = buildString {
                                append("服务名："); append(operation.serviceName); append('\n')
                                operation.tag?.let { append("Tag："); append(it) } ?: run {
                                    append("状态："); append(operation.state.name)
                                    operation.message?.takeIf(String::isNotBlank)?.let { append('\n'); append("说明："); append(it) }
                                }
                            }
                            controller.copyText(copy, "构建记录已复制")
                        }) { Icon(Icons.Outlined.ContentCopy, "复制构建记录") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsScreen(controller: DesktopApplication) {
    var taskRoot by remember(controller.config.taskRoot) { mutableStateOf(controller.config.taskRoot.orEmpty()) }
    var idea by remember(controller.config.ideaExecutable) { mutableStateOf(controller.config.ideaExecutable.orEmpty()) }
    var webStorm by remember(controller.config.webStormExecutable) { mutableStateOf(controller.config.webStormExecutable.orEmpty()) }
    var terminal by remember(controller.config.terminalExecutable) { mutableStateOf(controller.config.terminalExecutable.orEmpty()) }
    val meegleProjects = remember(controller.config.meegleProjects) {
        mutableStateMapOf<Int, MeegleProjectConfig>().apply {
            controller.config.meegleProjects.forEachIndexed { index, project -> put(index, project) }
        }
    }
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
        "basic" to "基础设置",
        "groups" to "组",
        "agents" to "Agent 说明",
        "tools" to "开发工具",
        "advanced" to "高级设置",
    )
    val initialSection = remember { WindowPreferences.load().settingsSection }
    var selectedSection by remember { mutableStateOf(initialSection.takeIf { key -> sections.any { it.first == key } } ?: "basic") }
    val listState = rememberLazyListState()
    val uiScope = rememberCoroutineScope()
    val itemOffset = if (controller.configurationLoadError == null) 0 else 1
    LaunchedEffect(initialSection, itemOffset) {
        val index = sections.indexOfFirst { it.first == selectedSection }.coerceAtLeast(0)
        listState.scrollToItem(index + itemOffset)
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.widthIn(max = 1080.dp).fillMaxHeight().align(Alignment.TopCenter)
                .padding(start = 28.dp, end = 28.dp, bottom = 28.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                sections.forEachIndexed { index, (key, label) ->
                    FilterChip(
                        selected = selectedSection == key,
                        onClick = {
                            selectedSection = key
                            WindowPreferences.saveSettingsSection(key)
                            uiScope.launch { listState.animateScrollToItem(index + itemOffset) }
                        },
                        label = { Text(label) },
                    )
                }
            }
            LazyColumn(
                Modifier.weight(1f),
                state = listState,
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
            item {
            SettingsCard("基础设置", "启动只读取这些本地配置，不扫描仓库。") {
                PathField("任务根目录", taskRoot, { taskRoot = it }, !controller.pathPickerBusy) {
                    controller.chooseDirectory(taskRoot) { taskRoot = it }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("界面主题", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.width(14.dp))
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemePreference.entries.forEach { theme -> FilterChip(controller.config.theme == theme, { controller.setTheme(theme) }, label = { Text(theme.displayName) }) }
                    }
                    Button(onClick = { controller.updateTaskRoot(taskRoot) }, enabled = taskRoot.isNotBlank()) { Text("保存目录") }
                }
            }
            }
            item {
            SettingsCard("组", "组和组内服务均按数组顺序展示；只能删除没有服务和任务的空组。") {
                controller.config.groups.forEachIndexed { index, group ->
                    GroupSettingsRow(
                        controller = controller,
                        group = group,
                        index = index,
                        groupCount = controller.config.groups.size,
                        onRename = { renameGroup = group },
                        onDelete = { deleteGroupTarget = group },
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = { newGroup = true }) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("创建组") }
                }
            }
            }
            item {
            SettingsCard("Agent 说明", "磁盘中的全局/组 AGENTS.md 是唯一准确来源，保存后会同步相关任务。") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(agentScope == "global", { agentScope = "global" }, label = { Text("全局") })
                    controller.config.groups.forEach { group ->
                        FilterChip(agentScope == group.id, {
                            agentScope = group.id
                            agentGroupId = group.id
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
                        globalAgents = it
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
            item {
            SettingsCard("开发工具", "留空时不会尝试启动对应工具。") {
                PathField("IntelliJ IDEA", idea, { idea = it }, !controller.pathPickerBusy) { controller.chooseFile(idea) { idea = it } }
                PathField("WebStorm", webStorm, { webStorm = it }, !controller.pathPickerBusy) { controller.chooseFile(webStorm) { webStorm = it } }
                PathField("终端", terminal, { terminal = it }, !controller.pathPickerBusy) { controller.chooseFile(terminal) { terminal = it } }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = { controller.updateExecutables(idea, webStorm, terminal) }) { Text("保存工具配置") }
                }
            }
            }
            item {
            SettingsCard("高级设置", "用于放置不影响日常任务管理的扩展配置。") {
                Text("飞书需求项目", style = MaterialTheme.typography.titleSmall)
                Text(
                    "保存 project_key 和 simple_name。创建任务时会自动拉取一次已配置项目中的飞书需求链接。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                meegleProjects.toSortedMap().forEach { (index, project) ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            project.projectKey,
                            { value -> meegleProjects[index] = project.copy(projectKey = value) },
                            Modifier.weight(1f),
                            label = { Text("project_key") },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            project.simpleName,
                            { value -> meegleProjects[index] = project.copy(simpleName = value) },
                            Modifier.weight(1f),
                            label = { Text("simple_name") },
                            singleLine = true,
                        )
                        ActionIconButton("删除飞书项目配置", { meegleProjects.remove(index) }) {
                            Icon(Icons.Outlined.Delete, "删除项目")
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        val index = (meegleProjects.keys.maxOrNull() ?: -1) + 1
                        meegleProjects[index] = MeegleProjectConfig("project_key", "simple_name")
                    },
                ) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("添加项目")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = { controller.updateMeegleProjects(meegleProjects.toSortedMap().values.toList()) }) {
                        Text("保存飞书需求配置")
                    }
                }
            }
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
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (groupCount == 1) "单组模式" else group.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (groupCount == 1) "任务和服务界面隐藏组层级 · ${group.services.size} 个服务" else "${group.services.size} 个服务",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text("Tag")
                Spacer(Modifier.width(8.dp))
                Switch(group.uatTagEnabled, { controller.setGroupTagEnabled(group.id, it) })
                if (groupCount > 1) {
                    ActionIconButton("上移组", { controller.moveGroup(group.id, -1) }, enabled = index > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "上移") }
                    ActionIconButton("下移组", { controller.moveGroup(group.id, 1) }, enabled = index < groupCount - 1) { Icon(Icons.Outlined.KeyboardArrowDown, "下移") }
                    ActionIconButton("重命名组", onRename) { Icon(Icons.Outlined.Edit, "重命名") }
                    ActionIconButton(
                        label = "删除空组",
                        onClick = onDelete,
                        enabled = group.services.isEmpty(),
                    ) { Icon(Icons.Outlined.Delete, "删除") }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            OutlinedTextField(
                branchPrefix,
                { branchPrefix = it },
                Modifier.fillMaxWidth(),
                label = { Text("默认分支名前缀") },
                placeholder = { Text("例如 feature/zhangsan_{num}_") },
                supportingText = { Text("{num} 会从需求链接或文本的最后一段数字解析；创建页仍可继续修改。") },
                singleLine = true,
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
                            },
                            enabled = tool.available,
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = { controller.updateGroupDefaults(group.id, branchPrefix, selectedToolIds.toList()) },
                    enabled = !controller.busy,
                ) { Text("保存组默认设置") }
            }
        }
    }
}

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
    onChoose: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value, onValueChange, Modifier.weight(1f), label = { Text(label) }, singleLine = true)
        OutlinedButton(onClick = onChoose, enabled = chooseEnabled) { Icon(Icons.Outlined.Folder, null); Text("选择") }
    }
}
