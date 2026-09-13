package com.snowball.awm.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.snowball.awm.core.AppConfig
import com.snowball.awm.core.DevelopmentToolType
import com.snowball.awm.core.LocalPushState
import com.snowball.awm.core.ServiceWorkspace
import com.snowball.awm.core.TaskManifest
import com.snowball.awm.core.WorkspaceGitHealth
import com.snowball.awm.core.WorkspaceGitHealthState
import com.snowball.awm.core.WorkspaceGitIssue
import com.snowball.awm.core.WorkspaceHealth
import com.snowball.awm.core.WorkspaceStrategy
import com.snowball.awm.core.health

internal const val WORKSPACE_CARD_SIDE_BY_SIDE_MIN_WIDTH_DP = 860f
private val WorkspaceCardSideActionChromeWidth = 86.dp
private val WorkspaceCardSummaryMinimumWidth = 300.dp

internal enum class WorkspaceCardLayout { SIDE_BY_SIDE, STACKED }

internal enum class WorkspaceStatusPlacement { SECOND_ROW, THIRD_ROW }

/** Wide cards place their action groups against the card's right edge. */
internal fun workspaceCardActionsAlignToEnd(layout: WorkspaceCardLayout): Boolean =
    layout == WorkspaceCardLayout.SIDE_BY_SIDE

/** Keeps the project/service identity visible when one service has multiple modules. */
internal fun workspaceCardTitle(serviceName: String, moduleName: String): String {
    val project = serviceName.trim()
    val module = moduleName.trim()
    return when {
        project.isBlank() -> module
        module.isBlank() || module == project -> project
        else -> "$project · $module"
    }
}

/** The title may include a module, but copying the project always uses the service name alone. */
internal fun workspaceProjectNameForCopy(serviceName: String): String = serviceName.trim()

internal data class WorkspaceBranchCopyAllocation(
    val branchWidth: Int,
    val copyX: Int,
)

internal fun workspaceCardLayout(availableWidthDp: Float): WorkspaceCardLayout = when {
    availableWidthDp >= WORKSPACE_CARD_SIDE_BY_SIDE_MIN_WIDTH_DP -> WorkspaceCardLayout.SIDE_BY_SIDE
    else -> WorkspaceCardLayout.STACKED
}

/** Keeps a short branch compact, while reserving its copy affordance when a long branch wraps. */
internal fun workspaceBranchCopyAllocation(
    availableWidth: Int,
    naturalBranchWidth: Int,
    copyWidth: Int,
    gapWidth: Int,
): WorkspaceBranchCopyAllocation {
    val branchCopyGap = if (naturalBranchWidth > 0 && copyWidth > 0) gapWidth else 0
    val maximumBranchWidth = (availableWidth - copyWidth - branchCopyGap).coerceAtLeast(0)
    val branchWidth = naturalBranchWidth.coerceIn(0, maximumBranchWidth)
    val copyX = branchWidth + if (branchWidth > 0 && copyWidth > 0) gapWidth else 0
    return WorkspaceBranchCopyAllocation(branchWidth, copyX)
}

internal fun workspaceStatusPlacement(health: WorkspaceGitHealth?): WorkspaceStatusPlacement =
    if (health?.state in setOf(WorkspaceGitHealthState.MISSING, WorkspaceGitHealthState.FAILED)) {
        WorkspaceStatusPlacement.THIRD_ROW
    } else {
        WorkspaceStatusPlacement.SECOND_ROW
    }

/** The card exposes one direct development-tool action: the workspace's own selection. */
internal data class WorkspaceToolbarPresentation(
    val showPathActionGroup: Boolean,
    val showGitActionGroup: Boolean,
    val showTagAction: Boolean,
    val showAddModuleAction: Boolean,
    val showRetryAction: Boolean,
    val developmentTool: DevelopmentToolType,
) {
    val developmentToolActionLabel: String
        get() = "使用 ${developmentTool.displayName} 打开"
}

