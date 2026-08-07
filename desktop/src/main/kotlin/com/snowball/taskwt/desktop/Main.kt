package com.snowball.taskwt.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.delay
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
        val density = LocalDensity.current
        LaunchedEffect(density) {
            window.minimumSize = with(density) {
                Dimension(1400.dp.roundToPx(), 720.dp.roundToPx())
            }
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
    var addServicesTask by remember { mutableStateOf<TaskManifest?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(10 * 60 * 1000L)
            controller.refreshRequirementStatuses()
        }
    }

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
                        NavigationItem.TASKS -> TasksScreen(
                            controller,
                            onAddServices = { addServicesTask = it },
                        )
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
            onCreate = { folderName, branch, services, requirementLink ->
                controller.createTask(folderName, branch, services, requirementLink)
                showCreateTask = false
            },
        )
    }
    addServicesTask?.let { task ->
        AddServicesDialog(
            task = task,
            repositories = controller.repositories.filter { repository ->
                controller.config.services[repository.id]?.enabled != false &&
                    task.services.none { it.repositoryId == repository.id }
            },
            onDismiss = { addServicesTask = null },
            onConfirm = { repositoryIds ->
                controller.addServices(task, repositoryIds)
                addServicesTask = null
            },
        )
    }
    controller.pendingBranchReuse?.let { pending ->
        BranchReuseDialog(
            conflicts = pending.conflicts,
            onCancel = controller::cancelBranchReuse,
            onConfirm = controller::confirmBranchReuse,
        )
    }
    controller.batchSelectionTask?.let { task ->
        BatchServiceSelectionDialog(
            task = task,
            onDismiss = controller::clearBatchSelection,
            onConfirm = { repositoryIds -> controller.buildTags(task, repositoryIds) },
        )
    }
    controller.batchTagResults?.let { results ->
        BatchTagResultDialog(
            results = results,
            requirementLink = controller.tasks
                .firstOrNull { it.folderName == results.firstOrNull()?.folderName }
                ?.requirementLink
                .orEmpty(),
            onCopy = {
                val text = TagOutputFormatter.format(
                    requirementLink = controller.tasks
                        .firstOrNull { it.folderName == results.firstOrNull()?.folderName }
                        ?.requirementLink
                        .orEmpty(),
                    operations = results,
                    includeFailures = false,
                )
                controller.copyText(text, "Tag 已复制")
            },
            onDismiss = controller::clearBatchTagResults,
        )
    }
    controller.tagResult?.let { result ->
        TagResultDialog(
            result = result,
            requirementLink = controller.tasks
                .firstOrNull { it.folderName == result.folderName }
                ?.requirementLink
                .orEmpty(),
            onCopy = {
                if (result.state == TagOperationState.SUCCESS && !result.tag.isNullOrBlank()) {
                    controller.copyText(
                        TagOutputFormatter.format(
                            requirementLink = controller.tasks
                                .firstOrNull { it.folderName == result.folderName }
                                ?.requirementLink
                                .orEmpty(),
                            operations = listOf(result),
                            includeFailures = false,
                        ),
                        "Tag 已复制",
                    )
                }
            },
            onDismiss = controller::clearTagResult,
        )
    }
}

private fun AppController.copyPathText(text: String) {
    copyText(text, "已复制")
}

