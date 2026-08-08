package com.snowball.taskwt.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Workspaces
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.snowball.taskwt.core.AgentConflictResolution
import com.snowball.taskwt.core.BootstrapConfig
import com.snowball.taskwt.core.GroupServiceConfig
import com.snowball.taskwt.core.IdeType
import com.snowball.taskwt.core.RepositoryConfig
import com.snowball.taskwt.core.ServiceGroupConfig
import com.snowball.taskwt.core.ServiceModuleConfig
import com.snowball.taskwt.core.ServiceWorkspace
import com.snowball.taskwt.core.TaskManifest
import com.snowball.taskwt.core.ThemePreference
import com.snowball.taskwt.core.WorkspaceStatus
import com.snowball.taskwt.core.WorkspaceStrategy
import com.snowball.taskwt.desktop.generated.resources.Res
import com.snowball.taskwt.desktop.generated.resources.app_icon
import org.jetbrains.compose.resources.painterResource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.UUID
import javax.swing.JFileChooser
import javax.swing.UIManager

fun main() = application {
    val controller = remember { AppController() }
    val state = rememberWindowState(width = 1440.dp, height = 900.dp)
    Window(
        onCloseRequest = {
            if (controller.busy) {
                controller.showError(IllegalStateException("操作正在执行，完成前不能关闭应用"))
            } else {
                controller.close()
                exitApplication()
            }
        },
        title = "Task Worktree Manager 0.2.0",
        state = state,
        icon = painterResource(Res.drawable.app_icon),
    ) {
        DisposableEffect(window) {
            window.minimumSize = Dimension(1180, 720)
            val listener = object : WindowAdapter() {
                override fun windowGainedFocus(event: WindowEvent?) = controller.onWindowFocused()
            }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }
        TaskWtTheme(controller.config.theme) { TaskWorktreeApp(controller) }
    }
}

@Composable
private fun TaskWorktreeApp(controller: AppController) {
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
                            NavigationItem.TASKS -> TasksScreen(controller) { showCreate = true }
                            NavigationItem.SERVICES -> ServicesScreen(controller)
                            NavigationItem.UAT -> UatScreen(controller)
                            NavigationItem.SETTINGS -> SettingsScreen(controller)
                        }
                    }
                }
            }
            if (controller.busy) {
                // Consume navigation and editor input while a serialized mutation is
                // running. Dialog actions also check the controller's Boolean result.
                Box(Modifier.fillMaxSize().clickable { })
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }

    if (showCreate) {
        CreateTaskDialog(controller, onDismiss = { showCreate = false }) { name, branch, group, services, link, overrides, notes ->
            if (controller.createTask(name, branch, group, services, link, overrides, notes)) {
                showCreate = false
            }
        }
    }
    controller.tagResult?.let { result ->
        AlertDialog(
            onDismissRequest = controller::clearTagResult,
            title = { Text("UAT 构建结果") },
            text = { Text(result.message ?: "${result.serviceName}：${result.state}${result.tag?.let { "\nTag: $it" }.orEmpty()}") },
            confirmButton = { Button(onClick = controller::clearTagResult) { Text("完成") } },
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
            confirmButton = { Button(onClick = { controller.resolveAgentConflict(AgentConflictResolution.USE_LOCAL) }) { Text("使用本地编辑") } },
            dismissButton = { OutlinedButton(onClick = { controller.resolveAgentConflict(AgentConflictResolution.USE_DISK) }) { Text("使用磁盘版本") } },
        )
    }
}

