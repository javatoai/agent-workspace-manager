package com.snowball.taskwt.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.snowball.taskwt.desktop.generated.resources.Res
import com.snowball.taskwt.desktop.generated.resources.app_icon
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.snowball.taskwt.core.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.Dimension
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.UIManager

fun main() = application {
    val controller = remember { AppController() }
    val windowState = rememberWindowState(width = 1400.dp, height = 900.dp)
    Window(
        onCloseRequest = ::exitApplication,
        title = "Task Worktree Manager",
        state = windowState,
        icon = painterResource(Res.drawable.app_icon),
    ) {
        LaunchedEffect(Unit) {
            window.minimumSize = Dimension(1180, 720)
        }
        TaskWtTheme(controller.config.theme) {
            TaskWorktreeApp(controller)
        }
    }
}

@Composable
private fun TaskWorktreeApp(controller: AppController) {
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateTask by remember { mutableStateOf(false) }

    LaunchedEffect(controller.statusMessage, controller.errorMessage) {
        val message = controller.errorMessage ?: controller.statusMessage
        if (message != null) {
            snackbarHostState.showSnackbar(
                message = message,
                withDismissAction = true,
                duration = if (controller.errorMessage != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            controller.dismissMessages()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            AppSidebar(
                selected = controller.navigation,
                onSelected = { controller.navigation = it },
                modifier = Modifier.width(232.dp).fillMaxHeight(),
            )
            Column(Modifier.weight(1f).fillMaxHeight()) {
                AppTopBar(
                    navigation = controller.navigation,
                    onRefresh = controller::refresh,
                    onCreateTask = { showCreateTask = true },
                    onThemeChange = controller::setTheme,
                    theme = controller.config.theme,
                )
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (controller.navigation) {
                        NavigationItem.DASHBOARD -> DashboardScreen(controller)
                        NavigationItem.TASKS -> TasksScreen(controller)
                        NavigationItem.SERVICES -> ServicesScreen(controller)
                        NavigationItem.UAT -> UatScreen(controller)
                        NavigationItem.SETTINGS -> SettingsScreen(controller)
                    }
                    if (controller.busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }

    if (controller.needsOnboarding) {
        OnboardingDialog(controller)
    }
    if (showCreateTask) {
        CreateTaskDialog(
            repositories = controller.repositories.filter {
                controller.config.services[it.id]?.enabled != false
            },
            onDismiss = { showCreateTask = false },
            onCreate = { taskKey, branch, services ->
                controller.createTask(taskKey, branch, services)
                showCreateTask = false
            },
        )
    }
    controller.batchSelectionTask?.let { task ->
        BatchServiceSelectionDialog(
            task = task,
            onDismiss = controller::clearBatchSelection,
            onConfirm = { repositoryIds -> controller.preflightTags(task, repositoryIds) },
        )
    }
    controller.batchTagPreview?.let { preview ->
        BatchTagPreflightDialog(
            preflight = preview,
            onDismiss = controller::clearBatchTagPreview,
            onConfirm = { controller.buildTags(preview) },
        )
    }
    controller.batchTagResults?.let { results ->
        BatchTagResultDialog(
            results = results,
            onCopy = {
                val text = results
                    .filter { it.state == TagOperationState.SUCCESS }
                    .joinToString(System.lineSeparator()) { it.message.orEmpty() }
                controller.copyPathText(text)
            },
            onDismiss = controller::clearBatchTagResults,
        )
    }
    controller.tagPreview?.let { preview ->
        TagPreflightDialog(
            preview = preview,
            onDismiss = controller::clearTagPreview,
            onConfirm = {
                val task = controller.tasks.firstOrNull { it.taskKey == preview.taskKey }
                val repositoryId = task?.services
                    ?.firstOrNull { it.serviceName == preview.serviceName }
                    ?.repositoryId
                if (task != null && repositoryId != null) {
                    controller.buildTag(task, repositoryId)
                }
            },
        )
    }
    controller.tagResult?.let { result ->
        TagResultDialog(
            result = result,
            onCopy = { result.message?.let(controller::copyPathText) },
            onDismiss = controller::clearTagResult,
        )
    }
}

private fun AppController.copyPathText(text: String) {
    java.awt.Toolkit.getDefaultToolkit().systemClipboard
        .setContents(java.awt.datatransfer.StringSelection(text), null)
}

@Composable
private fun AppSidebar(
    selected: NavigationItem,
    onSelected: (NavigationItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(4.dp, 8.dp, 4.dp, 26.dp),
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(BrandBlue),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.AccountTree,
                        contentDescription = null,
                        tint = Color.White,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("TaskWT", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(
                        "Worktree Manager",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            NavigationItem.entries.forEach { item ->
                val active = item == selected
                val icon = when (item) {
                    NavigationItem.DASHBOARD -> Icons.Outlined.SpaceDashboard
                    NavigationItem.TASKS -> Icons.Outlined.TaskAlt
                    NavigationItem.SERVICES -> Icons.Outlined.Dns
                    NavigationItem.UAT -> Icons.Outlined.Sell
                    NavigationItem.SETTINGS -> Icons.Outlined.Settings
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (active) MaterialTheme.colorScheme.primaryContainer
                            else Color.Transparent,
                        )
                        .clickable { onSelected(item) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            item.subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.weight(1f))
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.Security,
                        contentDescription = null,
                        tint = SuccessGreen,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "本地安全执行\n不上传源代码",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun AppTopBar(
    navigation: NavigationItem,
    onRefresh: () -> Unit,
    onCreateTask: () -> Unit,
    onThemeChange: (ThemePreference) -> Unit,
    theme: ThemePreference,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(72.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    navigation.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    navigation.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRefresh) {
                Icon(Icons.Outlined.Refresh, "刷新")
            }
            ThemeMenu(theme, onThemeChange)
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onCreateTask,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 11.dp),
            ) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text("新建任务")
            }
        }
    }
}

@Composable
private fun ThemeMenu(
    theme: ThemePreference,
    onThemeChange: (ThemePreference) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                when (theme) {
                    ThemePreference.SYSTEM -> Icons.Outlined.BrightnessAuto
                    ThemePreference.LIGHT -> Icons.Outlined.LightMode
                    ThemePreference.DARK -> Icons.Outlined.DarkMode
                },
                "主题",
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ThemePreference.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            when (option) {
                                ThemePreference.SYSTEM -> "跟随系统"
                                ThemePreference.LIGHT -> "浅色"
                                ThemePreference.DARK -> "深色"
                            },
                        )
                    },
                    onClick = {
                        expanded = false
                        onThemeChange(option)
                    },
                    leadingIcon = {
                        if (option == theme) Icon(Icons.Outlined.Check, null)
                    },
                )
            }
        }
    }
}

