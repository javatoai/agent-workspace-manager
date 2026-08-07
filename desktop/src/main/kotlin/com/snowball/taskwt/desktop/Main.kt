package com.snowball.taskwt.desktop

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.NoteAlt
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

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxSize()) {
                Sidebar(controller.navigation) { controller.navigation = it }
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
private fun Sidebar(selected: NavigationItem, onSelected: (NavigationItem) -> Unit) {
    Surface(Modifier.width(220.dp).fillMaxHeight(), shadowElevation = 2.dp) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = BrandBlue, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Outlined.AccountTree, null, Modifier.padding(10.dp), tint = Color.White)
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("TaskWT", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("0.2.0", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.height(16.dp))
            NavigationItem.entries.forEach { item ->
                val selectedItem = item == selected
                val icon = when (item) {
                    NavigationItem.TASKS -> Icons.Outlined.Workspaces
                    NavigationItem.SERVICES -> Icons.Outlined.Dns
                    NavigationItem.UAT -> Icons.Outlined.Sell
                    NavigationItem.SETTINGS -> Icons.Outlined.Settings
                }
                Surface(
                    Modifier.fillMaxWidth().clickable { onSelected(item) },
                    color = if (selectedItem) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    shape = RoundedCornerShape(11.dp),
                ) {
                    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, tint = if (selectedItem) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(11.dp))
                        Column {
                            Text(item.title, fontWeight = if (selectedItem) FontWeight.SemiBold else FontWeight.Normal)
                            Text(item.subtitle, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Spacer(Modifier.height(5.dp))
            }
            Spacer(Modifier.weight(1f))
            Text("所有配置与任务数据均保存在本地", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TopBar(controller: AppController, onCreate: () -> Unit) {
    Surface(Modifier.fillMaxWidth().height(72.dp), shadowElevation = 1.dp) {
        Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(controller.navigation.title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Text(controller.navigation.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = controller::refresh, enabled = !controller.busy) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("手动刷新")
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
    Row(Modifier.fillMaxSize().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        TaskList(controller, Modifier.width(360.dp).fillMaxHeight())
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
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(17.dp))
        Spacer(Modifier.width(7.dp))
        Text(name, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Text(count.toString(), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TaskCard(task: TaskManifest, selected: Boolean, onSelect: (TaskManifest) -> Unit) {
    ElevatedCard(
        Modifier.fillMaxWidth().clickable { onSelect(task) },
        colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(task.folderName, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusPill(task.status.name)
            }
            Text(task.featureBranch, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${task.services.size} 个工作区", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun TaskDetail(controller: AppController, task: TaskManifest, modifier: Modifier) {
    var notes by remember(task.folderName, task.updatedAt, controller.agentRevision) { mutableStateOf(controller.readTaskNotes(task)) }
    var confirmArchive by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val group = controller.config.groups.firstOrNull { it.id == task.groupId }
    Surface(modifier, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxSize().padding(22.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(task.folderName, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    if (controller.config.groups.size > 1) Text(group?.name ?: task.groupId, color = MaterialTheme.colorScheme.primary)
                    Text(task.featureBranch, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (task.status == WorkspaceStatus.ARCHIVED) {
                    OutlinedButton(onClick = { controller.restoreTask(task) }) { Icon(Icons.Outlined.Restore, null); Text("恢复") }
                } else {
                    OutlinedButton(onClick = { confirmArchive = true }) { Icon(Icons.Outlined.Archive, null); Text("归档") }
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "删除", tint = MaterialTheme.colorScheme.error) }
            }
            if (task.requirementLink.isNotBlank()) {
                AssistChip(onClick = { controller.openUrl(task.requirementLink) }, label = {
                    Text(controller.requirementStatuses[task.folderName]?.let { "飞书需求 · $it" } ?: "打开飞书需求")
                }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(17.dp)) })
            }
            Text("工作区", fontWeight = FontWeight.Bold)
            task.services.forEach { WorkspaceCard(controller, it) }
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.NoteAlt, null)
                Spacer(Modifier.width(8.dp))
                Text("任务人工说明", Modifier.weight(1f), fontWeight = FontWeight.Bold)
                TextButton(onClick = { controller.refreshTaskAgents(task) }) { Text("重新生成系统区") }
            }
            Text("只编辑 TASKWT:TASK-NOTES 标记区；重新生成不会覆盖这里。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(notes, {
                notes = it
                controller.markTaskNotesEdited(task, it)
            }, Modifier.fillMaxWidth(), minLines = 7, readOnly = controller.busy, label = { Text("Task notes") })
            Button(onClick = { controller.saveTaskNotes(task, notes) }, enabled = !controller.busy) {
                Icon(Icons.Outlined.Save, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("保存任务说明")
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
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(workspace.moduleName.ifBlank { workspace.serviceName }, fontWeight = FontWeight.SemiBold)
                Text("${workspace.serviceName} · ${workspace.strategy.displayName}", style = MaterialTheme.typography.labelSmall)
                Text(workspace.branch, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (workspace.warnings.isNotEmpty()) Text(workspace.warnings.joinToString("\n"), color = WarningAmber, style = MaterialTheme.typography.bodySmall)
            }
            StatusPill(workspace.status.name)
            IconButton(onClick = { controller.openWorkspace(workspace) }) { Icon(Icons.Outlined.Code, "用 IDE 打开") }
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
    LazyColumn(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        controller.config.groups.forEach { group ->
            val isExpanded = controller.config.groups.size == 1 || expanded.getOrPut(group.id) { true }
            item(key = "group-${group.id}") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier.weight(1f).then(
                            if (controller.config.groups.size > 1) {
                                Modifier.clickable { expanded[group.id] = !isExpanded }
                            } else {
                                Modifier
                            },
                        ),
                    ) {
                        if (controller.config.groups.size > 1) Text(group.name, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        else Text("服务列表", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text("数组顺序即展示顺序", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (controller.config.groups.size > 1) {
                        IconButton(onClick = { expanded[group.id] = !isExpanded }) {
                            Icon(if (isExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, "折叠业务组")
                        }
                    }
                    Button(onClick = { addToGroup = group.id }) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(5.dp)); Text("添加仓库") }
                }
            }
            if (isExpanded && group.services.isEmpty()) item(key = "empty-${group.id}") {
                OutlinedCard(Modifier.fillMaxWidth()) { Text("该组还没有服务", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (isExpanded) {
                items(group.services, key = { "${group.id}-${it.id}" }) { service ->
                    val repository = controller.config.repositories.firstOrNull { it.id == service.repositoryId }
                    ServiceCard(
                        service,
                        repository,
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
    onEdit: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (service.strategy == WorkspaceStrategy.STANDARD_WORKTREE) Icons.Outlined.AccountTree else Icons.Outlined.ContentCopy, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(service.displayName, fontWeight = FontWeight.Bold)
                Text(repository?.rootPath ?: "仓库配置缺失", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                Text(
                    if (service.strategy == WorkspaceStrategy.STANDARD_WORKTREE) "${service.modules.size} 个基础分支模块" else "独立克隆 · ${service.cloneDefaultBranch}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(service.enabled, null, enabled = false)
            IconButton(onClick = onUp) { Icon(Icons.Outlined.KeyboardArrowUp, "上移") }
            IconButton(onClick = onDown) { Icon(Icons.Outlined.KeyboardArrowDown, "下移") }
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "配置") }
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
    LazyColumn(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(eligible, key = { (task, workspace) -> "${task.folderName}-${workspace.groupServiceId}-${workspace.moduleId}" }) { (task, workspace) ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(workspace.moduleName, fontWeight = FontWeight.Bold)
                        Text("${task.folderName} · ${workspace.branch}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = { controller.buildTag(task, workspace) }, enabled = !controller.busy && workspace.status != WorkspaceStatus.ARCHIVED) {
                        Icon(Icons.Outlined.Sell, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("构建 Tag")
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

    LazyColumn(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            SettingsCard("基础设置", "启动只读取这些本地配置，不扫描仓库。") {
                PathField("任务根目录", taskRoot, { taskRoot = it }, onChoose = { chooseDirectory("选择任务根目录")?.let { taskRoot = it } })
                Button(onClick = { controller.updateTaskRoot(taskRoot) }, enabled = taskRoot.isNotBlank()) { Text("保存任务目录") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemePreference.entries.forEach { theme -> FilterChip(controller.config.theme == theme, { controller.setTheme(theme) }, label = { Text(theme.displayName) }) }
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
                OutlinedButton(onClick = { newGroup = true }) { Icon(Icons.Outlined.Add, null); Text("创建组") }
            }
        }
        item {
            SettingsCard("Agent 说明", "磁盘中的全局/组 AGENTS.md 是唯一准确来源，保存后会同步相关任务。") {
                Text("全局说明", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(globalAgents, {
                    globalAgents = it
                    controller.markGlobalAgentsEdited(it)
                }, Modifier.fillMaxWidth(), minLines = 5, readOnly = controller.busy)
                Button(onClick = { controller.saveGlobalAgents(globalAgents) }, enabled = !controller.busy) { Text("保存全局 AGENTS.md") }
                HorizontalDivider()
                if (controller.config.groups.size > 1) FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    controller.config.groups.forEach { group -> FilterChip(agentGroupId == group.id, {
                        agentGroupId = group.id
                        groupAgents = controller.readGroupAgents(group.id)
                    }, label = { Text(group.name) }) }
                }
                Text(if (controller.config.groups.size > 1) "组说明" else "当前服务说明", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(groupAgents, {
                    groupAgents = it
                    controller.markGroupAgentsEdited(agentGroupId, it)
                }, Modifier.fillMaxWidth(), minLines = 5, readOnly = controller.busy)
                Button(onClick = { controller.saveGroupAgents(agentGroupId, groupAgents) }, enabled = !controller.busy) { Text("保存组 AGENTS.md") }
            }
        }
        item {
            SettingsCard("开发工具", "留空时不会尝试启动对应工具。") {
                PathField("IntelliJ IDEA", idea, { idea = it }, onChoose = { chooseFile("选择 IDEA 可执行文件")?.let { idea = it } })
                PathField("WebStorm", webStorm, { webStorm = it }, onChoose = { chooseFile("选择 WebStorm 可执行文件")?.let { webStorm = it } })
                PathField("终端", terminal, { terminal = it }, onChoose = { chooseFile("选择终端可执行文件")?.let { terminal = it } })
                Button(onClick = { controller.updateExecutables(idea, webStorm, terminal) }) { Text("保存工具配置") }
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
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        Surface(Modifier.widthIn(min = 1050.dp, max = 1200.dp).heightIn(min = 680.dp, max = 820.dp), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxSize().padding(22.dp)) {
                Text("创建研发任务", fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("预览会实时合成全局、组和任务三级说明。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("任务名称") }, singleLine = true)
                        OutlinedTextField(branch, { branch = it }, Modifier.fillMaxWidth(), label = { Text("任务分支") }, singleLine = true)
                        OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth(), label = { Text("飞书需求链接（可选）") }, singleLine = true)
                        if (controller.config.groups.size > 1) {
                            Text("所属业务组", fontWeight = FontWeight.SemiBold)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                controller.config.groups.forEach { candidate -> FilterChip(groupId == candidate.id, {
                                    groupId = candidate.id; selected = emptySet(); overrides.clear()
                                }, label = { Text(candidate.name) }) }
                            }
                        }
                        Text("服务", fontWeight = FontWeight.SemiBold)
                        group.services.filter { it.enabled }.forEach { service ->
                            val checked = service.id in selected
                            OutlinedCard(Modifier.fillMaxWidth().clickable { selected = if (checked) selected - service.id else selected + service.id }) {
                                Column(Modifier.padding(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked, { selected = if (checked) selected - service.id else selected + service.id })
                                        Column(Modifier.weight(1f)) {
                                            Text(service.displayName, fontWeight = FontWeight.Medium)
                                            Text(service.strategy.displayName, style = MaterialTheme.typography.labelSmall)
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
                        OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("任务人工说明") }, minLines = 5)
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Text("AGENTS.md 完整预览", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                            Text(preview, Modifier.padding(13.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { onCreate(name, branch, groupId, selected.toList(), link, overrides.toMap(), notes) },
                        enabled = name.isNotBlank() && branch.isNotBlank() && selected.isNotEmpty() && !controller.busy,
                    ) { Text("创建任务") }
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
        Surface(Modifier.widthIn(min = 760.dp, max = 900.dp).heightIn(max = 780.dp), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(22.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("服务配置", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("展示名称") })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("启用"); Switch(enabled, { enabled = it })
                    IdeType.entries.forEach { value -> FilterChip(ide == value, { ide = value }, label = { Text(value.name) }) }
                }
                Text("工作区策略", fontWeight = FontWeight.SemiBold)
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
                    }, enabled = name.isNotBlank() && (strategy != WorkspaceStrategy.INDEPENDENT_CLONE || cloneBranch.isNotBlank()) && (strategy != WorkspaceStrategy.STANDARD_WORKTREE || modules.isNotEmpty())) { Text("保存") }
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
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.Workspaces, null, Modifier.size(54.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = action) { Text("继续") }
        }
    }
}

@Composable
private fun StatusPill(text: String) {
    Surface(color = MaterialTheme.colorScheme.statusColor(text).copy(alpha = 0.13f), shape = RoundedCornerShape(50)) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.statusColor(text), style = MaterialTheme.typography.labelSmall)
    }
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
