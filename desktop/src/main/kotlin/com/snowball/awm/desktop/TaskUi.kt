package com.snowball.awm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Commit
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Publish
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
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.snowball.awm.core.WorkspaceGitHealth
import com.snowball.awm.core.WorkspaceGitHealthState
import com.snowball.awm.core.WorkspaceGitIssue
import com.snowball.awm.core.WorkspaceRepairConfirmation
import com.snowball.awm.core.WorkspaceRepairPreview
import com.snowball.awm.core.WorkspaceGitBatchMode
import com.snowball.awm.core.WorkspaceGitBatchResult
import com.snowball.awm.core.WorkspaceGitStepState
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
    var taskQuery by remember(archived) { mutableStateOf("") }
    fun requirementTitle(task: TaskManifest): String? =
        (controller.requirementController.stateFor(task) as? RequirementUiState.Loaded)?.metadata?.title
    val filteredTasks = filterTasks(
        tasks = visibleTasks,
        query = taskQuery,
        requirementTitle = ::requirementTitle,
    )
    LaunchedEffect(archived, visibleTasks.joinToString { "${it.taskDirectoryName}:${it.requirementLink}" }) {
        controller.requirementController.refreshAll()
    }
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
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("任务列表", style = MaterialTheme.typography.titleMedium)
                            Text("按更新时间排列", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(20.dp)) {
                            Text("${filteredTasks.size}/${visibleTasks.size}", Modifier.padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    OutlinedTextField(
                        taskQuery,
                        { taskQuery = it },
                        Modifier.fillMaxWidth(),
                        label = { Text("搜索任务") },
                        placeholder = { Text("任务、需求、分支或服务") },
                        singleLine = true,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (filteredTasks.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("没有匹配的任务", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    TaskList(controller, filteredTasks, archived, Modifier.fillMaxSize().padding(10.dp))
                }
            }
        }
        controller.selectedTask?.takeIf { (it.lifecycleStatus == TaskLifecycleStatus.ARCHIVED) == archived }?.let {
            TaskDetail(controller, it, Modifier.weight(1f).widthIn(min = 900.dp).fillMaxHeight())
        }
    }
}

