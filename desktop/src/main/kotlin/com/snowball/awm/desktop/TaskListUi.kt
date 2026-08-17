package com.snowball.awm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.snowball.awm.core.TaskLifecycleStatus
import com.snowball.awm.core.TaskManifest
import com.snowball.awm.core.WorkspaceHealth
import com.snowball.awm.core.health

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