@Composable
private fun Sidebar(controller: AppController, onSelected: (NavigationItem) -> Unit) {
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
                    Text("TaskWT", style = MaterialTheme.typography.titleLarge)
                    Text("Workspace studio · 0.2.0", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "工作空间",
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NavigationItem.entries.forEach { item ->
                val selectedItem = item == controller.navigation
                val icon = when (item) {
                    NavigationItem.TASKS -> Icons.Outlined.Workspaces
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
            Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f), shape = RoundedCornerShape(13.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.Info, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("本地优先", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text("配置与任务均保存在本机", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(controller: AppController, onCreate: () -> Unit) {
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
            OutlinedButton(onClick = controller::refresh, enabled = !controller.busy) {
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

private fun navigationCount(controller: AppController, item: NavigationItem): Int? = when (item) {
    NavigationItem.TASKS -> controller.tasks.size
    NavigationItem.SERVICES -> controller.config.groups.sumOf { it.services.size }
    NavigationItem.UAT, NavigationItem.SETTINGS -> null
}

private val NavigationItem.pageDescription: String
    get() = when (this) {
        NavigationItem.TASKS -> "集中查看任务状态、工作区与任务说明"
        NavigationItem.SERVICES -> "按业务组管理仓库、模块和工作区策略"
        NavigationItem.UAT -> "从已启用的工作区安全构建测试标签"
        NavigationItem.SETTINGS -> "管理本地目录、业务组、Agent 说明与开发工具"
    }

@Composable
private fun TasksScreen(controller: AppController, onCreate: () -> Unit) {
    if (controller.needsTaskRoot) {
        EmptyState("请先配置任务根目录", "设置完成后即可创建第一个研发任务") {
            controller.navigation = NavigationItem.SETTINGS
        }
        return
    }
    if (controller.tasks.isEmpty()) {
        EmptyState("还没有研发任务", "从已配置的服务创建 Worktree 或独立克隆") { onCreate() }
        return
    }
    Row(Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Surface(
            Modifier.width(372.dp).fillMaxHeight(),
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
                        Text("${controller.tasks.size} 个", Modifier.padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                TaskList(controller, Modifier.fillMaxSize().padding(10.dp))
            }
        }
        controller.selectedTask?.let { TaskDetail(controller, it, Modifier.weight(1f).fillMaxHeight()) }
    }
}

@Composable
private fun TaskList(controller: AppController, modifier: Modifier) {
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (controller.config.groups.size == 1) {
            items(controller.tasks, key = { it.folderName }) { TaskCard(it, it == controller.selectedTask, controller::selectTask) }
        } else {
            controller.config.groups.forEach { group ->
                val grouped = controller.tasks.filter { it.groupId == group.id }
                item(key = "header-${group.id}") {
                    GroupHeader(group.name, grouped.size, expanded[group.id] != false) {
                        expanded[group.id] = expanded[group.id] == false
                    }
                }
                if (expanded[group.id] != false) items(grouped, key = { "${group.id}-${it.folderName}" }) {
                    TaskCard(it, it == controller.selectedTask, controller::selectTask)
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
private fun TaskCard(task: TaskManifest, selected: Boolean, onSelect: (TaskManifest) -> Unit) {
    ElevatedCard(
        Modifier.fillMaxWidth().clickable { onSelect(task) },
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f) else MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (selected) 0.dp else 1.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            if (selected) Surface(Modifier.width(4.dp).height(88.dp), color = MaterialTheme.colorScheme.primary) {}
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(task.folderName, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    StatusPill(task.status.name)
                }
                Text(task.featureBranch, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${task.services.size} 个工作区", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TaskDetail(controller: AppController, task: TaskManifest, modifier: Modifier) {
    var notes by remember(task.folderName, task.updatedAt, controller.agentRevision) { mutableStateOf(controller.readTaskNotes(task)) }
    var confirmArchive by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val group = controller.config.groups.firstOrNull { it.id == task.groupId }
    Surface(
        modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.46f), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(task.folderName, style = MaterialTheme.typography.headlineSmall)
                        Text(task.featureBranch, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            StatusPill(task.status.name)
                            if (controller.config.groups.size > 1) MetaPill(group?.name ?: task.groupId)
                            MetaPill("${task.services.size} 个工作区")
                        }
                        if (task.requirementLink.isNotBlank()) {
                            AssistChip(onClick = { controller.openUrl(task.requirementLink) }, label = {
                                Text(controller.requirementStatuses[task.folderName]?.let { "飞书需求 · $it" } ?: "打开飞书需求")
                            }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(17.dp)) })
                        }
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (task.status == WorkspaceStatus.ARCHIVED) {
                            OutlinedButton(onClick = { controller.restoreTask(task) }) { Icon(Icons.Outlined.Restore, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("恢复") }
                        } else {
                            OutlinedButton(onClick = { confirmArchive = true }) { Icon(Icons.Outlined.Archive, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("归档") }
                        }
                        TextButton(onClick = { confirmDelete = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                            Icon(Icons.Outlined.Delete, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("删除任务")
                        }
                    }
                }
            }
            SectionHeader("工作区", "进入 IDE、终端或文件目录")
            task.services.forEach { WorkspaceCard(controller, it) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                SectionHeader("任务人工说明", "仅保存 TASKWT:TASK-NOTES 人工区", Modifier.weight(1f))
                TextButton(onClick = { controller.refreshTaskAgents(task) }) { Text("重新生成系统区") }
            }
            OutlinedTextField(notes, {
                notes = it
                controller.markTaskNotesEdited(task, it)
            }, Modifier.fillMaxWidth(), minLines = 4, maxLines = 6, readOnly = controller.busy, label = { Text("任务说明") })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = { controller.saveTaskNotes(task, notes) }, enabled = !controller.busy) {
                    Icon(Icons.Outlined.Save, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("保存说明")
                }
            }
        }
    }
    if (confirmArchive) ConfirmDialog("归档任务", "安全检查通过后移除工作区，保留任务清单。", onDismiss = { confirmArchive = false }) {
        if (controller.archiveTask(task)) confirmArchive = false
    }
    if (confirmDelete) DeleteTaskDialog(controller, task) {
        controller.clearDeleteRisk(task)
        confirmDelete = false
    }
}

@Composable
private fun WorkspaceCard(controller: AppController, workspace: ServiceWorkspace) {
    OutlinedCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(11.dp)) {
                Icon(
                    if (workspace.strategy == WorkspaceStrategy.STANDARD_WORKTREE) Icons.Outlined.AccountTree else Icons.Outlined.ContentCopy,
                    null,
                    Modifier.padding(9.dp).size(19.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(workspace.moduleName.ifBlank { workspace.serviceName }, style = MaterialTheme.typography.titleSmall)
                Text("${workspace.serviceName} · ${workspace.strategy.displayName}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(workspace.branch, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (workspace.warnings.isNotEmpty()) Text(workspace.warnings.joinToString("\n"), color = WarningAmber, style = MaterialTheme.typography.bodySmall)
            }
            StatusPill(workspace.status.name)
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { controller.openWorkspace(workspace) }, enabled = workspace.status != WorkspaceStatus.ARCHIVED) {
                Icon(Icons.Outlined.Code, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("打开 IDE")
            }
            IconButton(onClick = { controller.terminal(workspace.worktreePath) }) { Icon(Icons.Outlined.Terminal, "终端") }
            IconButton(onClick = { controller.reveal(workspace.worktreePath) }) { Icon(Icons.Outlined.FolderOpen, "文件夹") }
        }
    }
}

@Composable
private fun ServicesScreen(controller: AppController) {
    var editTarget by remember { mutableStateOf<Pair<String, GroupServiceConfig>?>(null) }
    var addToGroup by remember { mutableStateOf<String?>(null) }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val serviceCount = controller.config.groups.sumOf { it.services.size }
    val standardCount = controller.config.groups.sumOf { group -> group.services.count { it.strategy == WorkspaceStrategy.STANDARD_WORKTREE } }
    LazyColumn(
        Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "service-overview") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricCard("业务组", controller.config.groups.size.toString(), "按业务边界组织", Modifier.weight(1f))
                MetricCard("服务", serviceCount.toString(), "已配置仓库入口", Modifier.weight(1f))
                MetricCard("Worktree", standardCount.toString(), "标准隔离工作区", Modifier.weight(1f))
                MetricCard("独立克隆", (serviceCount - standardCount).toString(), "固定分支工作区", Modifier.weight(1f))
            }
        }
        controller.config.groups.forEach { group ->
            val isExpanded = controller.config.groups.size == 1 || expanded.getOrPut(group.id) { true }
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
                        Column(
                            Modifier.weight(1f).then(
                                if (controller.config.groups.size > 1) Modifier.clickable { expanded[group.id] = !isExpanded } else Modifier,
                            ),
                        ) {
                            Text(if (controller.config.groups.size > 1) group.name else "服务列表", style = MaterialTheme.typography.titleMedium)
                            Text("${group.services.size} 个服务 · UAT Tag ${if (group.createTagEnabled) "已开启" else "已关闭"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (controller.config.groups.size > 1) {
                            IconButton(onClick = { expanded[group.id] = !isExpanded }) {
                                Icon(if (isExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, "折叠业务组")
                            }
                        }
                        Button(onClick = { addToGroup = group.id }) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("添加仓库") }
                    }
                }
            }
            if (isExpanded && group.services.isEmpty()) item(key = "empty-${group.id}") {
                OutlinedCard(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(9.dp))
                        Text("该组还没有服务，添加一个 Git 仓库开始配置。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (isExpanded) {
                items(group.services, key = { "${group.id}-${it.id}" }) { service ->
                    val index = group.services.indexOfFirst { it.id == service.id }
                    val repository = controller.config.repositories.firstOrNull { it.id == service.repositoryId }
                    ServiceCard(
                        service,
                        repository,
                        canMoveUp = index > 0,
                        canMoveDown = index in 0 until group.services.lastIndex,
                        onEdit = { editTarget = group.id to service },
                        onUp = { controller.moveService(group.id, service.id, -1) },
                        onDown = { controller.moveService(group.id, service.id, 1) },
                        onRemove = { controller.removeService(group.id, service.id) },
                    )
                }
            }
        }
    }
    addToGroup?.let { groupId -> AddRepositoryDialog(onDismiss = { addToGroup = null }) { path, strategy ->
        if (controller.addRepository(groupId, path, strategy)) addToGroup = null
    } }
    editTarget?.let { (groupId, service) -> ServiceEditorDialog(service, onDismiss = { editTarget = null }) {
        if (controller.updateService(groupId, it)) editTarget = null
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
                Text(if (service.strategy == WorkspaceStrategy.STANDARD_WORKTREE) "${service.modules.size} 个基础分支模块" else "默认分支 ${service.cloneDefaultBranch}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onUp, enabled = canMoveUp) { Icon(Icons.Outlined.KeyboardArrowUp, "上移") }
            IconButton(onClick = onDown, enabled = canMoveDown) { Icon(Icons.Outlined.KeyboardArrowDown, "下移") }
            OutlinedButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("配置") }
            IconButton(onClick = onRemove) { Icon(Icons.Outlined.Delete, "移除", tint = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun UatScreen(controller: AppController) {
    val eligible = controller.tasks.flatMap { task ->
        val group = controller.config.groups.firstOrNull { it.id == task.groupId }
        if (group?.createTagEnabled != true) emptyList() else task.services.filter { workspace ->
            val service = group.services.firstOrNull { it.id == workspace.groupServiceId }
            when (service?.strategy) {
                WorkspaceStrategy.STANDARD_WORKTREE -> service.modules
                    .firstOrNull { it.id == workspace.moduleId }
                    ?.tagEnabled == true
                WorkspaceStrategy.INDEPENDENT_CLONE -> service.cloneTagEnabled
                null -> false
            }
        }.map { task to it }
    }
    if (eligible.isEmpty()) {
        EmptyState("暂无可构建的 UAT 入口", "组总开关与模块/克隆子开关需同时开启") { controller.navigation = NavigationItem.SERVICES }
        return
    }
    val grouped = eligible.groupBy { it.first }
    LazyColumn(
        Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        item(key = "uat-summary") {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
            ) {
                Row(Modifier.fillMaxWidth().padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), shape = RoundedCornerShape(12.dp)) {
                        Icon(Icons.Outlined.Sell, null, Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${eligible.size} 个可构建入口", style = MaterialTheme.typography.titleMedium)
                        Text("仅展示组总开关和服务子开关同时启用的工作区", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    MetaPill("${grouped.size} 个任务")
                }
            }
        }
        grouped.forEach { (task, entries) ->
            item(key = "uat-task-${task.taskDirectoryName}") {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(task.folderName, style = MaterialTheme.typography.titleMedium)
                        Text(task.featureBranch, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("${entries.size} 个入口", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(entries, key = { (_, workspace) -> "${task.folderName}-${workspace.groupServiceId}-${workspace.moduleId}" }) { (_, workspace) ->
                ElevatedCard(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
                ) {
                    Row(Modifier.padding(horizontal = 17.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(11.dp)) {
                            Icon(Icons.Outlined.AccountTree, null, Modifier.padding(9.dp).size(19.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(workspace.moduleName, style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.width(8.dp))
                                StatusPill(workspace.status.name)
                            }
                            Text("${workspace.serviceName} · ${workspace.branch}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = { controller.buildTag(task, workspace) }, enabled = !controller.busy && workspace.status != WorkspaceStatus.ARCHIVED) {
                            Icon(Icons.Outlined.Sell, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("构建 Tag")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(controller: AppController) {
    var taskRoot by remember(controller.config.taskRoot) { mutableStateOf(controller.config.taskRoot.orEmpty()) }
    var idea by remember(controller.config.ideaExecutable) { mutableStateOf(controller.config.ideaExecutable.orEmpty()) }
    var webStorm by remember(controller.config.webStormExecutable) { mutableStateOf(controller.config.webStormExecutable.orEmpty()) }
    var terminal by remember(controller.config.terminalExecutable) { mutableStateOf(controller.config.terminalExecutable.orEmpty()) }
    var newGroup by remember { mutableStateOf(false) }
    var renameGroup by remember { mutableStateOf<ServiceGroupConfig?>(null) }
    var agentGroupId by remember(controller.config.groups) { mutableStateOf(controller.config.groups.first().id) }
    var globalAgents by remember(controller.agentRevision) { mutableStateOf(controller.readGlobalAgents()) }
    var groupAgents by remember(agentGroupId, controller.agentRevision) { mutableStateOf(controller.readGroupAgents(agentGroupId)) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.widthIn(max = 1080.dp).fillMaxHeight().align(Alignment.TopCenter).padding(start = 28.dp, end = 28.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
            SettingsCard("基础设置", "启动只读取这些本地配置，不扫描仓库。") {
                PathField("任务根目录", taskRoot, { taskRoot = it }, onChoose = { chooseDirectory("选择任务根目录")?.let { taskRoot = it } })
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
            SettingsCard("业务组", "组和组内服务均按数组顺序展示；只能删除没有服务和任务的空组。") {
                if (controller.config.groups.size == 1) {
                    val group = controller.config.groups.single()
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("单组模式", fontWeight = FontWeight.SemiBold)
                                Text("未创建额外业务组时，任务和服务界面不会显示组层级。", style = MaterialTheme.typography.labelSmall)
                            }
                            Text("UAT Tag 总开关")
                            Spacer(Modifier.width(8.dp))
                            Switch(group.createTagEnabled, { controller.setGroupTagEnabled(group.id, it) })
                        }
                    }
                } else controller.config.groups.forEachIndexed { index, group ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(group.name, fontWeight = FontWeight.SemiBold)
                                Text("${group.services.size} 个服务", style = MaterialTheme.typography.labelSmall)
                            }
                            Text("Tag")
                            Switch(group.createTagEnabled, { controller.setGroupTagEnabled(group.id, it) })
                            IconButton(onClick = { controller.moveGroup(group.id, -1) }, enabled = index > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "上移") }
                            IconButton(onClick = { controller.moveGroup(group.id, 1) }, enabled = index < controller.config.groups.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, "下移") }
                            IconButton(onClick = { renameGroup = group }) { Icon(Icons.Outlined.Edit, "重命名") }
                            IconButton(onClick = { runCatching { controller.deleteGroup(group.id) }.onFailure(controller::showError) }, enabled = group.services.isEmpty()) { Icon(Icons.Outlined.Delete, "删除") }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = { newGroup = true }) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("创建业务组") }
                }
            }
            }
            item {
            SettingsCard("Agent 说明", "磁盘中的全局/组 AGENTS.md 是唯一准确来源，保存后会同步相关任务。") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("全局说明", style = MaterialTheme.typography.titleSmall)
                        Text("对所有任务生效", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(globalAgents, {
                            globalAgents = it
                            controller.markGlobalAgentsEdited(it)
                        }, Modifier.fillMaxWidth(), minLines = 7, readOnly = controller.busy)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button(onClick = { controller.saveGlobalAgents(globalAgents) }, enabled = !controller.busy) { Text("保存全局说明") }
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (controller.config.groups.size > 1) "组说明" else "当前服务说明", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            if (controller.config.groups.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                controller.config.groups.forEach { group -> FilterChip(agentGroupId == group.id, {
                                    agentGroupId = group.id
                                    groupAgents = controller.readGroupAgents(group.id)
                                }, label = { Text(group.name) }) }
                            }
                        }
                        Text("仅对当前业务组的任务生效", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(groupAgents, {
                            groupAgents = it
                            controller.markGroupAgentsEdited(agentGroupId, it)
                        }, Modifier.fillMaxWidth(), minLines = 7, readOnly = controller.busy)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button(onClick = { controller.saveGroupAgents(agentGroupId, groupAgents) }, enabled = !controller.busy) { Text("保存组说明") }
                        }
                    }
                }
            }
            }
            item {
            SettingsCard("开发工具", "留空时不会尝试启动对应工具。") {
                PathField("IntelliJ IDEA", idea, { idea = it }, onChoose = { chooseFile("选择 IDEA 可执行文件")?.let { idea = it } })
                PathField("WebStorm", webStorm, { webStorm = it }, onChoose = { chooseFile("选择 WebStorm 可执行文件")?.let { webStorm = it } })
                PathField("终端", terminal, { terminal = it }, onChoose = { chooseFile("选择终端可执行文件")?.let { terminal = it } })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = { controller.updateExecutables(idea, webStorm, terminal) }) { Text("保存工具配置") }
                }
            }
            }
        }
    }
    if (newGroup) NameDialog("创建业务组", "", onDismiss = { newGroup = false }) {
        if (controller.addGroup(it)) newGroup = false
    }
    renameGroup?.let { group -> NameDialog("重命名业务组", group.name, onDismiss = { renameGroup = null }) {
        if (controller.renameGroup(group.id, it)) renameGroup = null
    } }
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
private fun PathField(label: String, value: String, onValueChange: (String) -> Unit, onChoose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value, onValueChange, Modifier.weight(1f), label = { Text(label) }, singleLine = true)
        OutlinedButton(onClick = onChoose) { Icon(Icons.Outlined.Folder, null); Text("选择") }
    }
}

@Composable
private fun CreateTaskDialog(
    controller: AppController,
    onDismiss: () -> Unit,
    onCreate: (String, String, String, List<String>, String, Map<String, String>, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var groupId by remember { mutableStateOf(controller.config.groups.first().id) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val overrides = remember { mutableStateMapOf<String, String>() }
    val group = controller.config.groups.first { it.id == groupId }
    val preview = remember(name, branch, groupId, selected, overrides.toMap(), notes) {
        controller.previewAgents(name, branch, groupId, selected, overrides.toMap(), notes)
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            Modifier.widthIn(min = 1080.dp, max = 1220.dp).heightIn(min = 700.dp, max = 840.dp),
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
                            Text("选择业务组和服务，并实时确认最终 AGENTS.md", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("任务名称") }, placeholder = { Text("例如：PAY-1024 支付订单优化") }, singleLine = true)
                        OutlinedTextField(branch, { branch = it }, Modifier.fillMaxWidth(), label = { Text("任务分支") }, placeholder = { Text("例如：feature/PAY-1024") }, singleLine = true)
                        OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth(), label = { Text("飞书需求链接（可选）") }, singleLine = true)
                        if (controller.config.groups.size > 1) {
                            Spacer(Modifier.height(2.dp))
                            SectionHeader("所属业务组", "任务创建后归属不可自动迁移")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                controller.config.groups.forEach { candidate -> FilterChip(groupId == candidate.id, {
                                    groupId = candidate.id; selected = emptySet(); overrides.clear()
                                }, label = { Text(candidate.name) }) }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        SectionHeader("选择服务", "标准服务创建 Worktree，独立克隆直接切换配置分支")
                        group.services.filter { it.enabled }.forEach { service ->
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
                                                if (service.strategy == WorkspaceStrategy.STANDARD_WORKTREE) "${service.modules.size} 个模块 · ${service.strategy.displayName}" else "默认 ${service.cloneDefaultBranch} · ${service.strategy.displayName}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    if (checked && service.strategy == WorkspaceStrategy.INDEPENDENT_CLONE) {
                                        OutlinedTextField(
                                            overrides[service.id] ?: service.cloneDefaultBranch.orEmpty(),
                                            { overrides[service.id] = it },
                                            Modifier.fillMaxWidth(),
                                            label = { Text("本任务克隆分支") },
                                            singleLine = true,
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        SectionHeader("任务说明", "只写入任务级人工说明区，可在详情页继续编辑")
                        OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("任务人工说明") }, minLines = 5)
                    }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionHeader("AGENTS.md 完整预览", "系统信息、全局、组和任务说明的最终合成结果", Modifier.weight(1f))
                            MetaPill("实时更新")
                        }
                        Surface(
                            Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Text(preview, Modifier.padding(15.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (selected.isEmpty()) "至少选择一个服务" else "将创建 ${selected.size} 个服务入口",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onCreate(name, branch, groupId, selected.toList(), link, overrides.toMap(), notes) },
                        enabled = name.isNotBlank() && branch.isNotBlank() && selected.isNotEmpty() && !controller.busy,
                    ) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("创建任务") }
                }
            }
        }
    }
}

@Composable
private fun AddRepositoryDialog(onDismiss: () -> Unit, onAdd: (String, WorkspaceStrategy) -> Unit) {
    var path by remember { mutableStateOf("") }
    var strategy by remember { mutableStateOf(WorkspaceStrategy.STANDARD_WORKTREE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动添加 Git 仓库") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("会校验 Git 顶层仓库并按 git-common-dir 去重；不接受 bare 或临时 Linked Worktree。")
                PathField("仓库目录", path, { path = it }, onChoose = { chooseDirectory("选择 Git 仓库")?.let { path = it } })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WorkspaceStrategy.entries.forEach { value -> FilterChip(strategy == value, { strategy = value }, label = { Text(value.displayName) }) }
                }
                if (strategy == WorkspaceStrategy.INDEPENDENT_CLONE) Text("独立克隆使用 origin 完整克隆，不创建 Feature 分支也不执行 Bootstrap。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = { onAdd(path, strategy) }, enabled = path.isNotBlank()) { Text("校验并添加") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ServiceEditorDialog(service: GroupServiceConfig, onDismiss: () -> Unit, onSave: (GroupServiceConfig) -> Unit) {
    val json = remember { Json { prettyPrint = true; encodeDefaults = true } }
    var name by remember { mutableStateOf(service.displayName) }
    var enabled by remember { mutableStateOf(service.enabled) }
    var ide by remember { mutableStateOf(service.ideType) }
    var strategy by remember { mutableStateOf(service.strategy) }
    var modules by remember { mutableStateOf(service.modules) }
    var cloneBranch by remember { mutableStateOf(service.cloneDefaultBranch.orEmpty()) }
    var cloneTag by remember { mutableStateOf(service.cloneTagEnabled) }
    var cloneRemote by remember { mutableStateOf(service.cloneUatRemote) }
    var cloneUat by remember { mutableStateOf(service.cloneUatBranch) }
    var cloneInitialTag by remember { mutableStateOf(service.cloneInitialUatTag.orEmpty()) }
    var cloneMessagePrefix by remember { mutableStateOf(service.cloneTagMessagePrefix) }
    var bootstrapText by remember { mutableStateOf(json.encodeToString(service.bootstrap)) }
    var bootstrapError by remember { mutableStateOf<String?>(null) }
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
                SectionHeader("基础信息", "配置显示名称、启用状态与默认 IDE")
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
                        ModuleEditor(module, canDelete = modules.size > 1, onChange = { changed -> modules = modules.mapIndexed { i, value -> if (i == index) changed else value } }, onDelete = { modules = modules.filterIndexed { i, _ -> i != index } })
                    }
                    OutlinedButton(onClick = {
                        modules = modules + ServiceModuleConfig(id = "module-${UUID.randomUUID()}", name = "新模块", baseRef = "origin/master")
                    }) { Icon(Icons.Outlined.Add, null); Text("添加基础分支模块") }
                    OutlinedTextField(
                        bootstrapText,
                        { bootstrapText = it; bootstrapError = null },
                        Modifier.fillMaxWidth(),
                        label = { Text("Bootstrap JSON") },
                        minLines = 5,
                        isError = bootstrapError != null,
                        supportingText = bootstrapError?.let { message -> { Text(message) } },
                    )
                } else {
                    Text("独立克隆", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(cloneBranch, { cloneBranch = it }, Modifier.fillMaxWidth(), label = { Text("默认远程分支") })
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("允许参与 UAT Tag", Modifier.weight(1f)); Switch(cloneTag, { cloneTag = it }) }
                    if (cloneTag) {
                        OutlinedTextField(cloneRemote, { cloneRemote = it }, Modifier.fillMaxWidth(), label = { Text("UAT remote") })
                        OutlinedTextField(cloneUat, { cloneUat = it }, Modifier.fillMaxWidth(), label = { Text("UAT branch") })
                        OutlinedTextField(cloneInitialTag, { cloneInitialTag = it }, Modifier.fillMaxWidth(), label = { Text("初始 UAT Tag（可选）") })
                        OutlinedTextField(cloneMessagePrefix, { cloneMessagePrefix = it }, Modifier.fillMaxWidth(), label = { Text("Tag 消息前缀") })
                    }
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
                        val normalizedModules = if (strategy == WorkspaceStrategy.STANDARD_WORKTREE) modules else emptyList()
                        runCatching {
                            service.copy(
                                displayName = name.trim(), enabled = enabled, ideType = ide, strategy = strategy,
                                modules = normalizedModules,
                                cloneDefaultBranch = if (strategy == WorkspaceStrategy.INDEPENDENT_CLONE) cloneBranch.trim() else null,
                                cloneTagEnabled = cloneTag, cloneUatRemote = cloneRemote.trim(), cloneUatBranch = cloneUat.trim(),
                                cloneInitialUatTag = cloneInitialTag.trim().ifBlank { null },
                                cloneTagMessagePrefix = cloneMessagePrefix.trim().ifBlank { "UAT" },
                                bootstrap = bootstrap,
                            )
                        }.onSuccess(onSave).onFailure { bootstrapError = it.message }
                    }, enabled = name.isNotBlank() && (strategy != WorkspaceStrategy.INDEPENDENT_CLONE || cloneBranch.isNotBlank()) && (strategy != WorkspaceStrategy.STANDARD_WORKTREE || modules.isNotEmpty())) { Icon(Icons.Outlined.Save, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("保存配置") }
                }
            }
        }
    }
}

@Composable
private fun ModuleEditor(module: ServiceModuleConfig, canDelete: Boolean, onChange: (ServiceModuleConfig) -> Unit, onDelete: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(module.name, { onChange(module.copy(name = it)) }, Modifier.weight(1f), label = { Text("模块名") })
                OutlinedTextField(module.baseRef, { onChange(module.copy(baseRef = it)) }, Modifier.weight(1f), label = { Text("基础分支") })
                IconButton(onClick = onDelete, enabled = canDelete) { Icon(Icons.Outlined.Delete, "删除模块") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("UAT Tag", Modifier.weight(1f)); Switch(module.tagEnabled, { onChange(module.copy(tagEnabled = it)) })
            }
            if (module.tagEnabled) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(module.uatRemote, { onChange(module.copy(uatRemote = it)) }, Modifier.weight(1f), label = { Text("remote") })
                OutlinedTextField(module.uatBranch, { onChange(module.copy(uatBranch = it)) }, Modifier.weight(1f), label = { Text("UAT branch") })
            }
            if (module.tagEnabled) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(module.initialUatTag.orEmpty(), { onChange(module.copy(initialUatTag = it.ifBlank { null })) }, Modifier.weight(1f), label = { Text("初始 Tag（可选）") })
                OutlinedTextField(module.tagMessagePrefix, { onChange(module.copy(tagMessagePrefix = it)) }, Modifier.weight(1f), label = { Text("消息前缀") })
            }
        }
    }
}