internal fun workspaceToolbarPresentationFor(
    workspace: ServiceWorkspace,
    canBuildTag: Boolean,
    showAddModule: Boolean,
    config: AppConfig,
): WorkspaceToolbarPresentation = WorkspaceToolbarPresentation(
    showPathActionGroup = config.showWorkspacePathActionGroup,
    showGitActionGroup = config.showWorkspaceGitActionGroup,
    showTagAction = canBuildTag,
    showAddModuleAction = showAddModule,
    showRetryAction = workspace.health == WorkspaceHealth.FAILED && workspace.groupServiceId.isNotBlank(),
    developmentTool = workspace.developmentTool,
)

@Composable
internal fun WorkspaceCard(
    controller: DesktopApplication,
    task: TaskManifest,
    workspace: ServiceWorkspace,
    showAddModule: Boolean,
    onAddModule: () -> Unit,
    onDeleteModule: () -> Unit,
) {
    val health = controller.gitHealth(workspace)
    val displayedBranch = health?.actualBranch?.takeIf(String::isNotBlank) ?: workspace.branch
    val branchVerified = !health?.actualBranch.isNullOrBlank()
    var commitMode by remember { mutableStateOf<String?>(null) }
    var commitMessage by remember(task, workspace) { mutableStateOf(controller.defaultCommitMessage(task, workspace)) }
    OutlinedCard(
        Modifier.fillMaxWidth(),
        // The detail page already has a background. A plain card keeps a workspace distinct
        // without adding another large tinted slab to an otherwise narrow detail pane.
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val layout = workspaceCardLayout(maxWidth.value)
            val sideBySideActionMaxWidth =
                (maxWidth - WorkspaceCardSideActionChromeWidth - WorkspaceCardSummaryMinimumWidth)
                    .coerceAtLeast(0.dp)
            when (layout) {
                WorkspaceCardLayout.SIDE_BY_SIDE -> {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WorkspaceCardIdentity(workspace, Modifier.align(Alignment.Top))
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
                            onCommit = { controller.loadBatchGitPreviews(task); commitMessage = controller.defaultCommitMessage(task, workspace); commitMode = "commit" },
                            onCommitAndPush = { controller.loadBatchGitPreviews(task); commitMessage = controller.defaultCommitMessage(task, workspace); commitMode = "commitPush" },
                            showAddModule = showAddModule,
                            onAddModule = onAddModule,
                            canDeleteModule = task.services.size > 1,
                            onDeleteModule = onDeleteModule,
                            // The toolbar may use up to the available space, but should shrink
                            // to its actual icon groups so the summary receives the rest.
                            alignToEnd = workspaceCardActionsAlignToEnd(layout),
                            modifier = Modifier.widthIn(max = sideBySideActionMaxWidth),
                        )
                    }
                }

                WorkspaceCardLayout.STACKED -> {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            WorkspaceCardIdentity(workspace)
                            Spacer(Modifier.width(12.dp))
                            WorkspaceCardSummary(
                                controller = controller,
                                task = task,
                                workspace = workspace,
                                health = health,
                                displayedBranch = displayedBranch,
                                branchVerified = branchVerified,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        HorizontalDivider(Modifier.padding(top = 12.dp))
                        WorkspaceCardActions(
                            controller = controller,
                            task = task,
                            workspace = workspace,
                            onCommit = { controller.loadBatchGitPreviews(task); commitMessage = controller.defaultCommitMessage(task, workspace); commitMode = "commit" },
                            onCommitAndPush = { controller.loadBatchGitPreviews(task); commitMessage = controller.defaultCommitMessage(task, workspace); commitMode = "commitPush" },
                            showAddModule = showAddModule,
                            onAddModule = onAddModule,
                            canDeleteModule = task.services.size > 1,
                            onDeleteModule = onDeleteModule,
                            alignToEnd = workspaceCardActionsAlignToEnd(layout),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                }
            }
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
private fun WorkspaceCardIdentity(workspace: ServiceWorkspace, modifier: Modifier = Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
        Icon(
            if (workspace.strategy == WorkspaceStrategy.STANDARD_WORKTREE) Icons.Outlined.AccountTree else Icons.Outlined.ContentCopy,
            null,
            Modifier.padding(8.dp).size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun WorkspaceCardSummary(
    controller: DesktopApplication,
    task: TaskManifest,
    workspace: ServiceWorkspace,
    health: WorkspaceGitHealth?,
    displayedBranch: String,
    branchVerified: Boolean,
    modifier: Modifier,
) {
    val statusPlacement = workspaceStatusPlacement(health)
    val projectName = workspaceProjectNameForCopy(workspace.serviceName)
    val copyIcons = taskAreaCopyIconPresentationFor(controller.config)
    var confirmRerunBootstrap by remember(workspace.worktreePath) { mutableStateOf(false) }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WorkspaceBranchCopyRow(
                modifier = Modifier.weight(1f).heightIn(min = 28.dp),
                branch = {
                    Text(
                        workspaceCardTitle(workspace.serviceName, workspace.moduleName),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                copy = {
                    if (copyIcons.showProjectNameCopyIcons && projectName.isNotBlank()) {
                        ActionIconButton(
                            "复制项目名",
                            { controller.copyText(projectName, "项目名已复制") },
                            Modifier.size(28.dp),
                        ) {
                            Icon(Icons.Outlined.ContentCopy, "复制项目名", Modifier.size(14.dp))
                        }
                    }
                },
            )
            if (workspace.health != WorkspaceHealth.READY) WorkspaceCardStatusPill(workspace.health.name)
        }
        WorkspaceBranchCopyRow(
            modifier = Modifier.fillMaxWidth().heightIn(min = 26.dp),
            branch = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        workspace.strategy.displayName,
                        modifier = Modifier.widthIn(max = 84.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        " · ",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    SelectionContainer {
                        Text(
                            if (branchVerified) displayedBranch else "$displayedBranch（未验证）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            copy = {
                if (copyIcons.showBranchNameCopyIcons) {
                    ActionIconButton(
                        "复制分支名",
                        { controller.copyText(displayedBranch, "分支已复制") },
                        Modifier.size(28.dp),
                    ) { Icon(Icons.Outlined.ContentCopy, "复制分支名", Modifier.size(14.dp)) }
                }
            },
        )
        if (statusPlacement == WorkspaceStatusPlacement.SECOND_ROW) {
            WorkspaceGitStatusLine(health)
        }
        if (statusPlacement == WorkspaceStatusPlacement.THIRD_ROW && health != null) {
            FlowRow(
                Modifier.fillMaxWidth().heightIn(min = 30.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                WorkspaceCardProblemPill(workspaceIssueLabel(health))
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SelectionContainer(Modifier.weight(1f)) {
                    Text(workspace.warnings.joinToString("\n"), color = WarningAmber, style = MaterialTheme.typography.bodySmall)
                }
                ActionIconButton(
                    "重新执行 Bootstrap",
                    { confirmRerunBootstrap = true },
                    Modifier.size(28.dp),
                    enabled = !controller.busy,
                ) { Icon(Icons.Outlined.Refresh, "重新执行 Bootstrap", Modifier.size(15.dp), tint = WarningAmber) }
                ActionIconButton(
                    "清除警告（确认已知晓）",
                    { controller.clearWorkspaceWarnings(task, workspace) },
                    Modifier.size(28.dp),
                    enabled = !controller.busy,
                ) { Icon(Icons.Outlined.Close, "清除警告", Modifier.size(15.dp), tint = WarningAmber) }
            }
        }
        if (controller.config.blockedGitWriteBranches.any { it.equals(displayedBranch, ignoreCase = true) }) {
            Text(
                "Git 写保护：分支 $displayedBranch 禁止 Commit、Push 和 Commit & Push",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    if (confirmRerunBootstrap) {
        ConfirmDialog(
            title = "重新执行 Bootstrap",
            message = "将按当前服务配置对该工作区重新执行 Bootstrap 复制规则与命令；已有文件按规则覆盖，命令会重复执行一次。禁止覆盖的规则会因目标已存在而报警告。",
            confirmLabel = "重新执行",
            enabled = !controller.busy,
            onDismiss = { confirmRerunBootstrap = false },
        ) {
            confirmRerunBootstrap = false
            controller.rerunWorkspaceBootstrap(task, workspace)
        }
    }
}

@Composable
private fun WorkspaceBranchCopyRow(
    modifier: Modifier,
    branch: @Composable () -> Unit,
    copy: @Composable () -> Unit,
) {
    val gapWidth = with(LocalDensity.current) { 7.dp.roundToPx() }
    val minimumHeight = with(LocalDensity.current) { 26.dp.roundToPx() }
    Layout(
        modifier = modifier,
        content = {
            Box { branch() }
            Box { copy() }
        },
    ) { measurables, constraints ->
        val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val copyPlaceable = measurables[1].measure(looseConstraints)
        val copyGap = if (copyPlaceable.width > 0) gapWidth else 0
        val branchMaximumWidth = (constraints.maxWidth - copyPlaceable.width - copyGap).coerceAtLeast(0)
        val branchPlaceable = measurables[0].measure(looseConstraints.copy(maxWidth = branchMaximumWidth))
        val allocation = workspaceBranchCopyAllocation(
            availableWidth = constraints.maxWidth,
            naturalBranchWidth = branchPlaceable.width,
            copyWidth = copyPlaceable.width,
            gapWidth = copyGap,
        )
        val height = maxOf(minimumHeight, branchPlaceable.height, copyPlaceable.height)
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(constraints.maxWidth, height) {
            branchPlaceable.placeRelative(0, (height - branchPlaceable.height) / 2)
            copyPlaceable.placeRelative(allocation.copyX, (height - copyPlaceable.height) / 2)
        }
    }
}

@Composable
private fun WorkspaceGitStatusLine(health: WorkspaceGitHealth?) {
    val labels = workspaceGitStatusLabels(health)
    if (labels.isEmpty()) return
    Text(
        "Git · ${labels.joinToString(" · ")}",
        Modifier.fillMaxWidth().padding(top = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

internal fun workspaceGitStatusLabels(health: WorkspaceGitHealth?): List<String> = when {
    health == null || health.state == WorkspaceGitHealthState.CHECKING -> listOf("检查中")
    health.state == WorkspaceGitHealthState.READY -> listOf(
        if (health.dirtyFileCount == 0) "无未提交" else "${health.dirtyFileCount} 个文件未提交",
        when (health.pushState) {
            LocalPushState.PUSHED -> "已推送"
            LocalPushState.AHEAD -> "${health.unpushedCommitCount} 个提交未推送"
            LocalPushState.REMOTE_BRANCH_MISSING -> "未发现远程分支"
            LocalPushState.NO_UPSTREAM -> "未关联远程"
            LocalPushState.FAILED -> "检查失败"
        },
    )

    else -> emptyList()
}

@Composable
private fun WorkspaceCardStatusPill(text: String, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.statusColor(text)
    val label = when (text) {
        "CREATING" -> "创建中"
        "READY" -> "就绪"
        "READY_WITH_WARNINGS" -> "有警告"
        "FAILED" -> "失败"
        "ARCHIVED" -> "已归档"
        "SUCCESS" -> "成功"
        "CONFLICT" -> "有冲突"
        "PARTIAL" -> "部分完成"
        "CREATED", "PREFLIGHT_PASSED", "SOURCE_BRANCH_PUSHED" -> "构建已中断"
        else -> text
    }
    Surface(
        modifier.widthIn(max = 180.dp),
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, color.copy(alpha = 0.18f)),
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            color = color,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WorkspaceCardProblemPill(text: String, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.error
    Surface(
        modifier.widthIn(max = 260.dp),
        color = color.copy(alpha = 0.10f),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, color.copy(alpha = 0.25f)),
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            color = color,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun WorkspaceCardActions(
    controller: DesktopApplication,
    task: TaskManifest,
    workspace: ServiceWorkspace,
    onCommit: () -> Unit,
    onCommitAndPush: () -> Unit,
    showAddModule: Boolean,
    onAddModule: () -> Unit,
    canDeleteModule: Boolean,
    onDeleteModule: () -> Unit,
    alignToEnd: Boolean,
    modifier: Modifier,
) {
    val actualBranch = controller.workspaceGitHealth[controller.workspaceKey(workspace)]?.actualBranch ?: workspace.branch
    val writeBlocked = controller.config.blockedGitWriteBranches.any { it.equals(actualBranch, ignoreCase = true) }
    val workspaceLabel = workspace.moduleName.ifBlank { workspace.serviceName }
    val workspaceLoading = controller.busy && controller.activeOperation?.contains(workspaceLabel) == true
    val tagLoading = workspaceLoading && controller.activeOperation?.contains("Tag") == true
    val toolbarPresentation = workspaceToolbarPresentationFor(
        workspace = workspace,
        canBuildTag = controller.canBuildTag(task, workspace),
        showAddModule = showAddModule,
        config = controller.config,
    )
    FlowRow(
        modifier,
        horizontalArrangement = if (alignToEnd) {
            Arrangement.spacedBy(8.dp, Alignment.End)
        } else {
            Arrangement.spacedBy(8.dp)
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (toolbarPresentation.showPathActionGroup) {
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
        }
        if (toolbarPresentation.showGitActionGroup) {
            GitActionIconGroup(
                enabled = !controller.busy && !writeBlocked,
                scopeLabel = workspaceLabel,
                loading = workspaceLoading,
                onCommit = onCommit,
                onCommitAndPush = onCommitAndPush,
                onPush = { controller.pushWorkspace(task, workspace) },
            )
        }
        if (toolbarPresentation.showTagAction) {
            IconActionGroup {
                ActionIconButton(
                    "构建测试Tag",
                    { controller.buildTag(task, workspace) },
                    Modifier.size(34.dp),
                    enabled = !controller.busy,
                    loading = tagLoading,
                ) {
                    Icon(Icons.Outlined.Sell, "测试Tag", Modifier.size(18.dp))
                }
            }
        }
        IconActionGroup {
            if (toolbarPresentation.showAddModuleAction) {
                ActionIconButton("为服务添加模块", onAddModule, Modifier.size(34.dp), enabled = !controller.busy) {
                    Icon(Icons.Outlined.Add, "添加模块", Modifier.size(18.dp))
                }
            }
            ActionIconButton("删除当前模块", onDeleteModule, Modifier.size(34.dp), enabled = canDeleteModule && !controller.busy) {
                Icon(Icons.Outlined.Delete, "删除模块", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
            }
        }
        IconActionGroup {
            ActionIconButton(
                toolbarPresentation.developmentToolActionLabel,
                { controller.openWorkspace(workspace, toolbarPresentation.developmentTool) },
                Modifier.size(34.dp),
            ) {
                Icon(Icons.Outlined.Code, "打开开发工具", Modifier.size(18.dp))
            }
        }
        if (toolbarPresentation.showRetryAction) {
            IconActionGroup {
                ActionIconButton(
                    "重试创建失败的服务",
                    { controller.retryFailedServices(task, listOf(workspace.groupServiceId)) },
                    Modifier.size(34.dp),
                    enabled = !controller.busy,
                ) {
                    Icon(Icons.Outlined.Refresh, "重试", Modifier.size(18.dp))
                }
            }
        }
    }
}

internal fun workspaceIssueLabel(health: WorkspaceGitHealth): String =
    workspaceIssueLabel(health.issue, health.actualBranch, health.expectedBranch)

internal fun workspaceIssueDetail(health: WorkspaceGitHealth?): String? = health
    ?.takeIf {
        it.state in setOf(WorkspaceGitHealthState.MISSING, WorkspaceGitHealthState.FAILED) &&
            it.issue != WorkspaceGitIssue.BRANCH_MISMATCH
    }
    ?.message
    ?.takeIf(String::isNotBlank)

internal fun workspaceIssueLabel(issue: WorkspaceGitIssue, actual: String?, expected: String?): String = when (issue) {
    WorkspaceGitIssue.NONE -> "正常"
    WorkspaceGitIssue.MISSING -> "工作区不存在"
    WorkspaceGitIssue.NOT_GIT -> "不是有效的 Git 工作区"
    WorkspaceGitIssue.IDENTITY_MISMATCH -> "Git 仓库身份不匹配"
    WorkspaceGitIssue.BRANCH_MISMATCH -> "分支不一致：${actual.orEmpty()} → ${expected.orEmpty()}"
    WorkspaceGitIssue.DETACHED_HEAD -> "Detached HEAD"
    WorkspaceGitIssue.OPERATION_IN_PROGRESS -> "存在进行中的 Git 操作"
    WorkspaceGitIssue.INSPECTION_FAILED -> "Git 状态检查失败"
}