private fun formatTagCopyText(serviceName: String, tag: String): String =
    "$serviceName · $tag"

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
                color = Color.Transparent,
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        controller.selectTask(task)
                        controller.navigation = NavigationItem.TASKS
                    },
                    requirementStatus = controller.requirementStatuses[task.folderName],
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
private fun TasksScreen(
    controller: AppController,
    onAddServices: (TaskManifest) -> Unit,
) {
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
                    items(controller.tasks, key = { it.folderName }) { task ->
                        TaskRow(
                            task = task,
                            selected = controller.selectedTask?.folderName == task.folderName,
                            onClick = { controller.selectTask(task) },
                            requirementStatus = controller.requirementStatuses[task.folderName],
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
                TaskDetail(controller, selected, onAddServices = { onAddServices(selected) })
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: TaskManifest,
    selected: Boolean,
    onClick: () -> Unit,
    requirementStatus: String? = null,
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
                Text(task.folderName, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    task.featureBranch,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            requirementStatus?.let { status -> StatusPill(status) }
        }
    }
}

@Composable
private fun TaskDetail(
    controller: AppController,
    task: TaskManifest,
    onAddServices: () -> Unit,
) {
    var showArchiveDialog by remember(task.folderName) { mutableStateOf(false) }
    var showDeleteDialog by remember(task.folderName) { mutableStateOf(false) }
    var showBranchInfoDialog by remember(task.folderName) { mutableStateOf(false) }
    val availableServices = task.services.filter { workspace ->
        workspace.status != WorkspaceStatus.ARCHIVED &&
            workspace.status != WorkspaceStatus.FAILED &&
            Path.of(workspace.worktreePath).toFile().isDirectory
    }
    val failedServices = task.services.filter { it.status == WorkspaceStatus.FAILED }
    val hasExistingWorktrees = task.services.any { workspace ->
        val root = Path.of(workspace.worktreePath)
        root.toFile().isDirectory && root.resolve(".git").toFile().exists()
    }
    PageContainer {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(task.folderName, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    task.featureBranch,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable {
                        controller.copyText(task.featureBranch, "分支已复制")
                    },
                )
                if (task.requirementLink.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    val linkClickable = isHttpUrl(task.requirementLink)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = task.requirementLink,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (linkClickable) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (linkClickable) {
                                Modifier.clickable { controller.openUrl(task.requirementLink) }
                            } else {
                                Modifier
                            },
                        )
                        IconButton(
                            onClick = { controller.copyText(task.requirementLink, "需求链接已复制") },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(Icons.Outlined.ContentCopy, "复制需求链接")
                        }
                    }
                }
                controller.requirementParticipants[task.folderName]?.let { participants ->
                    Spacer(Modifier.height(4.dp))
                    val names = (participants.qcOwners + participants.productManagers)
                        .map { it.name }
                        .distinct()
                    if (names.isNotEmpty()) {
                        Text(
                            names.joinToString("  "),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val contentWidth = maxOf(maxWidth, 720.dp)
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Row(
                    modifier = Modifier.width(contentWidth),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AssistChip(
                        onClick = { controller.copyPath(taskRootPath(controller, task)) },
                        label = { Text("复制路径") },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(17.dp)) },
                    )
                    AssistChip(
                        onClick = { controller.terminal(taskRootPath(controller, task)) },
                        label = { Text("终端") },
                        leadingIcon = { Icon(Icons.Outlined.Terminal, null, Modifier.size(17.dp)) },
                    )
                    AssistChip(
                        onClick = { controller.reveal(taskRootPath(controller, task)) },
                        label = { Text("打开文件夹") },
                        leadingIcon = { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(17.dp)) },
                    )
                    if (availableServices.size > 1) {
                        AssistChip(
                            onClick = { controller.showBatchSelection(task) },
                            label = { Text("批量 UAT Tag") },
                            leadingIcon = {
                                Icon(Icons.AutoMirrored.Outlined.PlaylistAddCheck, null, Modifier.size(17.dp))
                            },
                        )
                    }
                    if (task.status != WorkspaceStatus.ARCHIVED) {
                        AssistChip(
                            onClick = onAddServices,
                            label = { Text("添加服务") },
                            leadingIcon = { Icon(Icons.Outlined.Add, null, Modifier.size(17.dp)) },
                        )
                    }
                    AssistChip(
                        onClick = { showBranchInfoDialog = true },
                        label = { Text("分支信息") },
                        leadingIcon = { Icon(Icons.Outlined.AccountTree, null, Modifier.size(17.dp)) },
                    )
                    AssistChip(
                        onClick = { controller.openAiData(task) },
                        label = { Text("打开工作数据") },
                        leadingIcon = { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(17.dp)) },
                    )
                }
            }
        }

        SectionHeader("服务工作区", "每个服务可独立打开、定位、复制路径或生成测试 Tag")
        task.services.forEach { workspace ->
            WorkspaceCard(controller, task, workspace)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { controller.refreshAgentsMd(task) }) {
                Icon(Icons.Outlined.Description, null)
                Spacer(Modifier.width(6.dp))
                Text("刷新 AGENTS.md")
            }
            OutlinedButton(
                onClick = { controller.initializeTask(task, failedOnly = false) },
                enabled = hasExistingWorktrees,
            ) {
                Icon(Icons.Outlined.PlayCircle, null)
                Spacer(Modifier.width(6.dp))
                Text("重新初始化")
            }
            if (failedServices.isNotEmpty() && task.status != WorkspaceStatus.ARCHIVED) {
                Button(onClick = { controller.retryFailedServices(task) }) {
                    Icon(Icons.Outlined.Replay, null)
                    Spacer(Modifier.width(6.dp))
                    Text("重试失败服务")
                }
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
            OutlinedButton(
                onClick = { showDeleteDialog = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Outlined.DeleteForever, null)
                Spacer(Modifier.width(6.dp))
                Text("删除任务")
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
    if (showDeleteDialog) {
        DeleteTaskDialog(
            controller = controller,
            task = task,
            onDismiss = { showDeleteDialog = false },
            onDelete = { forceDiscard ->
                controller.deleteTask(task, forceDiscard)
                showDeleteDialog = false
            },
        )
    }
    if (showBranchInfoDialog) {
        BranchInfoDialog(
            task = task,
            onCopy = {
                controller.copyText(TaskBranchInfoFormatter.format(task), "分支信息已复制")
            },
            onDismiss = { showBranchInfoDialog = false },
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
    var warningDismissed by remember(task.folderName, workspace.repositoryId, workspace.warnings) {
        mutableStateOf(false)
    }
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
            if (workspace.warnings.isNotEmpty() && !warningDismissed) {
                Surface(
                    color = WarningAmber.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 6.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            workspace.warnings.forEach {
                                Text("• $it", style = MaterialTheme.typography.bodySmall, color = WarningAmber)
                            }
                        }
                        IconButton(
                            onClick = { warningDismissed = true },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(Icons.Outlined.Close, "关闭提示", tint = WarningAmber)
                        }
                    }
                }
            }
            val worktreeExists = Path.of(workspace.worktreePath).toFile().isDirectory
            val workspaceAvailable = workspace.status != WorkspaceStatus.ARCHIVED &&
                workspace.status != WorkspaceStatus.FAILED &&
                worktreeExists
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val contentWidth = maxOf(maxWidth, 760.dp)
                Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    Row(
                        modifier = Modifier.width(contentWidth),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (workspace.status == WorkspaceStatus.FAILED &&
                                task.status != WorkspaceStatus.ARCHIVED
                            ) {
                                Button(
                                    onClick = {
                                        controller.retryFailedServices(task, listOf(workspace.repositoryId))
                                    },
                                ) {
                                    Icon(Icons.Outlined.Replay, null, Modifier.size(17.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text("重试创建")
                                }
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
                        }
                        Spacer(Modifier.weight(1f))
                        Row(
                            modifier = Modifier.padding(end = 24.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = { controller.buildTag(task, workspace.repositoryId) },
                                enabled = workspaceAvailable,
                            ) {
                                Icon(Icons.Outlined.Sell, null, Modifier.size(17.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("创建Tag")
                            }
                            Button(
                                onClick = { controller.openWorkspace(workspace) },
                                enabled = workspaceAvailable,
                            ) {
                                Icon(Icons.AutoMirrored.Outlined.Launch, null, Modifier.size(17.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (workspace.ideType == IdeType.IDEA) "IDEA 打开" else "WebStorm 打开")
                            }
                        }
                    }
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
            EmptyState(Icons.Outlined.Sell, "还没有 UAT 构建", "进入研发任务，选择服务后执行“创建Tag”。")
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
                                "${operation.folderName} · ${operation.featureBranch} → ${operation.testBranch}",
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
                        Spacer(Modifier.width(4.dp))
                        IconButton(
                            onClick = {
                                operation.tag?.let { tag ->
                                    controller.copyText(
                                        formatTagCopyText(operation.serviceName, tag),
                                        "Tag 已复制",
                                    )
                                }
                            },
                            enabled = !operation.tag.isNullOrBlank(),
                        ) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = "复制 Tag")
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
    var agentsAppendix by remember(controller.config.agentsMdAppendix) {
        mutableStateOf(controller.config.agentsMdAppendix)
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
        SettingsCard(
            "AGENTS.md 模板追加",
            "每次生成/刷新任务 AGENTS.md 时，原样拼接到文末「自定义说明」章节；留空则省略该章节",
        ) {
            OutlinedTextField(
                value = agentsAppendix,
                onValueChange = { agentsAppendix = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                minLines = 5,
                placeholder = { Text("可选：团队约定、常用命令、注意事项…") },
            )
            Button(onClick = { controller.updateAgentsMdAppendix(agentsAppendix) }) {
                Text("保存 AGENTS.md 追加内容")
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
    onCreate: (folderName: String, branch: String, services: List<String>, requirementLink: String) -> Unit,
) {
    var requirementLink by remember { mutableStateOf("") }
    var folderName by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("新建跨仓库任务", fontWeight = FontWeight.Bold)
                Text(
                    "需求链接、文件夹名与 Feature 分支均为必填",
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
                    requirementLink,
                    { requirementLink = it },
                    label = { Text("需求链接") },
                    placeholder = { Text("飞书项目 URL 或任意文本") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    folderName,
                    { folderName = it },
                    label = { Text("文件夹名") },
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
                onClick = { onCreate(folderName, branch, selected.toList(), requirementLink) },
                enabled = requirementLink.isNotBlank() &&
                    folderName.isNotBlank() &&
                    branch.isNotBlank() &&
                    selected.isNotEmpty(),
            ) { Text("创建 Worktree") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        shape = RoundedCornerShape(20.dp),
    )
}

@Composable
private fun BranchReuseDialog(
    conflicts: List<BranchConflict>,
    onCancel: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var selected by remember(conflicts) { mutableStateOf(emptySet<String>()) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("发现同名分支") },
        text = {
            Column(
                Modifier.widthIn(min = 620.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("以下项目已存在同名本地或远端分支，请逐项确认是否复用。取消任一项目将终止本次创建。")
                conflicts.forEach { conflict ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = conflict.repositoryId in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) {
                                        selected + conflict.repositoryId
                                    } else {
                                        selected - conflict.repositoryId
                                    }
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Text(conflict.serviceName, fontWeight = FontWeight.SemiBold)
                                Text(conflict.repositoryPath, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    buildString {
                                        if (conflict.localBranchExists) append("本地分支")
                                        if (conflict.remoteBranchExists) {
                                            if (isNotEmpty()) append("、")
                                            append("远端 ${conflict.remoteRef}")
                                        }
                                        if (conflict.occupiedWorktreePath != null) {
                                            if (isNotEmpty()) append("、")
                                            append("已被 Worktree 占用：${conflict.occupiedWorktreePath}")
                                        }
                                    },
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
            Button(
                onClick = {
                    if (selected.size == conflicts.size) onConfirm(selected)
                    else onCancel()
                },
            ) { Text("复用并创建") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("终止创建") } },
    )
}

@Composable
private fun AddServicesDialog(
    task: TaskManifest,
    repositories: List<RepositoryInfo>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("追加服务", fontWeight = FontWeight.Bold)
                Text(
                    "将使用任务已有分支：${task.featureBranch}",
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
                if (repositories.isEmpty()) {
                    Text(
                        "没有可追加的服务（可能均已加入或未启用）。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    SectionHeader("选择要追加的服务", "创建同名 Feature 分支 Worktree，并执行 Bootstrap")
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
                                        selected = if (checked) {
                                            selected - repository.id
                                        } else {
                                            selected + repository.id
                                        }
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
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selected.toList()) },
                enabled = selected.isNotEmpty(),
            ) { Text("追加服务") }
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
                        bootstrapJson = json.encodeToString(BootstrapPresets.empty())
                        bootstrapError = null
                    }) {
                        Text("恢复默认")
                    }
                    TextButton(onClick = {
                        bootstrapJson = json.encodeToString(BootstrapPresets.codeGraph())
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
                        "仅在你已经确认所有本地改动均可丢弃时，输入完整文件夹名以启用强制归档。",
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = WarningAmber,
                    )
                }
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = { Text("强制归档确认：${task.folderName}") },
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
                    enabled = confirmation == task.folderName,
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
private fun DeleteTaskDialog(
    controller: AppController,
    task: TaskManifest,
    onDismiss: () -> Unit,
    onDelete: (forceDiscard: Boolean) -> Unit,
) {
    val risks = remember(task.folderName) { controller.inspectDeleteRisk(task) }
    var discardConfirmed by remember { mutableStateOf(false) }
    val canDelete = risks.isEmpty() || discardConfirmed
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("删除任务", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "将永久删除任务目录及其中的 Worktree，并释放磁盘空间。本地 / 远端 Feature 分支会保留。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "路径：${taskRootPath(controller, task)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (risks.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "以下服务存在未提交改动或进行中的 Git 操作：",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            risks.forEach { risk ->
                                Text(
                                    "• ${risk.serviceName}" +
                                        listOfNotNull(
                                            if (risk.staged) "staged" else null,
                                            if (risk.unstaged) "unstaged" else null,
                                            if (risk.untracked) "untracked" else null,
                                            risk.operationInProgress,
                                            risk.statusCheckError?.let { "status check failed: $it" },
                                        ).joinToString(prefix = "（", postfix = "）", separator = ", "),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = discardConfirmed,
                            onCheckedChange = { discardConfirmed = it },
                        )
                        Text("确认丢弃未提交改动")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onDelete(risks.isNotEmpty()) },
                enabled = canDelete,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text("删除任务")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
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
        it.status != WorkspaceStatus.ARCHIVED &&
            it.status != WorkspaceStatus.FAILED &&
            Path.of(it.worktreePath).toFile().isDirectory
    }
    var selected by remember(task.folderName) {
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
                    "每个服务会独立自动预检并构建；某个服务失败不会中断其他服务。",
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
            ) { Text("构建 ${selected.size} 个服务") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        shape = RoundedCornerShape(20.dp),
    )
}

@Composable
private fun BatchTagResultDialog(
    results: List<TagOperation>,
    requirementLink: String,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val successes = results.filter {
        it.state == TagOperationState.SUCCESS && !it.tag.isNullOrBlank()
    }
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
                if (successes.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(11.dp),
                    ) {
                        Text(
                            TagOutputFormatter.format(
                                requirementLink = requirementLink,
                                operations = successes,
                                includeFailures = false,
                            ),
                            Modifier.padding(13.dp),
                        )
                    }
                }
                if (successes.isEmpty()) results.forEach { result ->
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
private fun BranchInfoDialog(
    task: TaskManifest,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val branches = task.services.joinToString("\n") { workspace ->
        "${workspace.serviceName}：${workspace.branch}"
    }.ifBlank { "暂无服务" }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.AccountTree, null, tint = BrandBlue, modifier = Modifier.size(38.dp)) },
        title = { Text("分支信息", fontWeight = FontWeight.Bold) },
        text = {
            Surface(
                Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(11.dp),
            ) {
                Text(branches, Modifier.padding(13.dp), fontWeight = FontWeight.Medium)
            }
        },
        confirmButton = { Button(onClick = onCopy) { Text("复制") } },
        shape = RoundedCornerShape(20.dp),
    )
}

@Composable
private fun TagResultDialog(
    result: TagOperation,
    requirementLink: String,
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
                            if (success) {
                                TagOutputFormatter.format(
                                    requirementLink = requirementLink,
                                    operations = listOf(result),
                                    includeFailures = false,
                                )
                            } else {
                                result.message ?: "${result.serviceName}：${result.state}"
                            },
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
