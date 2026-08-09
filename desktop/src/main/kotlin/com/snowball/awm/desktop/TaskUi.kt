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
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.UUID


@Composable
internal fun TasksScreen(controller: DesktopApplication, archived: Boolean, onCreate: () -> Unit) {
    if (controller.needsTaskRoot) {
        EmptyState("请先配置任务根目录", "设置完成后即可创建第一个研发任务", "前往设置") {
            controller.navigation = NavigationItem.SETTINGS
        }
        return
    }
    val visibleTasks = controller.tasks.filter { (it.lifecycleStatus == TaskLifecycleStatus.ARCHIVED) == archived }
    if (visibleTasks.isEmpty()) {
        if (archived) {
            EmptyState("还没有已归档任务", "归档后的任务会保留在这里，可随时恢复。", "返回研发任务") {
                controller.navigation = NavigationItem.TASKS
            }
        } else {
            EmptyState("还没有研发任务", "从已配置的服务创建 Worktree 或独立克隆", "创建第一个任务", onCreate)
        }
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
        controller.selectedTask?.takeIf { (it.lifecycleStatus == TaskLifecycleStatus.ARCHIVED) == archived }?.let {
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
                    if (task.lifecycleStatus == TaskLifecycleStatus.ARCHIVED) StatusPill("ARCHIVED")
                    StatusPill(task.health.name)
                }
                Text(task.featureBranch, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                RequirementStatePill(controller.requirementController.stateFor(task))
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
    val failedServiceIds = task.services.filter { it.health == WorkspaceHealth.FAILED }
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
                        if (task.lifecycleStatus == TaskLifecycleStatus.ARCHIVED) StatusPill("ARCHIVED")
                        StatusPill(task.health.name)
                        if (controller.config.groups.size > 1) MetaPill(group?.name ?: task.groupId)
                        MetaPill("${task.services.size} 个工作区")
                        Spacer(Modifier.weight(1f))
                        if (task.lifecycleStatus == TaskLifecycleStatus.ARCHIVED) {
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
                            ActionIconButton(
                                label = "复制需求链接",
                                onClick = { controller.copyText(task.requirementLink, "需求链接已复制") },
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(Icons.Outlined.ContentCopy, "复制需求链接", Modifier.size(15.dp))
                            }
                            val requirementState = controller.requirementController.stateFor(task)
                            RequirementStatePill(requirementState)
                            (requirementState as? RequirementUiState.Loaded)?.metadata?.participants?.let { participants ->
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
        controller.archiveTask(task, onCompleted = { confirmArchive = false })
    }
    if (confirmDelete) DeleteTaskDialog(controller, task) {
        controller.clearDeleteRisk(task)
        confirmDelete = false
    }
    if (showAddServices) AddTaskServicesDialog(controller, task, onDismiss = { showAddServices = false }) { ids ->
        controller.addServices(task, ids) { showAddServices = false }
    }
    if (showBatchTag) BatchTagDialog(tagWorkspaces, onDismiss = { showBatchTag = false }) { selected ->
        controller.deliveryController.buildBatch(task, selected) { showBatchTag = false }
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
                        StatusPill(workspace.health.name)
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
                            ActionIconButton("在终端中打开", { controller.terminal(workspace.worktreePath) }, Modifier.size(34.dp)) {
                                Icon(Icons.Outlined.Terminal, "终端", Modifier.size(18.dp))
                            }
                            ActionIconButton("打开工作区文件夹", { controller.openDirectory(workspace.worktreePath) }, Modifier.size(34.dp)) {
                                Icon(Icons.Outlined.FolderOpen, "打开文件夹", Modifier.size(18.dp))
                            }
                            ActionIconButton("复制工作区完整路径", { controller.copyText(workspace.worktreePath, "工作区路径已复制") }, Modifier.size(34.dp)) {
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
                    if (workspace.health == WorkspaceHealth.FAILED && workspace.groupServiceId.isNotBlank()) {
                        OutlinedButton(onClick = { controller.retryFailedServices(task, listOf(workspace.groupServiceId)) }, enabled = !controller.busy) {
                            Icon(Icons.Outlined.Refresh, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("重试")
                        }
                    }
                }
        }
    }
}