@Composable
private fun DeleteTaskDialog(controller: AppController, task: TaskManifest, onDismiss: () -> Unit) {
    LaunchedEffect(task.taskDirectoryName) { controller.requestDeleteRisk(task) }
    val inspection = controller.deleteRiskInspections[task.taskDirectoryName]
    val loading = inspection == null || inspection.loading
    val risks = inspection?.risks.orEmpty()
    val inspectionError = inspection?.error
    var discard by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除任务") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("会删除任务目录和其工作区，保留远程分支。")
            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在检查 Git 状态…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            inspectionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            risks.forEach {
                val unpushed = if (it.unpushedCommits > 0) "，${it.unpushedCommits} 个仅本地提交" else ""
                Text("• ${it.serviceName}：存在未提交改动、Git 操作或未推送提交$unpushed", color = MaterialTheme.colorScheme.error)
            }
            if (risks.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(discard, { discard = it }); Text("确认丢弃未提交改动") }
        } },
        confirmButton = { Button(
            onClick = {
                if (controller.deleteTask(task, risks.isNotEmpty())) onDismiss()
            },
            enabled = !loading && inspectionError == null && (risks.isEmpty() || discard) && !controller.busy,
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

private fun chooseDirectory(title: String): String? = choosePath(title, JFileChooser.DIRECTORIES_ONLY)
private fun chooseFile(title: String): String? = choosePath(title, JFileChooser.FILES_ONLY)

private fun choosePath(title: String, mode: Int): String? {
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val chooser = JFileChooser().apply { dialogTitle = title; fileSelectionMode = mode; isAcceptAllFileFilterUsed = false }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
}
