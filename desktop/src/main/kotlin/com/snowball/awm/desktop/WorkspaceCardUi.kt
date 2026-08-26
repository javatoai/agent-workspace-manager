package com.snowball.awm.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Commit
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Publish
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.snowball.awm.core.LocalPushState
import com.snowball.awm.core.ServiceWorkspace
import com.snowball.awm.core.TaskManifest
import com.snowball.awm.core.WorkspaceGitHealth
import com.snowball.awm.core.WorkspaceGitHealthState
import com.snowball.awm.core.WorkspaceGitIssue
import com.snowball.awm.core.WorkspaceHealth
import com.snowball.awm.core.WorkspaceStrategy
import com.snowball.awm.core.health

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
                showAddModule = showAddModule,
                onAddModule = onAddModule,
                canDeleteModule = task.services.size > 1,
                onDeleteModule = onDeleteModule,
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
    health: WorkspaceGitHealth?,
    displayedBranch: String,
    branchVerified: Boolean,
    modifier: Modifier,
) {
    val statusPlacement = workspaceStatusPlacement(health)
    var confirmRerunBootstrap by remember(workspace.worktreePath) { mutableStateOf(false) }
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
    showAddModule: Boolean,
    onAddModule: () -> Unit,
    canDeleteModule: Boolean,
    onDeleteModule: () -> Unit,
    modifier: Modifier,
) {
    val actualBranch = controller.workspaceGitHealth[controller.workspaceKey(workspace)]?.actualBranch ?: workspace.branch
    val writeBlocked = controller.config.blockedGitWriteBranches.any { it.equals(actualBranch, ignoreCase = true) }
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
            enabled = !controller.busy && !writeBlocked,
            scopeLabel = workspace.moduleName.ifBlank { workspace.serviceName },
            loading = controller.busy && controller.activeOperation?.contains(workspace.moduleName.ifBlank { workspace.serviceName }) == true,
            onCommit = onCommit,
            onCommitAndPush = onCommitAndPush,
            onPush = { controller.pushWorkspace(task, workspace) },
        )
        if (controller.canBuildTag(task, workspace)) {
            IconActionGroup {
                ActionIconButton("构建 Tag", { controller.buildTag(task, workspace) }, Modifier.size(34.dp), enabled = !controller.busy, loading = controller.busy && controller.activeOperation?.contains(workspace.moduleName.ifBlank { workspace.serviceName }) == true && controller.activeOperation?.contains("Tag") == true) {
                    Icon(Icons.Outlined.Sell, "Tag", Modifier.size(18.dp))
                }
            }
        }
        IconActionGroup {
            if (showAddModule) ActionIconButton("为服务添加模块", onAddModule, Modifier.size(34.dp), enabled = !controller.busy) {
                Icon(Icons.Outlined.Add, "添加模块", Modifier.size(18.dp))
            }
            ActionIconButton("删除当前模块", onDeleteModule, Modifier.size(34.dp), enabled = canDeleteModule && !controller.busy) {
                Icon(Icons.Outlined.Delete, "删除模块", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
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
internal fun IconActionGroup(content: @Composable RowScope.() -> Unit) {
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

@Composable
internal fun GitActionIconGroup(
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