@Composable
private fun TaskList(controller: DesktopApplication, taskItems: List<TaskManifest>, archived: Boolean, modifier: Modifier) {
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (controller.config.groups.size == 1) {
            items(taskItems, key = { it.folderName }) { TaskCard(controller, it, it == controller.selectedTask, archived, controller::selectTask) }
        } else {
            controller.config.groups.forEach { group ->
                val grouped = taskItems.filter { it.groupId == group.id }
                item(key = "header-${group.id}") {
                    GroupHeader(group.name, grouped.size, expanded[group.id] != false) {
                        expanded[group.id] = expanded[group.id] == false
                    }
                }
                if (expanded[group.id] != false) items(grouped, key = { "${group.id}-${it.folderName}" }) {
                    TaskCard(controller, it, it == controller.selectedTask, archived, controller::selectTask)
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
private fun TaskCard(controller: DesktopApplication, task: TaskManifest, selected: Boolean, archivedList: Boolean, onSelect: (TaskManifest) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            .compositeOver(MaterialTheme.colorScheme.surface)
    } else {
        MaterialTheme.colorScheme.surface
    }
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().clip(shape).clickable { onSelect(task) },
        shape = shape,
        colors = CardDefaults.elevatedCardColors(
            containerColor = containerColor,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (selected) 0.dp else 1.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).background(containerColor)) {
            if (selected) Surface(Modifier.width(4.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.primary) {}
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TooltipText(task.folderName, Modifier.weight(1f), MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                    if (!archivedList && task.lifecycleStatus == TaskLifecycleStatus.ARCHIVED) StatusPill("ARCHIVED")
                    if (task.health != WorkspaceHealth.READY) StatusPill(task.health.name)
                    if (task.requirementLink.isNotBlank()) {
                        Spacer(Modifier.width(5.dp))
                        RequirementStatePill(controller.requirementController.stateFor(task))
                    }
                }
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
    var workDataToolMenu by remember(task.folderName) { mutableStateOf(false) }
    var showOnlyAbnormal by remember(task.folderName) { mutableStateOf(false) }
    var batchGitMode by remember(task.folderName) { mutableStateOf<WorkspaceGitBatchMode?>(null) }
    var lastBatchGitMode by remember(task.folderName) { mutableStateOf(WorkspaceGitBatchMode.PUSH) }
    var batchGitInitialSelection by remember(task.folderName) { mutableStateOf<Set<String>?>(null) }
    var batchGitResult by remember(task.folderName) { mutableStateOf<WorkspaceGitBatchResult?>(null) }
    val group = controller.config.groups.firstOrNull { it.id == task.groupId }
    val tagWorkspaces = task.services.filter { controller.canBuildTag(task, it) }
    val physicalWorkspaces = controller.physicalWorkspaces(task)
    val requirementState = controller.requirementController.stateFor(task)
    val failedTools = task.workspaceToolLaunches.filter { it.status != WorkspaceToolLaunchStatus.OPENED }
    val failedServiceIds = task.services.filter { it.health == WorkspaceHealth.FAILED }
        .map(ServiceWorkspace::groupServiceId).filter(String::isNotBlank).distinct()
    val tagOperationLoading = controller.busy && controller.activeOperation?.contains("Tag") == true
    Surface(
        modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            TaskDetailHeader(
                controller = controller,
                task = task,
                requirementState = requirementState,
                physicalWorkspaces = physicalWorkspaces,
                groupName = group?.name,
                showGroup = controller.config.groups.size > 1,
                onArchive = { confirmArchive = true },
                onDelete = { confirmDelete = true },
            )
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IconActionGroup {
                    ActionIconButton("复制任务完整路径", { controller.copyText(controller.taskPath(task), "任务路径已复制") }, Modifier.size(34.dp)) { Icon(Icons.Outlined.ContentCopy, "复制任务路径", Modifier.size(18.dp)) }
                    ActionIconButton("在任务目录打开终端", { controller.terminal(controller.taskPath(task)) }, Modifier.size(34.dp)) { Icon(Icons.Outlined.Terminal, "终端", Modifier.size(18.dp)) }
                    ActionIconButton("打开任务目录", { controller.openDirectory(controller.taskPath(task)) }, Modifier.size(34.dp)) { Icon(Icons.Outlined.FolderOpen, "打开任务目录", Modifier.size(18.dp)) }
                }
                if (tagWorkspaces.size > 1) {
                    IconActionGroup {
                        ActionIconButton("批量 Tag", { showBatchTag = true }, Modifier.size(34.dp), enabled = !controller.busy, loading = tagOperationLoading) { Icon(Icons.Outlined.Sell, "批量 Tag", Modifier.size(18.dp)) }
                    }
                }
                GitActionIconGroup(
                    enabled = !controller.busy && physicalWorkspaces.isNotEmpty(),
                    scopeLabel = "全部工作区",
                    loading = controller.busy && controller.activeOperation?.contains("全部工作区") == true,
                    onCommit = { batchGitInitialSelection = null; controller.loadBatchGitPreviews(task); batchGitMode = WorkspaceGitBatchMode.COMMIT },
                    onCommitAndPush = { batchGitInitialSelection = null; controller.loadBatchGitPreviews(task); batchGitMode = WorkspaceGitBatchMode.COMMIT_AND_PUSH },
                    onPush = { batchGitInitialSelection = null; controller.loadBatchGitPreviews(task); batchGitMode = WorkspaceGitBatchMode.PUSH },
                )
                IconActionGroup {
                    if (controller.addableServices(task).isNotEmpty()) {
                        ActionIconButton("添加服务", { showAddServices = true }, Modifier.size(34.dp)) { Icon(Icons.Outlined.Add, "添加服务", Modifier.size(18.dp)) }
                    }
                    ActionIconButton("查看分支信息", { showBranchInfo = true }, Modifier.size(34.dp)) { Icon(Icons.Outlined.AccountTree, "分支信息", Modifier.size(18.dp)) }
                    Box {
                        ActionIconButton("打开工作数据", { controller.openWorkData(task) }, Modifier.size(34.dp)) { Icon(Icons.Outlined.Folder, "工作数据", Modifier.size(18.dp)) }
                        if (temporaryDevelopmentToolSelectionEnabled(controller.config)) {
                            AwmDropdownMenu(workDataToolMenu, onDismissRequest = { workDataToolMenu = false }) {
                                controller.configuredDevelopmentTools().forEach { type ->
                                    DropdownMenuItem(text = { Text(type.displayName) }, onClick = { workDataToolMenu = false; controller.openWorkData(task, type) })
                                }
                            }
                        }
                    }
                    if (temporaryDevelopmentToolSelectionEnabled(controller.config)) {
                        ActionIconButton("选择工作数据开发工具", { workDataToolMenu = true }, Modifier.size(30.dp)) { Icon(Icons.Outlined.KeyboardArrowDown, "选择开发工具", Modifier.size(17.dp)) }
                    }
                }
            }
            val abnormalWorkspaces = physicalWorkspaces.filter {
                controller.gitHealth(it)?.state in setOf(WorkspaceGitHealthState.MISSING, WorkspaceGitHealthState.FAILED)
            }
            val unpushed = physicalWorkspaces.count {
                controller.gitHealth(it)?.pushState in setOf(LocalPushState.AHEAD, LocalPushState.REMOTE_BRANCH_MISSING, LocalPushState.NO_UPSTREAM)
            }
            Surface(
                Modifier.fillMaxWidth().clickable(enabled = abnormalWorkspaces.isNotEmpty()) { showOnlyAbnormal = !showOnlyAbnormal },
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    "${physicalWorkspaces.size} 个工作区 · ${physicalWorkspaces.size - abnormalWorkspaces.size} 个正常 · ${abnormalWorkspaces.size} 个异常 · $unpushed 个待推送" +
                        if (showOnlyAbnormal) " · 正在仅显示异常" else "",
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (abnormalWorkspaces.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            (if (showOnlyAbnormal) abnormalWorkspaces else physicalWorkspaces).forEach { WorkspaceCard(controller, task, it) }
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
    if (showAddServices) AddTaskServicesDialog(controller, task, onDismiss = { showAddServices = false }) { ids, reuseKeys, baseOverrides ->
        controller.addServices(task, ids, reuseKeys, baseOverrides) { showAddServices = false }
    }
    if (showBatchTag) BatchTagDialog(tagWorkspaces, onDismiss = { showBatchTag = false }) { selected ->
        controller.deliveryController.buildBatch(task, selected) { showBatchTag = false }
    }
    if (showBranchInfo) BranchInfoDialog(controller.branchInfo(task), onDismiss = { showBranchInfo = false }) {
        controller.copyText(controller.branchInfo(task), "分支信息已复制")
    }
    batchGitMode?.let { mode ->
        BatchGitDialog(
            controller = controller,
            task = task,
            workspaces = physicalWorkspaces,
            mode = mode,
            initialSelection = batchGitInitialSelection,
            onDismiss = { if (!controller.busy) batchGitMode = null },
            onExecute = { selectedKeys, messages, fingerprints ->
                controller.batchGit(task, mode, selectedKeys, messages, fingerprints) { result ->
                    lastBatchGitMode = mode
                    batchGitMode = null
                    batchGitResult = result
                }
            },
        )
    }
    batchGitResult?.let { result ->
        BatchGitResultDialog(
            controller,
            result,
            onDismiss = { batchGitResult = null },
            onRetryFailed = { failed ->
                batchGitResult = null
                batchGitInitialSelection = failed
                controller.loadBatchGitPreviews(task)
                batchGitMode = lastBatchGitMode
            },
        )
    }
    controller.workspaceRepairPreview?.let { preview ->
        WorkspaceRepairDialog(
            preview = preview,
            busy = controller.busy,
            onDismiss = controller::clearWorkspaceRepairPreview,
            onRepair = { confirmation -> controller.repairWorkspace(task, preview, confirmation) },
        )
    }
    controller.workspaceRepairResult?.let { result ->
        AlertDialog(
            onDismissRequest = controller::clearWorkspaceRepairResult,
            title = { Text("工作区修复完成") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(result.serviceName, fontWeight = FontWeight.SemiBold)
                    Text(result.message)
                    result.backupPath?.let { path ->
                        Text("原目录备份：", style = MaterialTheme.typography.labelMedium)
                        SelectionContainer { Text(path, style = MaterialTheme.typography.bodySmall) }
                        OutlinedButton(onClick = { controller.openDirectory(path) }) { Text("打开备份目录") }
                    }
                    if (result.warnings.isNotEmpty()) Text(result.warnings.joinToString("\n"), color = WarningAmber)
                }
            },
            confirmButton = { Button(onClick = controller::clearWorkspaceRepairResult) { Text("关闭") } },
        )
    }
}

@Composable
private fun WorkspaceRepairDialog(
    preview: WorkspaceRepairPreview,
    busy: Boolean,
    onDismiss: () -> Unit,
    onRepair: (WorkspaceRepairConfirmation) -> Unit,
) {
    var remoteConfirmed by remember(preview.stateFingerprint) { mutableStateOf(false) }
    var sharedConfirmed by remember(preview.stateFingerprint) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (preview.canRepair) "确认修复工作区" else "工作区无法自动修复") },
        text = {
            Column(
                Modifier.widthIn(min = 560.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(preview.serviceName, fontWeight = FontWeight.SemiBold)
                WorkspaceProblemPill(workspaceIssueLabel(preview.issue, preview.actualBranch, preview.expectedBranch))
                SelectionContainer { Text(preview.workspacePath, style = MaterialTheme.typography.bodySmall) }
                Text(preview.message, color = if (preview.canRepair) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                preview.backupPath?.let {
                    Text("原目录会保留为备份：", fontWeight = FontWeight.SemiBold)
                    SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                if (preview.steps.isNotEmpty()) {
                    HorizontalDivider()
                    preview.steps.forEachIndexed { index, step -> Text("${index + 1}. $step") }
                }
                if (preview.requiresRemoteReuseConfirmation) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(remoteConfirmed, { remoteConfirmed = it })
                        Text("确认复用远程分支 ${preview.remote}/${preview.expectedBranch}")
                    }
                }
                if (preview.requiresSharedBranchConfirmation) {
                    Text("以下 Worktree 已检出同一分支：", color = MaterialTheme.colorScheme.error)
                    preview.occupiedWorktreePaths.forEach { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(sharedConfirmed, { sharedConfirmed = it })
                        Text("我了解多个 Worktree 将共享同一分支", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            if (preview.canRepair) Button(
                onClick = { onRepair(WorkspaceRepairConfirmation(remoteConfirmed, sharedConfirmed)) },
                enabled = !busy && (!preview.requiresRemoteReuseConfirmation || remoteConfirmed) &&
                    (!preview.requiresSharedBranchConfirmation || sharedConfirmed),
            ) { Text("确认修复") } else Button(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = if (preview.canRepair) ({ TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } }) else null,
    )
}

internal enum class WorkspaceCardLayout { SIDE_BY_SIDE }

internal enum class WorkspaceStatusPlacement { SECOND_ROW, THIRD_ROW }

internal data class WorkspaceBranchRowAllocation(
    val branchWidth: Int,
    val copyX: Int,
    val statusX: Int,
)

internal fun workspaceCardLayout(availableWidthDp: Float): WorkspaceCardLayout =
    WorkspaceCardLayout.SIDE_BY_SIDE

internal fun workspaceStatusPlacement(health: WorkspaceGitHealth?): WorkspaceStatusPlacement =
    if (health?.state in setOf(WorkspaceGitHealthState.MISSING, WorkspaceGitHealthState.FAILED)) {
        WorkspaceStatusPlacement.THIRD_ROW
    } else {
        WorkspaceStatusPlacement.SECOND_ROW
    }

internal fun workspaceBranchRowAllocation(
    availableWidth: Int,
    naturalBranchWidth: Int,
    copyWidth: Int,
    statusWidth: Int,
    gapWidth: Int,
): WorkspaceBranchRowAllocation {
    val branchCopyGap = if (naturalBranchWidth > 0 && copyWidth > 0) gapWidth else 0
    val copyStatusGap = if (copyWidth > 0 && statusWidth > 0) gapWidth else 0
    val maximumBranchWidth = (availableWidth - copyWidth - statusWidth - branchCopyGap - copyStatusGap).coerceAtLeast(0)
    val branchWidth = naturalBranchWidth.coerceIn(0, maximumBranchWidth)
    val copyX = branchWidth + if (branchWidth > 0 && copyWidth > 0) gapWidth else 0
    val statusX = copyX + copyWidth + if (copyWidth > 0 && statusWidth > 0) gapWidth else 0
    return WorkspaceBranchRowAllocation(branchWidth, copyX, statusX)
}

@Composable
private fun WorkspaceCard(controller: DesktopApplication, task: TaskManifest, workspace: ServiceWorkspace) {
    val health = controller.gitHealth(workspace)
    val displayedBranch = health?.actualBranch?.takeIf(String::isNotBlank) ?: workspace.branch
    val branchVerified = !health?.actualBranch.isNullOrBlank()
    var commitMode by remember { mutableStateOf<String?>(null) }
    var commitMessage by remember(task, workspace) { mutableStateOf(controller.defaultCommitMessage(task, workspace)) }
    var toolMenu by remember { mutableStateOf(false) }
    OutlinedCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(Modifier.align(Alignment.Top), color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(11.dp)) {
                Icon(
                    if (workspace.strategy == WorkspaceStrategy.STANDARD_WORKTREE) Icons.Outlined.AccountTree else Icons.Outlined.ContentCopy,
                    null,
                    Modifier.padding(9.dp).size(19.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(12.dp))
            WorkspaceCardSummary(
                controller = controller,
                task = task,
                workspace = workspace,
                health = health,
                displayedBranch = displayedBranch,
                branchVerified = branchVerified,
                modifier = Modifier.weight(1f).align(Alignment.Top),
            )
            Spacer(Modifier.width(12.dp))
            WorkspaceCardActions(
                controller = controller,
                task = task,
                workspace = workspace,
                toolMenu = toolMenu,
                onToolMenuChange = { toolMenu = it },
                onCommit = { controller.loadBatchGitPreviews(task); commitMessage = controller.defaultCommitMessage(task, workspace); commitMode = "commit" },
                onCommitAndPush = { controller.loadBatchGitPreviews(task); commitMessage = controller.defaultCommitMessage(task, workspace); commitMode = "commitPush" },
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
    commitMode?.let { mode ->
        val preview = (controller.batchGitPreviewState as? BatchGitPreviewState.Loaded)?.previews?.get(controller.workspaceKey(workspace))
        AlertDialog(
            onDismissRequest = { commitMode = null },
            title = { Text(if (mode == "commitPush") "提交并推送" else "提交") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(commitMessage, { commitMessage = it }, Modifier.fillMaxWidth(), label = { Text("提交信息") }, minLines = 3)
                    when (val previewState = controller.batchGitPreviewState) {
                        BatchGitPreviewState.Idle, BatchGitPreviewState.Loading -> Text("正在读取变更预览…")
                        is BatchGitPreviewState.Failed -> SelectionContainer { Text(previewState.message, color = MaterialTheme.colorScheme.error) }
                        is BatchGitPreviewState.Loaded -> if (preview != null) {
                            Text("将提交 ${preview.files.size} 个变更文件", style = MaterialTheme.typography.labelMedium)
                            SelectionContainer { Text(preview.files.take(20).joinToString("\n"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
                            if (preview.diffStat.isNotBlank()) SelectionContainer { Text(preview.diffStat, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (controller.commitWorkspace(task, workspace, commitMessage, pushAfter = mode == "commitPush", expectedFingerprint = preview?.fingerprint)) commitMode = null
                }, enabled = commitMessage.isNotBlank() && preview != null && !controller.busy) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { commitMode = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun WorkspaceCardSummary(
    controller: DesktopApplication,
    task: TaskManifest,
    workspace: ServiceWorkspace,
    health: com.snowball.awm.core.WorkspaceGitHealth?,
    displayedBranch: String,
    branchVerified: Boolean,
    modifier: Modifier,
) {
    val statusPlacement = workspaceStatusPlacement(health)
    Column(modifier) {
        Row(Modifier.heightIn(min = 30.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                workspace.moduleName.ifBlank { workspace.serviceName },
                Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MetaPill(workspace.strategy.displayName)
            if (workspace.health != WorkspaceHealth.READY) StatusPill(workspace.health.name)
        }
        WorkspaceBranchStatusRow(
            modifier = Modifier.fillMaxWidth().heightIn(min = 30.dp),
            branch = {
                SelectionContainer {
                    Text(
                        if (branchVerified) displayedBranch else "$displayedBranch（未验证）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            copy = {
                ActionIconButton(
                    "复制分支名",
                    { controller.copyText(displayedBranch, "分支已复制") },
                    Modifier.size(28.dp),
                ) { Icon(Icons.Outlined.ContentCopy, "复制分支名", Modifier.size(14.dp)) }
            },
            status = {
                if (statusPlacement == WorkspaceStatusPlacement.SECOND_ROW) {
                    if (health == null || health.state == WorkspaceGitHealthState.CHECKING) MetaPill("检查中")
                    if (health?.state == WorkspaceGitHealthState.READY) {
                        MetaPill(if (health.dirtyFileCount == 0) "无未提交" else "${health.dirtyFileCount} 个文件未提交")
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
            },
        )
        if (statusPlacement == WorkspaceStatusPlacement.THIRD_ROW && health != null) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 30.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f, fill = false)) {
                    WorkspaceProblemPill(workspaceIssueLabel(health))
                }
                ActionIconButton(
                    health.message ?: "查看修复方案",
                    { controller.inspectWorkspaceRepair(task, workspace) },
                    Modifier.size(30.dp),
                    enabled = !controller.busy,
                ) { Icon(Icons.Outlined.Build, "修复工作区", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error) }
            }
        }
        workspaceIssueDetail(health)?.let { detail ->
            SelectionContainer {
                Text(detail, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (workspace.warnings.isNotEmpty()) {
            Text(workspace.warnings.joinToString("\n"), color = WarningAmber, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun WorkspaceBranchStatusRow(
    modifier: Modifier,
    branch: @Composable () -> Unit,
    copy: @Composable () -> Unit,
    status: @Composable () -> Unit,
) {
    val gapWidth = with(LocalDensity.current) { 7.dp.roundToPx() }
    val minimumHeight = with(LocalDensity.current) { 30.dp.roundToPx() }
    Layout(
        modifier = modifier,
        content = {
            Box { branch() }
            Box { copy() }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) { status() }
        },
    ) { measurables, constraints ->
        val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val copyPlaceable = measurables[1].measure(looseConstraints)
        val statusPlaceable = measurables[2].measure(looseConstraints)
        val reservedGaps = gapWidth + if (statusPlaceable.width > 0) gapWidth else 0
        val maximumBranchWidth = (
            constraints.maxWidth - copyPlaceable.width - statusPlaceable.width - reservedGaps
        ).coerceAtLeast(0)
        val branchPlaceable = measurables[0].measure(looseConstraints.copy(maxWidth = maximumBranchWidth))
        val allocation = workspaceBranchRowAllocation(
            availableWidth = constraints.maxWidth,
            naturalBranchWidth = branchPlaceable.width,
            copyWidth = copyPlaceable.width,
            statusWidth = statusPlaceable.width,
            gapWidth = gapWidth,
        )
        val height = maxOf(minimumHeight, branchPlaceable.height, copyPlaceable.height, statusPlaceable.height)
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(constraints.maxWidth, height) {
            branchPlaceable.placeRelative(0, (height - branchPlaceable.height) / 2)
            copyPlaceable.placeRelative(allocation.copyX, (height - copyPlaceable.height) / 2)
            statusPlaceable.placeRelative(allocation.statusX, (height - statusPlaceable.height) / 2)
        }
    }
}

@Composable
private fun WorkspaceCardActions(
    controller: DesktopApplication,
    task: TaskManifest,
    workspace: ServiceWorkspace,
    toolMenu: Boolean,
    onToolMenuChange: (Boolean) -> Unit,
    onCommit: () -> Unit,
    onCommitAndPush: () -> Unit,
    modifier: Modifier,
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconActionGroup {
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
        GitActionIconGroup(
            enabled = !controller.busy,
            scopeLabel = workspace.moduleName.ifBlank { workspace.serviceName },
            loading = controller.busy && controller.activeOperation?.contains(workspace.moduleName.ifBlank { workspace.serviceName }) == true,
            onCommit = onCommit,
            onCommitAndPush = onCommitAndPush,
            onPush = { controller.pushWorkspace(task, workspace) },
        )
        if (controller.canBuildTag(task, workspace)) {
            IconActionGroup {
                ActionIconButton("构建 Tag", { controller.deliveryController.build(task, workspace) }, Modifier.size(34.dp), enabled = !controller.busy, loading = controller.busy && controller.activeOperation?.contains(workspace.moduleName.ifBlank { workspace.serviceName }) == true && controller.activeOperation?.contains("Tag") == true) {
                    Icon(Icons.Outlined.Sell, "Tag", Modifier.size(18.dp))
                }
            }
        }
        IconActionGroup {
            ActionIconButton(
                "使用 ${workspace.developmentTool.displayName} 打开",
                { controller.openWorkspace(workspace) },
                Modifier.size(34.dp),
            ) { Icon(Icons.Outlined.Code, "打开开发工具", Modifier.size(18.dp)) }
            if (temporaryDevelopmentToolSelectionEnabled(controller.config)) {
                Box {
                    ActionIconButton("选择开发工具", { onToolMenuChange(true) }, Modifier.size(30.dp)) {
                        Icon(Icons.Outlined.KeyboardArrowDown, "选择开发工具", Modifier.size(17.dp))
                    }
                    AwmDropdownMenu(toolMenu, onDismissRequest = { onToolMenuChange(false) }) {
                        controller.configuredDevelopmentTools().forEach { type ->
                            DropdownMenuItem(text = { Text(type.displayName) }, onClick = { onToolMenuChange(false); controller.openWorkspace(workspace, type) })
                        }
                    }
                }
            }
        }
        if (workspace.health == WorkspaceHealth.FAILED && workspace.groupServiceId.isNotBlank()) {
            OutlinedButton(onClick = { controller.retryFailedServices(task, listOf(workspace.groupServiceId)) }, enabled = !controller.busy) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("重试")
            }
        }
    }
}

@Composable
private fun IconActionGroup(content: @Composable RowScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.padding(horizontal = 3.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

private fun workspaceIssueLabel(health: com.snowball.awm.core.WorkspaceGitHealth): String =
    workspaceIssueLabel(health.issue, health.actualBranch, health.expectedBranch)

internal fun workspaceIssueDetail(health: WorkspaceGitHealth?): String? = health
    ?.takeIf {
        it.state in setOf(WorkspaceGitHealthState.MISSING, WorkspaceGitHealthState.FAILED) &&
            it.issue != WorkspaceGitIssue.BRANCH_MISMATCH
    }
    ?.message
    ?.takeIf(String::isNotBlank)

private fun workspaceIssueLabel(issue: WorkspaceGitIssue, actual: String?, expected: String?): String = when (issue) {
    WorkspaceGitIssue.NONE -> "正常"
    WorkspaceGitIssue.MISSING -> "工作区不存在"
    WorkspaceGitIssue.NOT_GIT -> "不是有效的 Git 工作区"
    WorkspaceGitIssue.IDENTITY_MISMATCH -> "Git 仓库身份不匹配"
    WorkspaceGitIssue.BRANCH_MISMATCH -> "分支不一致：${actual.orEmpty()} → ${expected.orEmpty()}"
    WorkspaceGitIssue.DETACHED_HEAD -> "Detached HEAD"
    WorkspaceGitIssue.OPERATION_IN_PROGRESS -> "存在进行中的 Git 操作"
    WorkspaceGitIssue.INSPECTION_FAILED -> "Git 状态检查失败"
}

@Composable
private fun GitActionIconGroup(
    enabled: Boolean,
    scopeLabel: String,
    loading: Boolean = false,
    onCommit: () -> Unit,
    onCommitAndPush: () -> Unit,
    onPush: () -> Unit,
) {
    IconActionGroup {
        ActionIconButton("提交 $scopeLabel", onCommit, Modifier.size(34.dp), enabled, loading) {
            Icon(Icons.Outlined.Commit, "提交", Modifier.size(18.dp))
        }
        ActionIconButton("提交并推送 $scopeLabel", onCommitAndPush, Modifier.size(34.dp), enabled, loading) {
            Icon(Icons.Outlined.Publish, "提交并推送", Modifier.size(18.dp))
        }
        ActionIconButton("推送 $scopeLabel", onPush, Modifier.size(34.dp), enabled, loading) {
            Icon(Icons.Outlined.CloudUpload, "推送", Modifier.size(18.dp))
        }
    }
}

internal enum class BatchCommitDisposition {
    NOT_APPLICABLE,
    LOADING,
    MESSAGE_REQUIRED,
    NO_CHANGES,
    PUSH_ONLY,
}

internal fun batchCommitDisposition(
    mode: WorkspaceGitBatchMode,
    preview: com.snowball.awm.core.WorkspaceGitChangePreview?,
): BatchCommitDisposition = when {
    mode == WorkspaceGitBatchMode.PUSH -> BatchCommitDisposition.NOT_APPLICABLE
    preview == null -> BatchCommitDisposition.LOADING
    preview.files.isNotEmpty() -> BatchCommitDisposition.MESSAGE_REQUIRED
    mode == WorkspaceGitBatchMode.COMMIT_AND_PUSH -> BatchCommitDisposition.PUSH_ONLY
    else -> BatchCommitDisposition.NO_CHANGES
}

internal fun batchCommitMessagesValid(
    mode: WorkspaceGitBatchMode,
    selectedKeys: Set<String>,
    previews: Map<String, com.snowball.awm.core.WorkspaceGitChangePreview>,
    messages: Map<String, String>,
): Boolean {
    if (mode == WorkspaceGitBatchMode.PUSH) return true
    return selectedKeys.all { key ->
        when (batchCommitDisposition(mode, previews[key])) {
            BatchCommitDisposition.MESSAGE_REQUIRED -> !messages[key].isNullOrBlank()
            BatchCommitDisposition.NO_CHANGES, BatchCommitDisposition.PUSH_ONLY -> true
            BatchCommitDisposition.NOT_APPLICABLE, BatchCommitDisposition.LOADING -> false
        }
    }
}

@Composable
private fun BatchGitDialog(
    controller: DesktopApplication,
    task: TaskManifest,
    workspaces: List<ServiceWorkspace>,
    mode: WorkspaceGitBatchMode,
    initialSelection: Set<String>? = null,
    onDismiss: () -> Unit,
    onExecute: (Set<String>, Map<String, String>, Map<String, String>) -> Unit,
) {
    var selectedKeys by remember(task.taskDirectoryName, mode, workspaces) {
        mutableStateOf(initialSelection ?: workspaces.map(controller::workspaceKey).toSet())
    }
    val messages = remember(task.taskDirectoryName, mode) {
        mutableStateMapOf<String, String>().apply {
            workspaces.forEach { workspace ->
                put(controller.workspaceKey(workspace), controller.defaultCommitMessage(task, workspace))
            }
        }
    }
    val previews = (controller.batchGitPreviewState as? BatchGitPreviewState.Loaded)?.previews.orEmpty()
    val previewReady = controller.batchGitPreviewState is BatchGitPreviewState.Loaded
    val messagesValid = batchCommitMessagesValid(mode, selectedKeys, previews, messages)
    val selectedDispositions = selectedKeys.map { batchCommitDisposition(mode, previews[it]) }
    val commitAndPushOnlyPushes = mode == WorkspaceGitBatchMode.COMMIT_AND_PUSH &&
        selectedDispositions.isNotEmpty() && selectedDispositions.all { it == BatchCommitDisposition.PUSH_ONLY }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (mode) {
                    WorkspaceGitBatchMode.COMMIT -> "选择要提交的工作区"
                    WorkspaceGitBatchMode.PUSH -> "选择要推送的工作区"
                    WorkspaceGitBatchMode.COMMIT_AND_PUSH -> "选择要提交并推送的工作区"
                },
            )
        },
        text = {
            Column(
                Modifier.widthIn(min = 620.dp, max = 820.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("确认后仅预检并操作所选物理工作区。", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { selectedKeys = workspaces.map(controller::workspaceKey).toSet() }) { Text("全选") }
                    TextButton(onClick = { selectedKeys = emptySet() }) { Text("清空") }
                }
                workspaces.forEach { workspace ->
                    val key = controller.workspaceKey(workspace)
                    val health = controller.gitHealth(workspace)
                    val preview = previews[key]
                    val disposition = batchCommitDisposition(mode, preview)
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = key in selectedKeys,
                                    onCheckedChange = { checked ->
                                        selectedKeys = if (checked) selectedKeys + key else selectedKeys - key
                                    },
                                    enabled = !controller.busy,
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(workspace.moduleName.ifBlank { workspace.serviceName }, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${health?.actualBranch ?: workspace.branch} · ${workspace.pushRemote} · ${workspace.worktreePath}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (key in selectedKeys) when (disposition) {
                                    BatchCommitDisposition.PUSH_ONLY -> MetaPill("无本地变更，仅推送已有提交")
                                    BatchCommitDisposition.NO_CHANGES -> MetaPill("无本地变更，跳过提交")
                                    BatchCommitDisposition.LOADING -> MetaPill("正在确认变更")
                                    else -> Unit
                                }
                            }
                            if (key in selectedKeys && disposition == BatchCommitDisposition.MESSAGE_REQUIRED) {
                                OutlinedTextField(
                                    value = messages[key].orEmpty(),
                                    onValueChange = { messages[key] = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("提交信息") },
                                    minLines = 2,
                                )
                            }
                            if (key in selectedKeys && preview != null && preview.files.isNotEmpty()) {
                                Text("变更文件 ${preview.files.size} 个", style = MaterialTheme.typography.labelMedium)
                                SelectionContainer {
                                    Text(
                                        preview.files.take(20).joinToString("\n") + if (preview.files.size > 20) "\n…还有 ${preview.files.size - 20} 个" else "",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                                if (preview.diffStat.isNotBlank()) SelectionContainer {
                                    Text(preview.diffStat, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                                }
                            }
                        }
                    }
                }
                if (commitAndPushOnlyPushes) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(
                            "所选工作区都没有未提交文件；本次不会创建新提交，只会推送已有的未推送提交。",
                            Modifier.fillMaxWidth().padding(11.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                when (val previewState = controller.batchGitPreviewState) {
                    BatchGitPreviewState.Idle, BatchGitPreviewState.Loading -> Text("正在读取 Git 变更预览…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    is BatchGitPreviewState.Failed -> SelectionContainer { Text(previewState.message, color = MaterialTheme.colorScheme.error) }
                    is BatchGitPreviewState.Loaded -> Unit
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onExecute(selectedKeys, messages.toMap(), previews.mapValues { it.value.fingerprint }) },
                enabled = !controller.busy && previewReady && selectedKeys.isNotEmpty() && messagesValid,
            ) {
                Text(if (commitAndPushOnlyPushes) "确认仅推送" else "确认执行")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !controller.busy) { Text("取消") } },
    )
}

@Composable
private fun BatchGitResultDialog(controller: DesktopApplication, result: WorkspaceGitBatchResult, onDismiss: () -> Unit, onRetryFailed: (Set<String>) -> Unit) {
    val failedKeys = result.items.filter {
        it.commitState == WorkspaceGitStepState.FAILED || it.pushState == WorkspaceGitStepState.FAILED
    }.map { it.workspacePath }.toSet()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量 Git 操作结果") },
        text = {
            Column(
                Modifier.widthIn(min = 620.dp, max = 820.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                result.items.forEach { item ->
                    val failed = item.commitState == WorkspaceGitStepState.FAILED || item.pushState == WorkspaceGitStepState.FAILED
                    val skipped = item.commitState == WorkspaceGitStepState.SKIPPED && item.pushState == WorkspaceGitStepState.NOT_RUN
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(item.serviceName, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                StatusPill(if (failed) "FAILED" else if (skipped) "SKIPPED" else "SUCCESS")
                            }
                            Text(item.branch, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SelectionContainer { Text(item.message, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                            if (failed) TextButton(onClick = { controller.openDirectory(item.workspacePath) }) { Text("打开失败工作区") }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("关闭") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    controller.copyText(
                        result.items.joinToString("\n") { "${it.serviceName} [${it.branch}] ${it.message}" },
                        "Git 操作结果已复制",
                    )
                }) { Text("复制全部结果") }
                if (failedKeys.isNotEmpty()) OutlinedButton(onClick = { onRetryFailed(failedKeys) }) { Text("仅重试失败项") }
            }
        },
    )
}
