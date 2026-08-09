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
import com.snowball.awm.core.TaskNaming
import com.snowball.awm.core.TagOutputFormatter
import com.snowball.awm.core.RequirementDraftState
import com.snowball.awm.core.WorkspaceToolLaunchStatus
import com.snowball.awm.core.ThemePreference
import com.snowball.awm.core.WorkspaceStatus
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
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.UUID

fun main() {
    FileKit.init(appId = "com.snowball.awm")
    application {
        val controller = remember { DesktopApplication() }
        val state = rememberWindowState(width = 1600.dp, height = 980.dp)
        Window(
            onCloseRequest = {
                if (controller.busy) {
                    controller.showError(IllegalStateException("操作正在执行，完成前不能关闭应用"))
                } else {
                    controller.close()
                    exitApplication()
                }
            },
            title = "Agent Workspace Manager 0.4.2",
            state = state,
            icon = painterResource(Res.drawable.app_icon),
        ) {
            DisposableEffect(window) {
                // This is a desktop-first, two-pane workspace.  Keeping a minimum width
                // preserves readable service metadata and one-line operation bars instead
                // of letting controls collapse into an unusable mobile-like layout.
                window.minimumSize = Dimension(1580, 800)
                val listener = object : WindowAdapter() {
                    override fun windowGainedFocus(event: WindowEvent?) = controller.agentInstructionsController.onWindowFocused()
                }
                window.addWindowFocusListener(listener)
                onDispose { window.removeWindowFocusListener(listener) }
            }
            AwmTheme(controller.config.theme) { AgentWorkspaceApp(controller) }
        }
    }
}

