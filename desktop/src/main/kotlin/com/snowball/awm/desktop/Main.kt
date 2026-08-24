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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import com.snowball.awm.core.error
import androidx.compose.ui.window.rememberWindowState
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.snowball.awm.core.AgentConflictResolution
import com.snowball.awm.core.BootstrapConfig
import com.snowball.awm.core.BootstrapPresets
import com.snowball.awm.core.GroupServiceConfig
import com.snowball.awm.core.BranchPrefixResolver
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
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.UUID

fun main() {
    val fatalEvents = com.snowball.awm.core.JsonlEventSink()
    val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        fatalEvents.error(
            event = "application.uncaught",
            message = error.stackTraceToString(),
            metadata = mapOf("thread" to thread.name),
        )
        previousHandler?.uncaughtException(thread, error) ?: error.printStackTrace()
    }
    FileKit.init(appId = "com.snowball.awm")
    application {
        val controller = remember { DesktopApplication() }
        val windowPreferences = remember { WindowPreferences.load() }
        val state = rememberWindowState(
            width = windowPreferences.width.dp,
            height = windowPreferences.height.dp,
            placement = if (windowPreferences.maximized) WindowPlacement.Maximized else WindowPlacement.Floating,
        )
        Window(
            onCloseRequest = {
                if (controller.hasActiveOperations) {
                    controller.showError(IllegalStateException("操作正在执行，完成前不能关闭应用"))
                } else {
                    controller.close()
                    exitApplication()
                }
            },
            title = "Agent Workspace Manager 0.9.11",
            state = state,
            icon = painterResource(Res.drawable.app_icon),
        ) {
            DisposableEffect(window) {
                fun nativeScale(): Pair<Double, Double> = window.graphicsConfiguration.defaultTransform.let { it.scaleX to it.scaleY }
                val listener = object : WindowAdapter() {
                    override fun windowGainedFocus(event: WindowEvent?) = controller.onWindowFocused()
                }
                window.addWindowFocusListener(listener)
                onDispose {
                    val maximized = window.extendedState and java.awt.Frame.MAXIMIZED_BOTH != 0
                    val (scaleX, scaleY) = nativeScale()
                    WindowPreferences.savePhysicalWindow(window.width, window.height, maximized, scaleX, scaleY)
                    window.removeWindowFocusListener(listener)
                }
            }
            AwmTheme(controller.config.theme) { AgentWorkspaceApp(controller) }
        }
    }
}

@Composable
private fun AgentWorkspaceApp(controller: DesktopApplication) {
    val snackbar = remember { SnackbarHostState() }
    var showCreate by remember { mutableStateOf(false) }

    LaunchedEffect(controller.statusMessage) {
        val message = controller.statusMessage
        if (message != null) {
            snackbar.showSnackbar(
                message,
                withDismissAction = true,
                duration = SnackbarDuration.Short,
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
                            NavigationItem.TAG -> TagScreen(controller)
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
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                controller.activeOperation ?: "正在处理",
                                Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.inverseOnSurface,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            if (controller.activeOperationCancellable) {
                                TextButton(onClick = controller::cancelActiveOperation) {
                                    Text("取消", color = MaterialTheme.colorScheme.inversePrimary)
                                }
                            }
                        }
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateTaskDialog(controller, onDismiss = { showCreate = false }) { name, branch, group, services, link, notes, tools, reuseKeys, selections ->
            controller.taskController.create(name, branch, group, services, link, notes, tools, reuseKeys, selections) {
                showCreate = false
            }
        }
    }
    controller.errorMessage?.let { error ->
        TagResultDialog(
            title = "操作失败",
            content = error,
            onDismiss = controller::dismissMessages,
            onCopy = { controller.copyText(error, "错误详情已复制") },
        )
    }
    controller.tagResult?.let { result ->
        val output = TagOutputFormatter.format(controller.selectedTask?.requirementLink.orEmpty(), listOf(result), includeFailures = true)
        TagResultDialog(
            title = "Tag 构建结果",
            content = output,
            onDismiss = controller::clearTagResult,
            onCopy = { controller.copyText(output, "构建结果已复制") },
        )
    }
    controller.batchTagResults?.let { results ->
        val successful = results.filter { it.state.name == "SUCCESS" && !it.tag.isNullOrBlank() }
        val successOutput = TagOutputFormatter.format(controller.selectedTask?.requirementLink.orEmpty(), successful, includeFailures = false)
        AlertDialog(
            onDismissRequest = controller::clearBatchTagResults,
            title = { Text("批量 Tag 构建结果") },
            text = {
                SelectionContainer {
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                Text("AWM", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "工作空间",
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NavigationItem.entries.filter { it != NavigationItem.TAG || controller.showsTagNavigation }.forEach { item ->
                val selectedItem = item == controller.navigation
                val icon = when (item) {
                    NavigationItem.TASKS -> Icons.Outlined.Workspaces
                    NavigationItem.ARCHIVED -> Icons.Outlined.Archive
                    NavigationItem.SERVICES -> Icons.Outlined.Dns
                    NavigationItem.TAG -> Icons.Outlined.Sell
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
    NavigationItem.TASKS -> controller.tasks.count { it.lifecycleStatus != TaskLifecycleStatus.ARCHIVED }
    NavigationItem.ARCHIVED -> controller.tasks.count { it.lifecycleStatus == TaskLifecycleStatus.ARCHIVED }
    NavigationItem.SERVICES -> controller.config.groups.sumOf { it.services.size }
    NavigationItem.TAG, NavigationItem.SETTINGS -> null
}

private val NavigationItem.pageDescription: String
    get() = when (this) {
        NavigationItem.TASKS -> "集中查看任务状态、工作区与任务说明"
        NavigationItem.ARCHIVED -> "查看已归档任务并按需恢复"
        NavigationItem.SERVICES -> "按组管理仓库、模块和工作区策略"
        NavigationItem.TAG -> "从已启用的工作区安全构建测试标签"
        NavigationItem.SETTINGS -> "管理本地目录、组、Agent 说明与开发工具"
    }