@Composable
private fun PageContainer(
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        content = content,
    )
}

@Composable
private fun DashboardScreen(controller: AppController) {
    val activeTasks = controller.tasks.count { it.status != WorkspaceStatus.ARCHIVED }
    val warnings = controller.tasks.count {
        it.status == WorkspaceStatus.READY_WITH_WARNINGS || it.status == WorkspaceStatus.FAILED
    }
    PageContainer {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SummaryCard(
                "活跃任务",
                activeTasks.toString(),
                "跨仓库工作区",
                Icons.Outlined.TaskAlt,
                BrandBlue,
                Modifier.weight(1f),
            )
            SummaryCard(
                "已管理服务",
                controller.repositories.size.toString(),
                "自动扫描 Git 仓库",
                Icons.Outlined.Dns,
                Color(0xFF7C3AED),
                Modifier.weight(1f),
            )
            SummaryCard(
                "待处理提醒",
                warnings.toString(),
                "初始化或任务异常",
                Icons.Outlined.WarningAmber,
                WarningAmber,
                Modifier.weight(1f),
            )
        }

        SectionHeader("最近任务", "快速回到正在开发的跨仓库需求")
        if (controller.tasks.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.FolderOpen,
                title = "还没有研发任务",
                description = "点击右上角“新建任务”，选择服务后自动创建隔离 Worktree。",
            )
        } else {
            controller.tasks.take(5).forEach { task ->
                TaskRow(
                    task = task,
                    selected = false,
                    onClick = {
                        controller.selectedTask = task
                        controller.navigation = NavigationItem.TASKS
                    },
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(
    title: String,
    value: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier.height(138.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.fillMaxSize().padding(20.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(7.dp))
                Text(value, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(13.dp)).background(accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = accent)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String? = null) {
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (subtitle != null) {
            Spacer(Modifier.height(3.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TasksScreen(controller: AppController) {
    Row(Modifier.fillMaxSize()) {
        Surface(
            Modifier.width(330.dp).fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column(Modifier.fillMaxSize().padding(18.dp)) {
                SectionHeader("任务列表", "${controller.tasks.size} 个任务")
                Spacer(Modifier.height(14.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(controller.tasks, key = { it.taskKey }) { task ->
                        TaskRow(
                            task = task,
                            selected = controller.selectedTask?.taskKey == task.taskKey,
                            onClick = { controller.selectedTask = task },
                            compact = true,
                        )
                    }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val selected = controller.selectedTask
            if (selected == null) {
                EmptyState(
                    icon = Icons.Outlined.TouchApp,
                    title = "选择一个任务",
                    description = "查看服务 Worktree、打开开发工具并执行 UAT 构建。",
                    modifier = Modifier.align(Alignment.Center).padding(40.dp),
                )
            } else {
                TaskDetail(controller, selected)
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: TaskManifest,
    selected: Boolean,
    onClick: () -> Unit,
    compact: Boolean = false,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(13.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
        ),
    ) {
        Row(
            Modifier.padding(if (compact) 13.dp else 17.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(if (compact) 38.dp else 42.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.FolderCopy, null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(task.taskKey, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    task.featureBranch,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StatusPill(task.status.name)
        }
    }
}

@Composable
private fun TaskDetail(controller: AppController, task: TaskManifest) {
    var showArchiveDialog by remember(task.taskKey) { mutableStateOf(false) }
    val availableServices = task.services.filter {
        it.status != WorkspaceStatus.ARCHIVED && it.status != WorkspaceStatus.FAILED
    }
    PageContainer {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(task.taskKey, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    task.featureBranch,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = { controller.copyPath(taskRootPath(controller, task)) }) {
                Icon(Icons.Outlined.ContentCopy, null, Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("复制路径")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { controller.terminal(taskRootPath(controller, task)) }) {
                Icon(Icons.Outlined.Terminal, null, Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text("终端")
            }
            if (availableServices.any { it.ideType == IdeType.IDEA }) {
                Spacer(Modifier.width(8.dp))
                Button(onClick = { controller.openTask(task, IdeType.IDEA) }) {
                    Icon(Icons.AutoMirrored.Outlined.Launch, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("IDEA 打开")
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                onClick = {},
                label = { Text("${task.services.size} 个服务") },
                leadingIcon = { Icon(Icons.Outlined.Dns, null, Modifier.size(17.dp)) },
            )
            AssistChip(
                onClick = {},
                label = { Text(task.status.name) },
                leadingIcon = { Icon(Icons.Outlined.Info, null, Modifier.size(17.dp)) },
            )
            if (availableServices.any { it.ideType == IdeType.WEBSTORM }) {
                AssistChip(
                    onClick = { controller.openTask(task, IdeType.WEBSTORM) },
                    label = { Text("WebStorm 打开") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Launch, null, Modifier.size(17.dp)) },
                )
            }
            if (availableServices.size > 1) {
                AssistChip(
                    onClick = { controller.showBatchSelection(task) },
                    label = { Text("批量 UAT Tag") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAddCheck, null, Modifier.size(17.dp)) },
                )
            }
        }

        SectionHeader("服务工作区", "每个服务可独立打开、定位、复制路径或生成测试 Tag")
        task.services.forEach { workspace ->
            WorkspaceCard(controller, task, workspace)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { controller.initializeTask(task, failedOnly = false) }) {
                Icon(Icons.Outlined.PlayCircle, null)
                Spacer(Modifier.width(6.dp))
                Text("重新初始化")
            }
            if (task.status == WorkspaceStatus.ARCHIVED) {
                Button(onClick = { controller.restoreTask(task) }) {
                    Icon(Icons.Outlined.Unarchive, null)
                    Spacer(Modifier.width(6.dp))
                    Text("恢复任务")
                }
            } else {
                OutlinedButton(onClick = { showArchiveDialog = true }) {
                    Icon(Icons.Outlined.Archive, null)
                    Spacer(Modifier.width(6.dp))
                    Text("安全归档")
                }
            }
        }
    }
    if (showArchiveDialog) {
        ArchiveTaskDialog(
            task = task,
            onDismiss = { showArchiveDialog = false },
            onArchive = { force ->
                controller.archiveTask(task, force)
                showArchiveDialog = false
            },
        )
    }
}

private fun taskRootPath(controller: AppController, task: TaskManifest): String =
    Path.of(controller.config.taskRoot.orEmpty()).resolve(task.taskDirectoryName).toString()

@Composable
private fun WorkspaceCard(
    controller: AppController,
    task: TaskManifest,
    workspace: ServiceWorkspace,
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(1.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (workspace.ideType == IdeType.IDEA) Icons.Outlined.Code
                        else Icons.Outlined.Web,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(workspace.serviceName, fontWeight = FontWeight.SemiBold)
                    Text(
                        workspace.worktreePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StatusPill(workspace.status.name)
            }
            if (workspace.warnings.isNotEmpty()) {
                Surface(
                    color = WarningAmber.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        workspace.warnings.forEach {
                            Text("• $it", style = MaterialTheme.typography.bodySmall, color = WarningAmber)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val workspaceAvailable = workspace.status != WorkspaceStatus.ARCHIVED &&
                    workspace.status != WorkspaceStatus.FAILED
                TextButton(
                    onClick = { controller.openWorkspace(workspace) },
                    enabled = workspaceAvailable,
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Launch, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("打开")
                }
                TextButton(
                    onClick = { controller.terminal(workspace.worktreePath) },
                    enabled = workspaceAvailable,
                ) {
                    Icon(Icons.Outlined.Terminal, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("终端")
                }
                TextButton(onClick = { controller.copyPath(workspace.worktreePath) }) {
                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("复制路径")
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { controller.preflightTag(task, workspace.repositoryId) },
                    enabled = workspaceAvailable,
                ) {
                    Icon(Icons.Outlined.Sell, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("生成 UAT Tag")
                }
            }
        }
    }
}

@Composable
private fun ServicesScreen(controller: AppController) {
    var editing by remember { mutableStateOf<ServiceConfig?>(null) }
    PageContainer {
        SectionHeader("服务仓库", "仅管理扫描目录中发现的主 Git 仓库；自动忽略任务 Worktree 和构建目录")
        if (controller.repositories.isEmpty()) {
            EmptyState(Icons.Outlined.SearchOff, "没有扫描到仓库", "请到设置中添加服务扫描目录，然后点击刷新。")
        } else {
            controller.repositories.forEach { repository ->
                val service = controller.config.services[repository.id] ?: return@forEach
                ElevatedCard(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(15.dp),
                    elevation = CardDefaults.elevatedCardElevation(1.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(42.dp).clip(RoundedCornerShape(12.dp))
                                .background(BrandBlue.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Outlined.Source, null, tint = BrandBlue)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(service.displayName, fontWeight = FontWeight.SemiBold)
                            Text(
                                repository.rootPath,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            "${service.ideType} · ${service.uatRemote}/${service.uatBranch}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = service.enabled,
                            onCheckedChange = {
                                controller.updateService(service.copy(enabled = it))
                            },
                        )
                        IconButton(onClick = { editing = service }) {
                            Icon(Icons.Outlined.Edit, "编辑")
                        }
                    }
                }
            }
        }
    }
    editing?.let { service ->
        ServiceEditDialog(
            service = service,
            onDismiss = { editing = null },
            onSave = {
                controller.updateService(it)
                editing = null
            },
        )
    }
}

@Composable
private fun UatScreen(controller: AppController) {
    val operations = remember(controller.tasks, controller.tagResult, controller.batchTagResults) {
        controller.tasks.flatMap { task ->
            val root = controller.config.taskRoot ?: return@flatMap emptyList()
            runCatching {
                TagOperationStore().list(Path.of(root).resolve(task.taskDirectoryName))
            }.getOrDefault(emptyList())
        }.sortedByDescending { it.updatedAt }
    }
    PageContainer {
        SectionHeader("UAT 构建记录", "测试分支和 Tag 均在隔离 Worktree 中生成，并保留完整状态")
        if (operations.isEmpty()) {
            EmptyState(Icons.Outlined.Sell, "还没有 UAT 构建", "进入研发任务，选择服务后执行“生成 UAT Tag”。")
        } else {
            operations.forEach { operation ->
                ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(17.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (operation.state == TagOperationState.SUCCESS) Icons.Outlined.CheckCircle
                            else Icons.Outlined.Info,
                            null,
                            tint = MaterialTheme.colorScheme.statusColor(operation.state.name),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${operation.serviceName} · ${operation.tag ?: "尚未生成 Tag"}",
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "${operation.taskKey} · ${operation.featureBranch} → ${operation.testBranch}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        StatusPill(operation.state.name)
                        if (operation.state == TagOperationState.PARTIAL) {
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { controller.resumeTag(operation) }) {
                                Text("继续")
                            }
                        } else if (operation.state == TagOperationState.CONFLICT) {
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { controller.openOperationTask(operation) }) {
                                Text("打开任务")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(controller: AppController) {
    var idea by remember(controller.config.ideaExecutable) {
        mutableStateOf(controller.config.ideaExecutable.orEmpty())
    }
    var webStorm by remember(controller.config.webStormExecutable) {
        mutableStateOf(controller.config.webStormExecutable.orEmpty())
    }
    var terminal by remember(controller.config.terminalExecutable) {
        mutableStateOf(controller.config.terminalExecutable.orEmpty())
    }
    PageContainer {
        SectionHeader("目录配置", "配置文件：${ApplicationPaths.systemDefault().config}")
        SettingsCard("服务扫描目录", "递归发现目录内的主 Git 仓库，不支持单独手工添加仓库") {
            controller.config.scanRoots.forEach { root ->
                PathSettingRow(root, onRemove = { controller.removeScanRoot(root) })
            }
            OutlinedButton(onClick = {
                chooseDirectory("选择服务扫描目录")?.let(controller::addScanRoot)
            }) {
                Icon(Icons.Outlined.Add, null)
                Spacer(Modifier.width(6.dp))
                Text("添加扫描目录")
            }
        }
        SettingsCard("任务工作区目录", "任务将创建在 <taskRoot>/<taskDirectoryName> 下") {
            PathSettingRow(
                controller.config.taskRoot ?: "尚未配置",
                onBrowse = {
                    chooseDirectory("选择任务工作区目录")?.let(controller::updateTaskRoot)
                },
            )
        }
        SettingsCard("JetBrains 开发工具", "建议在 IDEA/WebStorm 全局设置中选择“项目在新窗口打开”") {
            ExecutableField(
                label = "IntelliJ IDEA",
                value = idea,
                onValueChange = { idea = it },
                onBrowse = { chooseFile("选择 idea64.exe")?.let { idea = it } },
            )
            ExecutableField(
                label = "WebStorm",
                value = webStorm,
                onValueChange = { webStorm = it },
                onBrowse = { chooseFile("选择 webstorm64.exe")?.let { webStorm = it } },
            )
            ExecutableField(
                label = "终端（可选）",
                value = terminal,
                onValueChange = { terminal = it },
                onBrowse = { chooseFile("选择终端程序")?.let { terminal = it } },
            )
            Button(onClick = { controller.updateIdeExecutables(idea, webStorm, terminal) }) {
                Text("保存开发工具配置")
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun PathSettingRow(
    value: String,
    onRemove: (() -> Unit)? = null,
    onBrowse: (() -> Unit)? = null,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Folder, null, Modifier.size(19.dp))
            Spacer(Modifier.width(9.dp))
            Text(value, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (onBrowse != null) TextButton(onClick = onBrowse) { Text("更改") }
            if (onRemove != null) IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.Close, "移除", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ExecutableField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onBrowse: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        trailingIcon = {
            IconButton(onClick = onBrowse) { Icon(Icons.Outlined.FolderOpen, "浏览") }
        },
    )
}

@Composable
private fun StatusPill(status: String) {
    val color = MaterialTheme.colorScheme.statusColor(status)
    Surface(
        color = color.copy(alpha = 0.11f),
        contentColor = color,
        shape = RoundedCornerShape(20.dp),
    ) {
        Text(
            status.replace('_', ' '),
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 54.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OnboardingDialog(controller: AppController) {
    var scanRoot by remember { mutableStateOf("") }
    var taskRoot by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = {},
        icon = {
            Box(
                Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(BrandBlue),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.AccountTree, null, tint = Color.White)
            }
        },
        title = { Text("欢迎使用 Task Worktree Manager", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "首次使用不预设任何目录。请选择服务扫描目录和任务工作区目录，后续可在设置中继续添加扫描目录。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DirectoryField("服务扫描目录", scanRoot) {
                    chooseDirectory("选择服务扫描目录")?.let { scanRoot = it }
                }
                DirectoryField("任务工作区目录", taskRoot) {
                    chooseDirectory("选择任务工作区目录")?.let { taskRoot = it }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { controller.completeOnboarding(scanRoot, taskRoot) },
                enabled = scanRoot.isNotBlank() && taskRoot.isNotBlank(),
            ) {
                Text("保存并开始扫描")
            }
        },
        shape = RoundedCornerShape(20.dp),
    )
}

@Composable
private fun DirectoryField(label: String, value: String, browse: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        trailingIcon = { IconButton(onClick = browse) { Icon(Icons.Outlined.FolderOpen, "选择") } },
    )
}

@Composable
private fun CreateTaskDialog(
    repositories: List<RepositoryInfo>,
    onDismiss: () -> Unit,
    onCreate: (String, String, List<String>) -> Unit,
) {
    var taskKey by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("新建跨仓库任务", fontWeight = FontWeight.Bold)
                Text(
                    "任务编号可以是任意字符串",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(
                Modifier.widthIn(min = 560.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                OutlinedTextField(
                    taskKey,
                    { taskKey = it },
                    label = { Text("需求编号 / taskKey") },
                    placeholder = { Text("例如：OBT-12345 支付链路改造") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    branch,
                    { branch = it },
                    label = { Text("Feature 分支名") },
                    placeholder = { Text("例如：feature/OBT-12345") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                SectionHeader("选择本次涉及的服务", "将为每个服务创建同名分支 Worktree")
                repositories.forEach { repository ->
                    val checked = repository.id in selected
                    Surface(
                        Modifier.fillMaxWidth().clickable {
                            selected = if (checked) selected - repository.id else selected + repository.id
                        },
                        color = if (checked) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(11.dp),
                    ) {
                        Row(
                            Modifier.padding(11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    selected = if (checked) selected - repository.id else selected + repository.id
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(repository.name, fontWeight = FontWeight.Medium)
                                Text(
                                    repository.rootPath,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(taskKey, branch, selected.toList()) },
                enabled = taskKey.isNotBlank() && branch.isNotBlank() && selected.isNotEmpty(),
            ) { Text("创建 Worktree") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        shape = RoundedCornerShape(20.dp),
    )
}

@Composable
private fun ServiceEditDialog(
    service: ServiceConfig,
    onDismiss: () -> Unit,
    onSave: (ServiceConfig) -> Unit,
) {
    var name by remember { mutableStateOf(service.displayName) }
    var ide by remember { mutableStateOf(service.ideType) }
    var baseRef by remember { mutableStateOf(service.defaultBaseRef) }
    var remote by remember { mutableStateOf(service.uatRemote) }
    var testBranch by remember { mutableStateOf(service.uatBranch) }
    var initialTag by remember { mutableStateOf(service.initialUatTag.orEmpty()) }
    val json = remember { Json { prettyPrint = true; encodeDefaults = true } }
    var bootstrapJson by remember { mutableStateOf(json.encodeToString(service.bootstrap)) }
    var bootstrapError by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("服务配置 · ${service.displayName}", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.widthIn(min = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("显示名称") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = ide == IdeType.IDEA,
                        onClick = { ide = IdeType.IDEA },
                        label = { Text("IntelliJ IDEA") },
                    )
                    FilterChip(
                        selected = ide == IdeType.WEBSTORM,
                        onClick = { ide = IdeType.WEBSTORM },
                        label = { Text("WebStorm") },
                    )
                }
                OutlinedTextField(baseRef, { baseRef = it }, label = { Text("创建任务基础分支") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(remote, { remote = it }, label = { Text("UAT Remote") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(testBranch, { testBranch = it }, label = { Text("测试环境分支") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    initialTag,
                    { initialTag = it },
                    label = { Text("首次 UAT Tag（仓库无历史 Tag 时使用）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("初始化步骤", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        bootstrapJson = json.encodeToString(
                            BootstrapConfig(
                                commands = listOf(
                                    com.snowball.taskwt.core.BootstrapCommand(
                                        name = "初始化 CodeGraph 索引",
                                        executable = "codegraph",
                                        arguments = listOf("init", "-i"),
                                        timeoutSeconds = 600,
                                    ),
                                ),
                            ),
                        )
                        bootstrapError = null
                    }) {
                        Text("使用 CodeGraph 预设")
                    }
                }
                OutlinedTextField(
                    value = bootstrapJson,
                    onValueChange = {
                        bootstrapJson = it
                        bootstrapError = null
                    },
                    label = { Text("Bootstrap JSON（复制规则和顺序命令）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                    maxLines = 12,
                    isError = bootstrapError != null,
                    supportingText = bootstrapError?.let { message -> { Text(message) } },
                    textStyle = MaterialTheme.typography.bodySmall,
                )
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        "默认流程：Feature → $remote/$testBranch → 生成并推送 Tag。发生冲突时只提示冲突文件，不改动开发工作区。",
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                runCatching { json.decodeFromString<BootstrapConfig>(bootstrapJson) }
                    .onSuccess { bootstrap ->
                        onSave(
                            service.copy(
                                displayName = name,
                                ideType = ide,
                                defaultBaseRef = baseRef,
                                uatRemote = remote,
                                uatBranch = testBranch,
                                initialUatTag = initialTag.ifBlank { null },
                                bootstrap = bootstrap,
                            ),
                        )
                    }
                    .onFailure { bootstrapError = "JSON 格式错误：${it.message}" }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        shape = RoundedCornerShape(20.dp),
    )
}

@Composable
private fun ArchiveTaskDialog(
    task: TaskManifest,
    onDismiss: () -> Unit,
    onArchive: (force: Boolean) -> Unit,
) {
    var confirmation by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Archive, null, tint = WarningAmber) },
        title = { Text("归档任务", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "工具会先检查暂存、未提交、未跟踪、未推送提交以及进行中的 merge/rebase。安全检查通过后移除 Worktree，但保留本地分支、任务清单和 Tag 历史。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    color = WarningAmber.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        "仅在你已经确认所有本地改动均可丢弃时，输入完整任务编号以启用强制归档。",
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = WarningAmber,
                    )
                }
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = { Text("强制归档确认：${task.taskKey}") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onArchive(false) }) {
                Text("执行安全归档")
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = { onArchive(true) },
                    enabled = confirmation == task.taskKey,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("强制归档")
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
        shape = RoundedCornerShape(20.dp),
    )
}

@Composable
private fun BatchServiceSelectionDialog(
    task: TaskManifest,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val eligible = task.services.filter {
        it.status != WorkspaceStatus.ARCHIVED && it.status != WorkspaceStatus.FAILED
    }
    var selected by remember(task.taskKey) {
        mutableStateOf(eligible.map { it.repositoryId }.toSet())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAddCheck, null, tint = BrandBlue) },
        title = { Text("批量生成 UAT Tag", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.widthIn(min = 560.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(
                    "每个服务会独立预检和构建；某个服务失败不会中断其他服务。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                eligible.forEach { workspace ->
                    val checked = workspace.repositoryId in selected
                    Surface(
                        modifier = Modifier.fillMaxWidth().clickable {
                            selected = if (checked) {
                                selected - workspace.repositoryId
                            } else {
                                selected + workspace.repositoryId
                            }
                        },
                        color = if (checked) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(11.dp),
                    ) {
                        Row(
                            Modifier.padding(11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    selected = if (checked) {
                                        selected - workspace.repositoryId
                                    } else {
                                        selected + workspace.repositoryId
                                    }
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(workspace.serviceName, fontWeight = FontWeight.Medium)
                                Text(
                                    workspace.branch,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            StatusPill(workspace.status.name)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selected.toList()) },
                enabled = selected.isNotEmpty(),
            ) { Text("预检 ${selected.size} 个服务") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        shape = RoundedCornerShape(20.dp),
    )
}

@Composable
private fun BatchTagPreflightDialog(
    preflight: BatchTagPreflight,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val passed = preflight.entries.count { it.preview != null }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Outlined.FactCheck, null, tint = if (passed > 0) SuccessGreen else DangerRed) },
        title = { Text("批量 UAT 预检结果", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.widthIn(min = 680.dp).heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "通过 $passed / ${preflight.entries.size}。确认后只构建预检通过的服务。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                preflight.entries.forEach { entry ->
                    val preview = entry.preview
                    Surface(
                        color = if (preview != null) SuccessGreen.copy(alpha = 0.08f)
                        else MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(13.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (preview != null) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                                    null,
                                    tint = if (preview != null) SuccessGreen else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(entry.serviceName, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                if (preview != null) StatusPill(preview.mergeMode.name)
                            }
                            if (preview != null) {
                                Text(
                                    "${preview.featureBranch} → ${preview.remote}/${preview.testBranch}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(
                                    "预计 Tag：${preview.estimatedTag} · ${preview.commitList.size} 个提交",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    entry.error ?: "预检失败",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = passed > 0) {
                Text("构建 $passed 个服务")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        shape = RoundedCornerShape(20.dp),
    )
}

@Composable
private fun BatchTagResultDialog(
    results: List<TagOperation>,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val successes = results.filter { it.state == TagOperationState.SUCCESS }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                if (successes.size == results.size) Icons.Outlined.CheckCircle else Icons.Outlined.Info,
                null,
                tint = if (successes.size == results.size) SuccessGreen else WarningAmber,
                modifier = Modifier.size(38.dp),
            )
        },
        title = { Text("批量 UAT 构建结果", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.widthIn(min = 620.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text(
                    "成功 ${successes.size} / ${results.size}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                results.forEach { result ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(11.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(result.serviceName, fontWeight = FontWeight.SemiBold)
                                Text(
                                    result.message ?: result.state.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            StatusPill(result.state.name)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("完成") }
        },
        dismissButton = {
            if (successes.isNotEmpty()) {
                OutlinedButton(onClick = onCopy) {
                    Icon(Icons.Outlined.ContentCopy, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("复制成功清单")
                }
            }
        },
        shape = RoundedCornerShape(20.dp),
    )
}

@Composable
private fun TagPreflightDialog(
    preview: TagPreflight,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Outlined.FactCheck, null, tint = SuccessGreen) },
        title = { Text("UAT 构建预检", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                Modifier.widthIn(min = 620.dp).heightIn(max = 600.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PreviewLine("服务", preview.serviceName)
                PreviewLine("Feature", "${preview.featureBranch}@${preview.featureSha.take(12)}")
                PreviewLine("测试分支", "${preview.remote}/${preview.testBranch}@${preview.testSha.take(12)}")
                PreviewLine("合并方式", preview.mergeMode.name)
                PreviewLine("预计 Tag", preview.estimatedTag)
                PreviewLine("远端同步", "ahead ${preview.featureSync.ahead} · behind ${preview.featureSync.behind}")
                HorizontalDivider()
                Text("提交列表", fontWeight = FontWeight.SemiBold)
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
                    Text(
                        preview.commitList.ifEmpty { listOf("Feature 已包含在测试分支中") }.joinToString("\n"),
                        Modifier.fillMaxWidth().padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text("Diff 统计", fontWeight = FontWeight.SemiBold)
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
                    Text(
                        preview.diffStat.ifBlank { "无文件差异" },
                        Modifier.fillMaxWidth().padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Icon(Icons.Outlined.Sell, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("确认合并并生成 Tag")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        shape = RoundedCornerShape(20.dp),
    )
}

@Composable
private fun PreviewLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(100.dp))
        Text(value, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TagResultDialog(
    result: TagOperation,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val success = result.state == TagOperationState.SUCCESS
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                if (success) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                null,
                tint = if (success) SuccessGreen else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(38.dp),
            )
        },
        title = { Text(if (success) "UAT Tag 构建成功" else "UAT Tag 需要处理", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusPill(result.state.name)
                Surface(
                    Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(11.dp),
                ) {
                    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            result.message ?: "${result.serviceName}：${result.state}",
                            Modifier.weight(1f),
                            fontWeight = FontWeight.Medium,
                        )
                        if (success) IconButton(onClick = onCopy) {
                            Icon(Icons.Outlined.ContentCopy, "复制结果")
                        }
                    }
                }
                if (result.conflictFiles.isNotEmpty()) {
                    Text("冲突文件", fontWeight = FontWeight.SemiBold)
                    result.conflictFiles.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }
                    Text(
                        "请手工将 Feature 合并到 ${result.testBranch} 并推送，然后重新执行构建。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("完成") } },
        shape = RoundedCornerShape(20.dp),
    )
}

private fun chooseDirectory(title: String): String? {
    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        isAcceptAllFileFilterUsed = false
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile.absolutePath
    } else {
        null
    }
}

private fun chooseFile(title: String): String? {
    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.FILES_ONLY
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile.absolutePath
    } else {
        null
    }
}