@Composable
private fun AgentWorkspaceApp(controller: DesktopApplication) {
    val snackbar = remember { SnackbarHostState() }
    var showCreate by remember { mutableStateOf(false) }

    LaunchedEffect(controller.statusMessage, controller.errorMessage) {
        val error = controller.errorMessage
        val message = error ?: controller.statusMessage
        if (message != null) {
            snackbar.showSnackbar(
                message,
                withDismissAction = true,
                duration = if (error == null) SnackbarDuration.Short else SnackbarDuration.Long,
            )
            controller.dismissMessages()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxSize()) {
                Sidebar(controller) { controller.navigation = it }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    TopBar(controller, onCreate = { showCreate = true })
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when (controller.navigation) {
                            NavigationItem.TASKS -> TasksScreen(controller, archived = false) { showCreate = true }
                            NavigationItem.ARCHIVED -> TasksScreen(controller, archived = true) { showCreate = true }
                            NavigationItem.SERVICES -> ServicesScreen(controller)
                            NavigationItem.UAT -> UatScreen(controller)
                            NavigationItem.SETTINGS -> SettingsScreen(controller)
                        }
                    }
                }
            }
            if (controller.busy) {
                Surface(
                    Modifier.align(Alignment.TopCenter).widthIn(min = 360.dp, max = 560.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            controller.activeOperation ?: "正在处理",
                            color = MaterialTheme.colorScheme.inverseOnSurface,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateTaskDialog(controller, onDismiss = { showCreate = false }) { name, branch, group, services, link, notes, tools ->
            if (controller.taskController.create(name, branch, group, services, link, notes, tools)) {
                showCreate = false
            }
        }
    }
    controller.tagResult?.let { result ->
        val output = TagOutputFormatter.format(controller.selectedTask?.requirementLink.orEmpty(), listOf(result), includeFailures = true)
        AlertDialog(
            onDismissRequest = controller::clearTagResult,
            title = { Text("UAT 构建结果") },
            text = { Text(output) },
            confirmButton = { Row { OutlinedButton(onClick = { controller.copyText(output, "构建结果已复制") }) { Text("复制") }; Spacer(Modifier.width(8.dp)); Button(onClick = controller::clearTagResult) { Text("完成") } } },
        )
    }
    controller.batchTagResults?.let { results ->
        val successful = results.filter { it.state.name == "SUCCESS" && !it.tag.isNullOrBlank() }
        val successOutput = TagOutputFormatter.format(controller.selectedTask?.requirementLink.orEmpty(), successful, includeFailures = false)
        AlertDialog(
            onDismissRequest = controller::clearBatchTagResults,
            title = { Text("批量 UAT 构建结果") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    results.forEach { result ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(result.serviceName, fontWeight = FontWeight.SemiBold)
                                Text(result.tag ?: result.message.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            StatusPill(result.state.name)
                        }
                    }
                }
            },
            confirmButton = { Row { OutlinedButton(onClick = { controller.copyText(successOutput, "成功 Tag 已复制") }, enabled = successful.isNotEmpty()) { Text(if (successful.isEmpty()) "没有可复制的成功 Tag" else "复制成功 Tag") }; Spacer(Modifier.width(8.dp)); Button(onClick = controller::clearBatchTagResults) { Text("完成") } } },
        )
    }
    controller.repositoryAddResult?.let { result ->
        AlertDialog(
            onDismissRequest = controller::clearRepositoryAddResult,
            title = { Text("仓库添加结果") },
            text = {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("已添加 ${result.added.size} 个服务，跳过 ${result.skipped.size} 个目录。")
                    result.added.forEach { Text("✓ $it", color = SuccessGreen, style = MaterialTheme.typography.bodySmall) }
                    result.skipped.forEach { skipped ->
                        Text(
                            "跳过 ${skipped.path}\n${skipped.reason}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = { Button(onClick = controller::clearRepositoryAddResult) { Text("完成") } },
        )
    }
    controller.agentConflict?.let { conflict ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Agent 文件发生冲突") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("文件已被外部编辑器修改，当前窗口也有未保存内容。请选择要保留的版本。")
                    Text(conflict.path.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { Button(onClick = { controller.agentInstructionsController.resolveConflict(AgentConflictResolution.USE_LOCAL) }) { Text("使用本地编辑") } },
            dismissButton = { OutlinedButton(onClick = { controller.agentInstructionsController.resolveConflict(AgentConflictResolution.USE_DISK) }) { Text("使用磁盘版本") } },
        )
    }
}

@Composable
private fun Sidebar(controller: DesktopApplication, onSelected: (NavigationItem) -> Unit) {
    Surface(
        Modifier.width(232.dp).fillMaxHeight().border(
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp),
        ),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 18.dp)) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = BrandBlue, shape = RoundedCornerShape(14.dp), shadowElevation = 3.dp) {
                    Icon(Icons.Outlined.AccountTree, null, Modifier.padding(11.dp), tint = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("AWM", style = MaterialTheme.typography.titleLarge)
                    Text("Workspace studio · 0.4.2", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "工作空间",
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NavigationItem.entries.filter { it != NavigationItem.UAT || controller.showsUatNavigation }.forEach { item ->
                val selectedItem = item == controller.navigation
                val icon = when (item) {
                    NavigationItem.TASKS -> Icons.Outlined.Workspaces
                    NavigationItem.ARCHIVED -> Icons.Outlined.Archive
                    NavigationItem.SERVICES -> Icons.Outlined.Dns
                    NavigationItem.UAT -> Icons.Outlined.Sell
                    NavigationItem.SETTINGS -> Icons.Outlined.Settings
                }
                Surface(
                    Modifier.fillMaxWidth().clickable { onSelected(item) },
                    color = if (selectedItem) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    shape = RoundedCornerShape(13.dp),
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (selectedItem) {
                            Surface(Modifier.width(4.dp).height(26.dp), color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(4.dp)) {}
                            Spacer(Modifier.width(8.dp))
                        } else {
                            Spacer(Modifier.width(12.dp))
                        }
                        Icon(icon, null, tint = if (selectedItem) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(11.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.title, fontWeight = if (selectedItem) FontWeight.SemiBold else FontWeight.Normal)
                            Text(item.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        navigationCount(controller, item)?.let { count ->
                            Surface(color = if (selectedItem) MaterialTheme.colorScheme.surface.copy(alpha = 0.75f) else MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(20.dp)) {
                                Text(count.toString(), Modifier.padding(horizontal = 7.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Security, null, Modifier.size(22.dp), tint = SuccessGreen)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("本地安全执行", style = MaterialTheme.typography.labelMedium)
                    Text("不上传源代码", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TopBar(controller: DesktopApplication, onCreate: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().height(84.dp),
        color = MaterialTheme.colorScheme.background,
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(controller.navigation.title, style = MaterialTheme.typography.headlineSmall)
                Text(controller.navigation.pageDescription, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = controller.taskController::refresh, enabled = !controller.busy) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("刷新状态")
            }
            Spacer(Modifier.width(10.dp))
            if (controller.navigation == NavigationItem.TASKS) Button(onClick = onCreate, enabled = !controller.needsTaskRoot) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("创建任务")
            }
        }
    }
}

private fun navigationCount(controller: DesktopApplication, item: NavigationItem): Int? = when (item) {
    NavigationItem.TASKS -> controller.tasks.count { it.status != WorkspaceStatus.ARCHIVED }
    NavigationItem.ARCHIVED -> controller.tasks.count { it.status == WorkspaceStatus.ARCHIVED }
    NavigationItem.SERVICES -> controller.config.groups.sumOf { it.services.size }
    NavigationItem.UAT, NavigationItem.SETTINGS -> null
}

private val NavigationItem.pageDescription: String
    get() = when (this) {
        NavigationItem.TASKS -> "集中查看任务状态、工作区与任务说明"
        NavigationItem.ARCHIVED -> "查看已归档任务并按需恢复"
        NavigationItem.SERVICES -> "按组管理仓库、模块和工作区策略"
        NavigationItem.UAT -> "从已启用的工作区安全构建测试标签"
        NavigationItem.SETTINGS -> "管理本地目录、组、Agent 说明与开发工具"
    }

@Composable
private fun TasksScreen(controller: DesktopApplication, archived: Boolean, onCreate: () -> Unit) {
    if (controller.needsTaskRoot) {
        EmptyState("请先配置任务根目录", "设置完成后即可创建第一个研发任务") {
            controller.navigation = NavigationItem.SETTINGS
        }
        return
    }
    val visibleTasks = controller.tasks.filter { (it.status == WorkspaceStatus.ARCHIVED) == archived }
    if (visibleTasks.isEmpty()) {
        EmptyState(if (archived) "还没有已归档任务" else "还没有研发任务", if (archived) "归档后的任务会保留在这里，可随时恢复。" else "从已配置的服务创建 Worktree 或独立克隆") { onCreate() }
        return
    }
    Row(
        Modifier.fillMaxSize()
            .padding(start = 28.dp, end = 28.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Surface(
            Modifier.width(352.dp).fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("任务列表", style = MaterialTheme.typography.titleMedium)
                        Text("按更新时间排列", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(20.dp)) {
                        Text("${visibleTasks.size} 个", Modifier.padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TaskList(controller, visibleTasks, Modifier.fillMaxSize().padding(10.dp))
            }
        }
        controller.selectedTask?.takeIf { (it.status == WorkspaceStatus.ARCHIVED) == archived }?.let {
            TaskDetail(controller, it, Modifier.weight(1f).widthIn(min = 900.dp).fillMaxHeight())
        }
    }
}

@Composable
private fun TaskList(controller: DesktopApplication, taskItems: List<TaskManifest>, modifier: Modifier) {
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (controller.config.groups.size == 1) {
            items(taskItems, key = { it.folderName }) { TaskCard(controller, it, it == controller.selectedTask, controller::selectTask) }
        } else {
            controller.config.groups.forEach { group ->
                val grouped = taskItems.filter { it.groupId == group.id }
                item(key = "header-${group.id}") {
                    GroupHeader(group.name, grouped.size, expanded[group.id] != false) {
                        expanded[group.id] = expanded[group.id] == false
                    }
                }
                if (expanded[group.id] != false) items(grouped, key = { "${group.id}-${it.folderName}" }) {
                    TaskCard(controller, it, it == controller.selectedTask, controller::selectTask)
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(name: String, count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
            Icon(if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.padding(3.dp).size(16.dp))
        }
        Spacer(Modifier.width(9.dp))
        Text(name, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.weight(1f))
        Text("$count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TaskCard(controller: DesktopApplication, task: TaskManifest, selected: Boolean, onSelect: (TaskManifest) -> Unit) {
    ElevatedCard(
        onClick = { onSelect(task) },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f) else MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (selected) 0.dp else 1.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            if (selected) Surface(Modifier.width(4.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.primary) {}
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(task.folderName, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    StatusPill(task.status.name)
                }
                Text(task.featureBranch, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                controller.requirementStatuses[task.folderName]?.let { RequirementStatusPill(it) }
                Text("${task.services.size} 个工作区", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TaskDetail(controller: DesktopApplication, task: TaskManifest, modifier: Modifier) {
    var notes by remember(task.folderName, task.updatedAt, controller.agentRevision) { mutableStateOf(controller.readTaskNotes(task)) }
    var confirmArchive by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showAddServices by remember(task.folderName) { mutableStateOf(false) }
    var showBatchTag by remember(task.folderName) { mutableStateOf(false) }
    var showBranchInfo by remember(task.folderName) { mutableStateOf(false) }
    val group = controller.config.groups.firstOrNull { it.id == task.groupId }
    val tagWorkspaces = task.services.filter { controller.canBuildTag(task, it) }
    val failedTools = task.workspaceToolLaunches.filter { it.status != WorkspaceToolLaunchStatus.OPENED }
    val failedServiceIds = task.services.filter { it.status == WorkspaceStatus.FAILED }
        .map(ServiceWorkspace::groupServiceId).filter(String::isNotBlank).distinct()
    Surface(
        modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.46f), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(task.folderName, Modifier.widthIn(max = 220.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            task.featureBranch,
                            Modifier.widthIn(max = 220.dp).clickable { controller.copyText(task.featureBranch, "分支已复制") },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        StatusPill(task.status.name)
                        if (controller.config.groups.size > 1) MetaPill(group?.name ?: task.groupId)
                        MetaPill("${task.services.size} 个工作区")
                        Spacer(Modifier.weight(1f))
                        if (task.status == WorkspaceStatus.ARCHIVED) {
                            OutlinedButton(onClick = { controller.restoreTask(task) }) { Icon(Icons.Outlined.Restore, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("恢复") }
                        } else {
                            OutlinedButton(onClick = { confirmArchive = true }) { Icon(Icons.Outlined.Archive, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("归档") }
                        }
                        OutlinedButton(
                            onClick = { confirmDelete = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Icon(Icons.Outlined.Delete, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("删除任务")
                        }
                    }
                    if (task.requirementLink.isNotBlank()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                task.requirementLink,
                                Modifier
                                    .widthIn(max = 460.dp)
                                    .then(if (isHttpUrl(task.requirementLink)) Modifier.clickable {
                                        controller.openUrl(task.requirementLink)
                                    } else Modifier),
                                color = if (isHttpUrl(task.requirementLink)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            // Keep the copy affordance directly beside the link instead of
                            // letting a weighted text field push it to the opposite edge.
                            IconButton(
                                onClick = { controller.copyText(task.requirementLink, "需求链接已复制") },
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(Icons.Outlined.ContentCopy, "复制需求链接", Modifier.size(15.dp))
                            }
                            controller.requirementStatuses[task.folderName]?.let { RequirementStatusPill(it) }
                            controller.requirementParticipants[task.folderName]?.let { participants ->
                                participants.qcOwners.takeIf { it.isNotEmpty() }?.let { MetaPill("测试：${it.joinToString("、") { person -> person.name }}") }
                                participants.productManagers.takeIf { it.isNotEmpty() }?.let { MetaPill("产品：${it.joinToString("、") { person -> person.name }}") }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = { controller.copyText(controller.taskPath(task), "任务路径已复制") }, label = { Text("复制路径") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(17.dp)) })
                AssistChip(onClick = { controller.terminal(controller.taskPath(task)) }, label = { Text("终端") }, leadingIcon = { Icon(Icons.Outlined.Terminal, null, Modifier.size(17.dp)) })
                AssistChip(onClick = { controller.openDirectory(controller.taskPath(task)) }, label = { Text("打开工作目录") }, leadingIcon = { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(17.dp)) })
                if (tagWorkspaces.size > 1) {
                    AssistChip(onClick = { showBatchTag = true }, label = { Text("批量 UAT Tag") }, leadingIcon = { Icon(Icons.Outlined.Sell, null, Modifier.size(17.dp)) })
                }
                if (controller.addableServices(task).isNotEmpty()) {
                    AssistChip(onClick = { showAddServices = true }, label = { Text("添加服务") }, leadingIcon = { Icon(Icons.Outlined.Add, null, Modifier.size(17.dp)) })
                }
                AssistChip(onClick = { showBranchInfo = true }, label = { Text("分支信息") }, leadingIcon = { Icon(Icons.Outlined.AccountTree, null, Modifier.size(17.dp)) })
                AssistChip(onClick = { controller.openWorkData(task) }, label = { Text("打开工作数据", maxLines = 1) }, leadingIcon = { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(17.dp)) })
            }
            SectionHeader("工作区", "每个工作区可独立打开、复制路径或构建 UAT Tag")
            task.services.forEach { WorkspaceCard(controller, task, it) }
            if (failedTools.isNotEmpty()) {
                SectionHeader("工作区工具打开失败", "任务创建不受影响，可单独重试失败工具")
                failedTools.forEach { launch ->
                    val option = controller.workspaceToolOptions(task.groupId).firstOrNull { it.id == launch.toolId }
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(option?.displayName ?: launch.toolId, fontWeight = FontWeight.SemiBold)
                                Text(
                                    when (launch.status) {
                                        WorkspaceToolLaunchStatus.PENDING -> "等待打开"
                                        WorkspaceToolLaunchStatus.OPENED -> "已打开"
                                        WorkspaceToolLaunchStatus.FAILED -> "打开失败 · ${launch.message.orEmpty()}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (launch.status == WorkspaceToolLaunchStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (launch.status == WorkspaceToolLaunchStatus.FAILED) {
                                OutlinedButton(
                                    onClick = { controller.retryWorkspaceTool(task, launch.toolId) },
                                    enabled = !controller.busy && option?.available == true,
                                ) { Text("重试") }
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SectionHeader("任务人工说明", "保存时会更新人工区并按最新配置重新生成 AGENTS.md 系统区")
            OutlinedTextField(notes, {
                notes = it
                controller.markTaskNotesEdited(task, it)
            }, Modifier.fillMaxWidth(), minLines = 4, maxLines = 6, readOnly = controller.busy, label = { Text("任务说明") })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = { controller.saveTaskNotes(task, notes) }, enabled = !controller.busy) {
                    Icon(Icons.Outlined.Save, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("保存")
                }
                if (failedServiceIds.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { controller.retryFailedServices(task) }, enabled = !controller.busy) { Text("重试全部失败服务") }
                }
            }
        }
    }
    if (confirmArchive) ConfirmDialog("归档任务", "任务将移至已归档，工作区和代码不会被删除。", onDismiss = { confirmArchive = false }) {
        if (controller.archiveTask(task)) confirmArchive = false
    }
    if (confirmDelete) DeleteTaskDialog(controller, task) {
        controller.clearDeleteRisk(task)
        confirmDelete = false
    }
    if (showAddServices) AddTaskServicesDialog(controller, task, onDismiss = { showAddServices = false }) { ids ->
        if (controller.addServices(task, ids)) showAddServices = false
    }
    if (showBatchTag) BatchTagDialog(tagWorkspaces, onDismiss = { showBatchTag = false }) { selected ->
        if (controller.deliveryController.buildBatch(task, selected)) showBatchTag = false
    }
    if (showBranchInfo) BranchInfoDialog(controller.branchInfo(task), onDismiss = { showBranchInfo = false }) {
        controller.copyText(controller.branchInfo(task), "分支信息已复制")
    }
}

@Composable
private fun WorkspaceCard(controller: DesktopApplication, task: TaskManifest, workspace: ServiceWorkspace) {
    val health = controller.gitHealth(workspace)
    OutlinedCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(11.dp)) {
                    Icon(
                        if (workspace.strategy == WorkspaceStrategy.STANDARD_WORKTREE) Icons.Outlined.AccountTree else Icons.Outlined.ContentCopy,
                        null,
                        Modifier.padding(9.dp).size(19.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).widthIn(min = 420.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            workspace.moduleName.ifBlank { workspace.serviceName },
                            Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            workspace.strategy.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        StatusPill(workspace.status.name)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            workspace.branch,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        MetaPill(
                            when (health?.state) {
                                null, WorkspaceGitHealthState.CHECKING -> "检查中"
                                WorkspaceGitHealthState.MISSING -> "工作区不存在"
                                WorkspaceGitHealthState.FAILED -> "检查失败"
                                WorkspaceGitHealthState.READY -> if (health.dirtyFileCount == 0) "无未提交" else "${health.dirtyFileCount} 个文件未提交"
                            },
                        )
                        if (health?.state == WorkspaceGitHealthState.READY) {
                            MetaPill(
                                when (health.pushState) {
                                    LocalPushState.PUSHED -> "已推送"
                                    LocalPushState.AHEAD -> "${health.unpushedCommitCount} 个提交未推送"
                                    LocalPushState.REMOTE_BRANCH_MISSING -> "未发现远程分支"
                                    LocalPushState.NO_UPSTREAM -> "未关联远程"
                                    LocalPushState.FAILED -> "检查失败"
                                },
                            )
                        }
                    }
                    if (workspace.warnings.isNotEmpty()) {
                        Text(workspace.warnings.joinToString("\n"), color = WarningAmber, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.width(16.dp))
                // Keep actions in their own vertically-centred group.  This gives the
                // two-line workspace summary a stable rhythm regardless of button size.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Row(Modifier.padding(horizontal = 3.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { controller.terminal(workspace.worktreePath) }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Outlined.Terminal, "终端", Modifier.size(18.dp))
                            }
                            IconButton(onClick = { controller.openDirectory(workspace.worktreePath) }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Outlined.FolderOpen, "打开文件夹", Modifier.size(18.dp))
                            }
                            IconButton(onClick = { controller.copyText(workspace.worktreePath, "工作区路径已复制") }, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Outlined.ContentCopy, "复制路径", Modifier.size(18.dp))
                            }
                        }
                    }
                    if (controller.canBuildTag(task, workspace)) {
                        OutlinedButton(onClick = { controller.deliveryController.build(task, workspace) }, enabled = !controller.busy) {
                            Icon(Icons.Outlined.Sell, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("UAT Tag")
                        }
                    }
                    OutlinedButton(onClick = { controller.openWorkspace(workspace) }) {
                        Icon(Icons.Outlined.Code, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("打开 IDE")
                    }
                    if (workspace.status == WorkspaceStatus.FAILED && workspace.groupServiceId.isNotBlank()) {
                        OutlinedButton(onClick = { controller.retryFailedServices(task, listOf(workspace.groupServiceId)) }, enabled = !controller.busy) {
                            Icon(Icons.Outlined.Refresh, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("重试")
                        }
                    }
                }
        }
    }
}

@Composable
private fun ServicesScreen(controller: DesktopApplication) {
    var editTarget by remember { mutableStateOf<Pair<String, GroupServiceConfig>?>(null) }
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
                onRemove = { controller.removeService(group.id, service.id) })
        }
    }
    addToGroup?.let { groupId -> AddRepositoryDialog(controller, onDismiss = { addToGroup = null }) { paths ->
        if (controller.settingsController.addRepositories(groupId, paths)) addToGroup = null
    } }
    editTarget?.let { (groupId, service) -> ServiceEditorDialog(controller, service, onDismiss = { editTarget = null }) {
        if (controller.settingsController.updateService(groupId, it)) editTarget = null
    } }
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
            IconButton(onClick = onUp, enabled = canMoveUp) { Icon(Icons.Outlined.KeyboardArrowUp, "上移") }
            IconButton(onClick = onDown, enabled = canMoveDown) { Icon(Icons.Outlined.KeyboardArrowDown, "下移") }
            OutlinedButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("配置") }
            IconButton(onClick = onRemove) { Icon(Icons.Outlined.Delete, "移除", tint = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun UatScreen(controller: DesktopApplication) {
    LazyColumn(
        Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        item {
            SectionHeader("UAT 构建历史", "构建操作请在研发任务的工作区行中执行；这里仅保留结果记录")
        }
        if (controller.tagHistory.isEmpty()) {
            item { EmptyState("还没有 UAT 构建历史", "进入研发任务，在对应工作区点击“UAT Tag”。") { controller.navigation = NavigationItem.TASKS } }
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
                        IconButton(onClick = {
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
private fun SettingsScreen(controller: DesktopApplication) {
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
    var agentGroupId by remember(controller.config.groups) { mutableStateOf(controller.config.groups.first().id) }
    var agentScope by remember { mutableStateOf("global") }
    var globalAgents by remember(controller.agentRevision) { mutableStateOf(controller.readGlobalAgents()) }
    val groupAgentDrafts = remember(controller.config.groups, controller.agentRevision) {
        mutableStateMapOf<String, String>().apply {
            controller.config.groups.forEach { group -> put(group.id, controller.readGroupAgents(group.id)) }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.widthIn(max = 1080.dp).fillMaxHeight().align(Alignment.TopCenter).padding(start = 28.dp, end = 28.dp, bottom = 28.dp),
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
                        IconButton(onClick = { controller.copyText(agentPath, "完整路径已复制") }) { Icon(Icons.Outlined.ContentCopy, "复制完整路径") }
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
                        IconButton(onClick = { meegleProjects.remove(index) }) {
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
    if (newGroup) NameDialog("创建组", "", onDismiss = { newGroup = false }) {
        if (controller.addGroup(it)) newGroup = false
    }
    renameGroup?.let { group -> NameDialog("重命名组", group.name, onDismiss = { renameGroup = null }) {
        if (controller.renameGroup(group.id, it)) renameGroup = null
    } }
}

@Composable
private fun GroupSettingsRow(
    controller: DesktopApplication,
    group: GroupConfig,
    index: Int,
    groupCount: Int,
    onRename: () -> Unit,
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
                    IconButton(onClick = { controller.moveGroup(group.id, -1) }, enabled = index > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "上移") }
                    IconButton(onClick = { controller.moveGroup(group.id, 1) }, enabled = index < groupCount - 1) { Icon(Icons.Outlined.KeyboardArrowDown, "下移") }
                    IconButton(onClick = onRename) { Icon(Icons.Outlined.Edit, "重命名") }
                    IconButton(
                        onClick = { runCatching { controller.deleteGroup(group.id) }.onFailure(controller::showError) },
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

@Composable
private fun CreateTaskDialog(
    controller: DesktopApplication,
    onDismiss: () -> Unit,
    onCreate: (String, String, String, List<String>, String, String, List<String>) -> Unit,
) {
    val initialGroup = controller.config.groups.first()
    var draft by remember {
        mutableStateOf(RequirementDraftState(branch = initialGroup.defaultBranchPrefix))
    }
    var notes by remember { mutableStateOf("") }
    var groupId by remember { mutableStateOf(initialGroup.id) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedToolIds by remember { mutableStateOf(initialGroup.defaultWorkspaceToolIds.toSet()) }
    var rightTab by remember { mutableStateOf("notes") }
    var requirementMenuExpanded by remember { mutableStateOf(false) }
    var requirementSearch by remember { mutableStateOf("") }
    var serviceSearch by remember(groupId) { mutableStateOf("") }
    val group = controller.config.groups.first { it.id == groupId }
    val toolOptions = controller.workspaceToolOptions(groupId)
    val taskNameMissing = draft.taskName.isBlank()
    // An untouched create form is incomplete rather than erroneous. Reserve the
    // error treatment for an entered name that cannot become a safe directory.
    val taskNameError = draft.taskName.takeUnless(String::isBlank)
        ?.let(TaskNaming::directoryNameValidationError)
    val preview = remember(draft.taskName, draft.branch, groupId, selected, draft.requirementLink, notes) {
        controller.previewAgents(draft.taskName, draft.branch, groupId, selected, draft.requirementLink, notes)
    }
    LaunchedEffect(draft.requirementLink) {
        val requestedLink = draft.requirementLink
        controller.requestRequirementMetadata(requestedLink) { metadata ->
            draft = draft.applyMetadata(requestedLink, metadata)
        }
    }
    LaunchedEffect(Unit) { controller.loadAutoRequirementLinks() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.90f).widthIn(max = 1540.dp),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.fillMaxSize()) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Outlined.Add, null, Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.onPrimary)
                        }
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text("创建研发任务", style = MaterialTheme.typography.headlineSmall)
                            Text("选择组和服务，并实时确认最终 AGENTS.md", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        MetaPill("已选 ${selected.size} 个服务")
                    }
                }
                Row(Modifier.weight(1f).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Surface(
                        Modifier.weight(1f).fillMaxHeight(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                        shape = RoundedCornerShape(15.dp),
                    ) {
                    Column(Modifier.fillMaxSize().padding(15.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                        SectionHeader("任务信息", "名称和分支将用于创建任务目录与 Git 分支")
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            OutlinedTextField(
                                draft.requirementLink,
                                { draft = draft.changeRequirement(it, group.defaultBranchPrefix) },
                                Modifier.weight(1f),
                                label = { Text("飞书需求链接（可选）") },
                                supportingText = {
                                    when {
                                        draft.requirementTitle != null -> Text(draft.requirementTitle!!)
                                        draft.metadataLoading -> Text("正在读取需求标题…")
                                        draft.metadataHint != null -> Text(draft.metadataHint!!)
                                    }
                                },
                                singleLine = true,
                            )
                            Spacer(Modifier.width(8.dp))
                            Box {
                                OutlinedButton(
                                    onClick = { requirementMenuExpanded = true },
                                    enabled = controller.requirementLinkCandidates.isNotEmpty(),
                                    modifier = Modifier.padding(top = 8.dp),
                                ) { Text("选择需求") }
                                DropdownMenu(
                                    expanded = requirementMenuExpanded,
                                    onDismissRequest = { requirementMenuExpanded = false },
                                    modifier = Modifier.widthIn(min = 520.dp, max = 680.dp),
                                    shape = MaterialTheme.shapes.medium,
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    tonalElevation = 0.dp,
                                    shadowElevation = 4.dp,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                ) {
                                    OutlinedTextField(
                                        requirementSearch,
                                        { requirementSearch = it },
                                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                        label = { Text("搜索标题或链接") },
                                        singleLine = true,
                                    )
                                    val matchingLinks = controller.requirementLinkCandidates.filter {
                                        requirementSearch.isBlank() ||
                                            it.title.contains(requirementSearch, true) ||
                                            it.url.contains(requirementSearch, true)
                                    }
                                    Column(
                                        Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                                    ) {
                                        matchingLinks.forEach { candidate ->
                                            DropdownMenuItem(
                                                text = {
                                                    Column {
                                                        Text(candidate.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                        Text(
                                                            candidate.url,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                },
                                                onClick = {
                                                    draft = draft.changeRequirement(candidate.url, group.defaultBranchPrefix, candidate.title)
                                                    requirementMenuExpanded = false
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (controller.requirementLinksLoading) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("正在拉取飞书需求链接", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        OutlinedTextField(
                            value = draft.taskName,
                            onValueChange = { draft = draft.editName(it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("文件夹名称") },
                            placeholder = { Text("例如：PAY-1024 支付订单优化") },
                            isError = taskNameError != null,
                            supportingText = { taskNameError?.let { Text(it) } },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            draft.branch,
                            { draft = draft.editBranch(it) },
                            Modifier.fillMaxWidth(),
                            label = { Text("任务分支") },
                            placeholder = { Text("例如：feature/PAY-1024") },
                            singleLine = true,
                        )
                        if (controller.config.groups.size > 1) {
                            Spacer(Modifier.height(2.dp))
                            SectionHeader("所属组", "任务创建后归属不可自动迁移")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                controller.config.groups.forEach { candidate -> FilterChip(groupId == candidate.id, {
                                    groupId = candidate.id
                                    selected = emptySet()
                                    selectedToolIds = candidate.defaultWorkspaceToolIds.toSet()
                                    serviceSearch = ""
                                    draft = draft.changeGroup(candidate.defaultBranchPrefix)
                                }, label = { Text(candidate.name) }) }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        SectionHeader("选择服务", "标准服务创建 Worktree，独立克隆直接切换配置分支")
                        OutlinedTextField(
                            value = serviceSearch,
                            onValueChange = { serviceSearch = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("搜索服务") },
                            placeholder = { Text("按服务显示名称搜索") },
                            singleLine = true,
                        )
                        val visibleServices = group.services
                            .filter { it.enabled }
                            .filter { serviceSearch.isBlank() || it.displayName.contains(serviceSearch, ignoreCase = true) }
                        if (visibleServices.isEmpty()) {
                            Text("没有匹配的服务", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        visibleServices.forEach { service ->
                            val checked = service.id in selected
                            OutlinedCard(
                                Modifier.fillMaxWidth().clickable { selected = if (checked) selected - service.id else selected + service.id },
                                colors = CardDefaults.outlinedCardColors(
                                    containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surface,
                                ),
                                border = BorderStroke(1.dp, if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Column(Modifier.padding(11.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked, { selected = if (checked) selected - service.id else selected + service.id })
                                        Column(Modifier.weight(1f)) {
                                            Text(service.displayName, style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                if (service.strategy == WorkspaceStrategy.STANDARD_WORKTREE) {
                                                    if (service.modules.size > 1) "${service.modules.size} 个模块 · ${service.strategy.displayName}" else service.strategy.displayName
                                                } else "${service.cloneModules.size} 个固定分支模块 · ${service.strategy.displayName}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(rightTab == "notes", { rightTab = "notes" }, label = { Text("任务人工说明") })
                            Spacer(Modifier.width(8.dp))
                            FilterChip(rightTab == "preview", { rightTab = "preview" }, label = { Text("AGENTS.md 预览") })
                            Spacer(Modifier.weight(1f))
                            if (rightTab == "preview") MetaPill("实时更新")
                        }
                        if (rightTab == "notes") {
                            OutlinedTextField(
                                notes,
                                { notes = it },
                                Modifier.fillMaxSize(),
                                label = { Text("任务人工说明") },
                                supportingText = { Text("只写入任务级人工区，创建后仍可在任务详情继续编辑。") },
                            )
                        } else {
                            Surface(
                                Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                AgentsMarkdownPreview(preview)
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        toolOptions.forEach { tool ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = tool.id in selectedToolIds,
                                    onCheckedChange = { checked -> selectedToolIds = if (checked) selectedToolIds + tool.id else selectedToolIds - tool.id },
                                    enabled = tool.available,
                                )
                                Text(if (tool.available) tool.displayName else "${tool.displayName}（不可用）", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (selected.isEmpty()) "至少选择一个服务" else "将创建 ${selected.size} 个服务入口",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selected.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onDismiss) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val availableTools = selectedToolIds.filter { id -> toolOptions.firstOrNull { it.id == id }?.available == true }
                                onCreate(draft.taskName, draft.branch, groupId, selected.toList(), draft.requirementLink, notes, availableTools)
                            },
                            enabled = !taskNameMissing && taskNameError == null && draft.branch.isNotBlank() &&
                                !BranchPrefixResolver.containsUnresolvedPlaceholder(draft.branch) &&
                                selected.isNotEmpty() && !controller.busy,
                        ) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("创建任务") }
                    }
                }
            }
        }
    }
}

/** Read-only Material 3 rendering used exclusively for generated AGENTS.md previews. */
@Composable
private fun AgentsMarkdownPreview(content: String) {
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    BoxWithConstraints(Modifier.fillMaxSize().padding(15.dp)) {
        val previewWidth = maxWidth
        Box(Modifier.fillMaxSize().verticalScroll(verticalScroll)) {
            // Scroll containers must not both measure Markdown itself. Keeping Markdown at least
            // as wide as the viewport makes prose start at the left edge; only a wide table or
            // path overflows into the inner horizontal scroll area.
            Box(Modifier.widthIn(min = previewWidth).horizontalScroll(horizontalScroll)) {
                Markdown(
                    content = content,
                    colors = markdownColor(),
                    // Default Markdown h1/h2 styles map to Material display styles, which are
                    // too prominent inside a compact task preview.
                    typography = markdownTypography(
                        h1 = MaterialTheme.typography.titleLarge,
                        h2 = MaterialTheme.typography.titleMedium,
                        h3 = MaterialTheme.typography.titleSmall,
                        h4 = MaterialTheme.typography.labelLarge,
                        h5 = MaterialTheme.typography.labelLarge,
                        h6 = MaterialTheme.typography.labelLarge,
                        text = MaterialTheme.typography.bodyMedium,
                        paragraph = MaterialTheme.typography.bodyMedium,
                        table = MaterialTheme.typography.bodySmall,
                        code = MaterialTheme.typography.bodySmall,
                        inlineCode = MaterialTheme.typography.bodySmall,
                    ),
                    modifier = Modifier.widthIn(min = previewWidth),
                    // Parsing is asynchronous; retaining the last result avoids preview flicker while typing.
                    retainState = true,
                )
            }
        }
    }
}

@Composable
private fun AddTaskServicesDialog(
    controller: DesktopApplication,
    task: TaskManifest,
    onDismiss: () -> Unit,
    onAdd: (List<String>) -> Unit,
) {
    val services = controller.addableServices(task)
    var selected by remember(task.folderName) { mutableStateOf<Set<String>>(emptySet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加服务") },
        text = {
            Column(Modifier.widthIn(min = 560.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("新增服务沿用任务分支：${task.featureBranch}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                services.forEach { service ->
                    val checked = service.id in selected
                    OutlinedCard(
                        Modifier.fillMaxWidth().clickable { selected = if (checked) selected - service.id else selected + service.id },
                        colors = CardDefaults.outlinedCardColors(containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(11.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked, { selected = if (checked) selected - service.id else selected + service.id })
                                Column(Modifier.weight(1f)) {
                                    Text(service.displayName, fontWeight = FontWeight.SemiBold)
                                    Text(service.strategy.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onAdd(selected.toList()) }, enabled = selected.isNotEmpty() && !controller.busy) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun BatchTagDialog(
    workspaces: List<ServiceWorkspace>,
    onDismiss: () -> Unit,
    onBuild: (List<ServiceWorkspace>) -> Unit,
) {
    var selected by remember(workspaces) { mutableStateOf(workspaces.map(ServiceWorkspace::selectionKey).toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量 UAT Tag") },
        text = {
            Column(Modifier.widthIn(min = 520.dp).heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                workspaces.forEach { workspace ->
                    val checked = workspace.selectionKey in selected
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            selected = if (checked) selected - workspace.selectionKey else selected + workspace.selectionKey
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked, { selected = if (checked) selected - workspace.selectionKey else selected + workspace.selectionKey })
                        Column {
                            Text(workspace.moduleName.ifBlank { workspace.serviceName }, fontWeight = FontWeight.SemiBold)
                            Text("${workspace.serviceName} · ${workspace.branch}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onBuild(workspaces.filter { it.selectionKey in selected }) }, enabled = selected.isNotEmpty()) { Text("开始构建") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun BranchInfoDialog(content: String, onDismiss: () -> Unit, onCopy: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分支信息") },
        text = {
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(11.dp)) {
                Text(content.ifBlank { "暂无分支信息" }, Modifier.padding(13.dp), fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = { Button(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("复制") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun AddRepositoryDialog(controller: DesktopApplication, onDismiss: () -> Unit, onAdd: (List<String>) -> Unit) {
    var paths by remember { mutableStateOf<List<String>>(emptyList()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量添加 Git 仓库") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("逐个校验所选目录，不递归扫描子目录；非 Git、Linked Worktree 和重复仓库会被跳过。")
                OutlinedButton(
                    onClick = { controller.chooseDirectories(paths.firstOrNull()) { paths = it } },
                    enabled = !controller.pathPickerBusy,
                ) {
                    Icon(Icons.Outlined.Folder, null)
                    Spacer(Modifier.width(6.dp))
                    Text("选择仓库目录（可多选）")
                }
                if (paths.isEmpty()) {
                    Text("尚未选择目录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
                        Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(10.dp).verticalScroll(rememberScrollState())) {
                            paths.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                Text("新增服务默认采用标准 Worktree；添加后可在服务配置中改为独立克隆。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = { onAdd(paths) }, enabled = paths.isNotEmpty() && !controller.busy) { Text("校验并添加") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ServiceEditorDialog(controller: DesktopApplication, service: GroupServiceConfig, onDismiss: () -> Unit, onSave: (GroupServiceConfig) -> Unit) {
    val json = remember { Json { prettyPrint = true; encodeDefaults = true } }
    var name by remember { mutableStateOf(service.displayName) }
    var enabled by remember { mutableStateOf(service.enabled) }
    var ide by remember { mutableStateOf(service.ideType) }
    var strategy by remember { mutableStateOf(service.strategy) }
    var modules by remember { mutableStateOf(service.modules) }
    var cloneModules by remember { mutableStateOf(service.cloneModules.ifEmpty { listOf(IndependentCloneModuleConfig(id = "clone-default")) }) }
    var bootstrapText by remember { mutableStateOf(json.encodeToString(service.bootstrap)) }
    var bootstrapError by remember { mutableStateOf<String?>(null) }
    var showBootstrapExample by remember { mutableStateOf(false) }
    var bootstrapCopied by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            Modifier.widthIn(min = 780.dp, max = 920.dp).heightIn(min = 620.dp, max = 820.dp),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(11.dp)) {
                        Icon(Icons.Outlined.Settings, null, Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("服务配置", style = MaterialTheme.typography.titleLarge)
                        Text(service.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    MetaPill(strategy.displayName)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    Modifier.weight(1f).padding(20.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                SectionHeader("基础信息", "IDE 是系统建议，可手工修改；保存值始终作为最终依据")
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("展示名称") })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("启用服务", style = MaterialTheme.typography.titleSmall); Switch(enabled, { enabled = it })
                    Spacer(Modifier.width(8.dp))
                    IdeType.entries.forEach { value -> FilterChip(ide == value, { ide = value }, label = { Text(value.name) }) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SectionHeader("工作区策略", "决定新任务如何准备该服务的代码目录")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WorkspaceStrategy.entries.forEach { value -> FilterChip(strategy == value, { strategy = value }, label = { Text(value.displayName) }) }
                }
                if (strategy == WorkspaceStrategy.STANDARD_WORKTREE) {
                    Text("不同基础分支创建不同 Worktree；相同基础分支的代码模块在 AGENTS.md 中约定。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    modules.forEachIndexed { index, module ->
                        ModuleEditor(module, service.repositoryId, controller, canDelete = modules.size > 1, onChange = { changed -> modules = modules.mapIndexed { i, value -> if (i == index) changed else value } }, onDelete = { modules = modules.filterIndexed { i, _ -> i != index } })
                    }
                    OutlinedButton(onClick = {
                        modules = modules + ServiceModuleConfig(id = "module-${UUID.randomUUID()}", baseRef = "origin/master")
                    }) { Icon(Icons.Outlined.Add, null); Text("添加基础分支模块") }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Bootstrap JSON", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { bootstrapCopied = false; showBootstrapExample = true }) { Text("查看示例") }
                    }
                    OutlinedTextField(
                        bootstrapText,
                        { bootstrapText = it; bootstrapError = null },
                        Modifier.fillMaxWidth(),
                        label = { Text("copyRules 与 commands") },
                        minLines = 5,
                        isError = bootstrapError != null,
                        supportingText = bootstrapError?.let { message -> { Text(message) } },
                    )
                } else {
                    Text("独立克隆模块", fontWeight = FontWeight.SemiBold)
                    Text("选择该服务时会为以下每个固定分支创建独立目录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    cloneModules.forEachIndexed { index, module ->
                        CloneModuleEditor(
                            module = module,
                            repositoryId = service.repositoryId,
                            controller = controller,
                            canDelete = cloneModules.size > 1,
                            onChange = { changed -> cloneModules = cloneModules.mapIndexed { i, value -> if (i == index) changed else value } },
                            onDelete = { cloneModules = cloneModules.filterIndexed { i, _ -> i != index } },
                        )
                    }
                    OutlinedButton(onClick = {
                        cloneModules = cloneModules + IndependentCloneModuleConfig(id = "clone-${UUID.randomUUID()}")
                    }) { Icon(Icons.Outlined.Add, null); Text("添加克隆模块") }
                }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    Text("修改仅影响后续任务", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val bootstrap = if (strategy == WorkspaceStrategy.STANDARD_WORKTREE) {
                            runCatching { json.decodeFromString<BootstrapConfig>(bootstrapText) }
                                .getOrElse { error -> bootstrapError = "JSON 格式错误：${error.message}"; return@Button }
                        } else service.bootstrap
                        val normalizedModules = if (strategy == WorkspaceStrategy.STANDARD_WORKTREE) modules.map { it.copy(name = it.name.trim()) } else emptyList()
                        runCatching {
                            service.copy(
                                displayName = name.trim(), enabled = enabled, ideType = ide, strategy = strategy,
                                modules = normalizedModules,
                                cloneModules = if (strategy == WorkspaceStrategy.INDEPENDENT_CLONE) {
                                    cloneModules.map { it.copy(name = it.name.trim(), branch = it.branch.trim(), uatRef = it.uatRef.trim()) }
                                } else emptyList(),
                                bootstrap = bootstrap,
                            )
                        }.onSuccess(onSave).onFailure { bootstrapError = it.message }
                    }, enabled = name.isNotBlank() && (strategy != WorkspaceStrategy.INDEPENDENT_CLONE || cloneModules.isNotEmpty() && cloneModules.all { it.branch.isNotBlank() }) && (strategy != WorkspaceStrategy.STANDARD_WORKTREE || modules.isNotEmpty())) { Icon(Icons.Outlined.Save, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("保存配置") }
                }
            }
        }
    }
    if (showBootstrapExample) {
        val example = remember { json.encodeToString(BootstrapPresets.example()) }
        Dialog(
            onDismissRequest = { showBootstrapExample = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                Modifier.width(820.dp).height(620.dp),
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Bootstrap JSON 示例", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        if (bootstrapCopied) Text("已复制", color = SuccessGreen, style = MaterialTheme.typography.labelMedium)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Surface(
                        Modifier.weight(1f).fillMaxWidth().padding(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            example,
                            Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showBootstrapExample = false }) { Text("关闭") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            controller.copyText(example, "Bootstrap 示例已复制")
                            bootstrapCopied = true
                        }) {
                            Icon(Icons.Outlined.ContentCopy, null)
                            Spacer(Modifier.width(5.dp))
                            Text("复制")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CloneModuleEditor(
    module: IndependentCloneModuleConfig,
    repositoryId: String,
    controller: DesktopApplication,
    canDelete: Boolean,
    onChange: (IndependentCloneModuleConfig) -> Unit,
    onDelete: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(module.name, { onChange(module.copy(name = it)) }, Modifier.weight(1f), label = { Text("显示名称（可选）") })
                IconButton(onClick = onDelete, enabled = canDelete) { Icon(Icons.Outlined.Delete, "删除模块") }
            }
            RemoteBranchPicker(module.branch, { onChange(module.copy(branch = it)) }, "固定远程分支", repositoryId, controller, Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) { Text("允许参与 UAT Tag", Modifier.weight(1f)); Switch(module.uatTagEnabled, { onChange(module.copy(uatTagEnabled = it)) }) }
            if (module.uatTagEnabled) {
                RemoteBranchPicker(module.uatRef, { onChange(module.copy(uatRef = it)) }, "UAT 目标分支", repositoryId, controller, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(module.initialUatTag.orEmpty(), { onChange(module.copy(initialUatTag = it.ifBlank { null })) }, Modifier.weight(1f), label = { Text("初始 Tag（可选）") })
                    OutlinedTextField(module.tagMessagePrefix, { onChange(module.copy(tagMessagePrefix = it)) }, Modifier.weight(1f), label = { Text("Tag 消息前缀") })
                }
            }
        }
    }
}

/** UI-only branch chooser: all Git I/O remains in DesktopApplication. */
@Composable
private fun RemoteBranchPicker(value: String, onValueChange: (String) -> Unit, label: String, repositoryId: String, controller: DesktopApplication, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val remote = runCatching { RemoteBranchRef.parse(value.trim()).remote }.getOrDefault("origin")
    val state = controller.remoteBranchState(repositoryId, remote)
    Box(modifier) {
        OutlinedTextField(value, onValueChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
            trailingIcon = { IconButton(onClick = { controller.loadRemoteBranches(repositoryId, remote); query = ""; expanded = true }) { Icon(Icons.Outlined.KeyboardArrowDown, "选择远程分支") } })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.widthIn(min = 560.dp, max = 720.dp), containerColor = MaterialTheme.colorScheme.surfaceVariant, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 8.dp), label = { Text("搜索远程分支") }, singleLine = true)
            when (state) {
                RemoteBranchesState.Loading -> DropdownMenuItem(text = { Text("正在读取远程分支…") }, onClick = {}, enabled = false)
                is RemoteBranchesState.Failed -> DropdownMenuItem(text = { Text("加载失败：${state.message}") }, onClick = { controller.loadRemoteBranches(repositoryId, remote, true) })
                is RemoteBranchesState.Loaded -> {
                    val branches = RemoteBranchSearch.filter(state.branches, query)
                    if (branches.isEmpty()) DropdownMenuItem(text = { Text("没有匹配分支") }, onClick = {}, enabled = false)
                    branches.forEach { branch -> DropdownMenuItem(text = { Text(branch) }, onClick = { onValueChange(branch); expanded = false }) }
                }
                RemoteBranchesState.Idle -> DropdownMenuItem(text = { Text("正在准备读取远程分支…") }, onClick = {}, enabled = false)
            }
        }
    }
}

@Composable
private fun ModuleEditor(module: ServiceModuleConfig, repositoryId: String, controller: DesktopApplication, canDelete: Boolean, onChange: (ServiceModuleConfig) -> Unit, onDelete: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    module.name,
                    { onChange(module.copy(name = it)) },
                    Modifier.weight(1f),
                    label = { Text("显示名称（可选）") },
                    placeholder = { Text("单分支默认服务名，多分支默认基础分支末段") },
                )
                RemoteBranchPicker(module.baseRef, { onChange(module.copy(baseRef = it)) }, "基础分支", repositoryId, controller, Modifier.weight(1f))
                IconButton(onClick = onDelete, enabled = canDelete) { Icon(Icons.Outlined.Delete, "删除模块") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("UAT Tag", Modifier.weight(1f)); Switch(module.uatTagEnabled, { onChange(module.copy(uatTagEnabled = it)) })
            }
            if (module.uatTagEnabled) RemoteBranchPicker(module.uatRef, { onChange(module.copy(uatRef = it)) }, "UAT 目标分支", repositoryId, controller, Modifier.fillMaxWidth())
            if (module.uatTagEnabled) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(module.initialUatTag.orEmpty(), { onChange(module.copy(initialUatTag = it.ifBlank { null })) }, Modifier.weight(1f), label = { Text("初始 Tag（可选）") })
                OutlinedTextField(module.tagMessagePrefix, { onChange(module.copy(tagMessagePrefix = it)) }, Modifier.weight(1f), label = { Text("Tag 消息前缀") }, supportingText = { Text("只影响说明首行，不改变 Tag 名") })
            }
        }
    }
}

@Composable
private fun DeleteTaskDialog(controller: DesktopApplication, task: TaskManifest, onDismiss: () -> Unit) {
    LaunchedEffect(task.taskDirectoryName) { controller.requestDeleteRisk(task) }
    val inspection = controller.deleteRiskInspections[task.taskDirectoryName]
    val loading = inspection == null || inspection.loading
    val risks = inspection?.risks.orEmpty()
    val inspectionError = inspection?.error
    val safetyCheckFailed = risks.any { it.statusCheckError != null }
    var discard by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除任务") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("会删除任务目录和其工作区，远程分支不会被修改。")
            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在检查 Git 状态…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            inspectionError?.let { Text("删除检查失败：$it", color = MaterialTheme.colorScheme.error) }
            risks.forEach {
                val unpushed = if (it.unpushedCommits > 0) "，${it.unpushedCommits} 个仅本地提交" else ""
                val detail = it.statusCheckError ?: "存在未提交改动、Git 操作或未推送提交$unpushed"
                Text("• ${it.serviceName}：$detail", color = MaterialTheme.colorScheme.error)
            }
            if (risks.any { it.statusCheckError == null }) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(discard, { discard = it }); Text("确认丢弃未提交改动") }
        } },
        confirmButton = { Button(
            onClick = {
                if (controller.deleteTask(task, risks.isNotEmpty())) onDismiss()
            },
            enabled = !loading && inspectionError == null && !safetyCheckFailed && (risks.isEmpty() || discard) && !controller.busy,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ConfirmDialog(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message) }, confirmButton = { Button(onClick = onConfirm) { Text("确认") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { OutlinedTextField(value, { value = it }, label = { Text("名称") }, singleLine = true) }, confirmButton = { Button(onClick = { onSave(value) }, enabled = value.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun EmptyState(title: String, subtitle: String, action: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                Modifier.padding(horizontal = 48.dp, vertical = 38.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(18.dp)) {
                    Icon(Icons.Outlined.Workspaces, null, Modifier.padding(15.dp).size(34.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Button(onClick = action) { Text("继续") }
            }
        }
    }
}

@Composable
private fun StatusPill(text: String) {
    val color = MaterialTheme.colorScheme.statusColor(text)
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(50), border = BorderStroke(1.dp, color.copy(alpha = 0.18f))) {
        Text(statusLabel(text), Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = color, style = MaterialTheme.typography.labelSmall)
    }
}

private enum class RequirementStatusCategory { PLANNING, DEVELOPMENT, TESTING, DONE, PAUSED, UNKNOWN }

private fun requirementStatusCategory(status: String): RequirementStatusCategory {
    val normalized = status.trim().lowercase()
    fun matches(vararg values: String) = values.any { it.lowercase() in normalized }
    return when {
        matches("已完成", "已验收", "已发布", "已关闭", "done", "closed", "resolved", "完成") -> RequirementStatusCategory.DONE
        matches("已取消", "取消", "暂停", "挂起", "拒绝", "不做", "终止") -> RequirementStatusCategory.PAUSED
        matches("提测", "待测试", "测试中", "验收中", "待验收") -> RequirementStatusCategory.TESTING
        matches("开发中", "研发中", "进行中", "实现中", "编码中") -> RequirementStatusCategory.DEVELOPMENT
        matches("待排期", "排期中", "规划中", "待开始", "未开始", "待开发") -> RequirementStatusCategory.PLANNING
        else -> RequirementStatusCategory.UNKNOWN
    }
}

@Composable
private fun RequirementStatusPill(status: String) {
    val color = when (requirementStatusCategory(status)) {
        RequirementStatusCategory.PLANNING -> MaterialTheme.colorScheme.primary
        RequirementStatusCategory.DEVELOPMENT -> MaterialTheme.colorScheme.tertiary
        RequirementStatusCategory.TESTING -> WarningAmber
        RequirementStatusCategory.DONE -> SuccessGreen
        RequirementStatusCategory.PAUSED, RequirementStatusCategory.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(50), border = BorderStroke(1.dp, color.copy(alpha = 0.18f))) {
        Text(status, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = color, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun MetaPill(text: String) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f), shape = RoundedCornerShape(50)) {
        Text(text, Modifier.padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MetricCard(title: String, value: String, caption: String, modifier: Modifier = Modifier) {
    Surface(
        modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(15.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 23.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun statusLabel(status: String): String = when (status) {
    "CREATING" -> "创建中"
    "READY" -> "就绪"
    "READY_WITH_WARNINGS" -> "有警告"
    "FAILED" -> "失败"
    "ARCHIVED" -> "已归档"
    "SUCCESS" -> "成功"
    "CONFLICT" -> "有冲突"
    "PARTIAL" -> "部分完成"
    else -> status
}

private val WorkspaceStrategy.displayName: String
    get() = when (this) {
        WorkspaceStrategy.STANDARD_WORKTREE -> "标准 Worktree"
        WorkspaceStrategy.INDEPENDENT_CLONE -> "独立克隆"
    }

private val ThemePreference.displayName: String
    get() = when (this) {
        ThemePreference.SYSTEM -> "跟随系统"
        ThemePreference.LIGHT -> "浅色"
        ThemePreference.DARK -> "深色"
    }
