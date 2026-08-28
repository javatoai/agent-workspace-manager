package com.snowball.awm.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.snowball.awm.core.TagOperation
import com.snowball.awm.core.TagOperationState
import com.snowball.awm.core.TagHistoryItem
import com.snowball.awm.core.TagWorkspaceCheck
import com.snowball.awm.core.userFacingLabel

@Composable
internal fun TagScreen(controller: DesktopApplication) {
    DisposableEffect(controller) {
        controller.setGenbuTagProbeVisible(true)
        onDispose { controller.setGenbuTagProbeVisible(false) }
    }
    var query by remember { mutableStateOf("") }
    var onlyProblems by remember { mutableStateOf(false) }
    var selectedOperationIds by remember { mutableStateOf(emptySet<String>()) }
    var showDeleteSelectedConfirmation by remember { mutableStateOf(false) }
    val problemCount = controller.tagHistory.count(::tagOperationIsProblem)
    val visibleHistory = filterTagHistoryItems(controller.tagHistoryItems, query, onlyProblems)
    val visibleOperationCount = visibleHistory.sumOf(FilteredTagHistoryItem::visibleOperationCount)
    val visibleOperationIds = visibleTagOperationIds(visibleHistory)
    LaunchedEffect(query, onlyProblems) { selectedOperationIds = emptySet() }
    if (showDeleteSelectedConfirmation) {
        ConfirmDialog(
            title = "删除 ${selectedOperationIds.size} 条Tag构建记录",
            message = "将永久删除选中的本地Tag构建记录及历史汇总。不会删除 Git Tag、代码、任务或需求资料目录。",
            confirmLabel = "删除所选",
            destructive = true,
            enabled = !controller.busy,
            onDismiss = { showDeleteSelectedConfirmation = false },
            onConfirm = {
                if (controller.deleteTagHistory(selectedOperationIds)) {
                    selectedOperationIds = emptySet()
                    showDeleteSelectedConfirmation = false
                }
            },
        )
    }
    Column(
        Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (controller.enabledGenbuProbeServiceCount == 0) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Text(
                    "Genbu 探测尚未启用：请在“服务仓库 → 编辑服务 → 基本信息”中为需要的服务开启。",
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (controller.tagHistory.isEmpty()) {
            EmptyState("还没有构建记录", "进入研发任务，在对应工作区点击“测试Tag”。", "前往研发任务") { controller.navigation = NavigationItem.TASKS }
            return@Column
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                label = { Text("筛选构建记录") },
                placeholder = { Text("按服务、任务、测试Tag或分支筛选") },
                singleLine = true,
            )
            FilterChip(
                selected = onlyProblems,
                onClick = { onlyProblems = !onlyProblems },
                label = { Text("仅问题${if (problemCount > 0) " ($problemCount)" else ""}") },
            )
            OutlinedButton(
                onClick = controller::refreshGenbuTagProbes,
                enabled = controller.enabledGenbuProbeServiceCount > 0 && !controller.isGenbuTagProbeRefreshing,
            ) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (controller.isGenbuTagProbeRefreshing) "刷新中…" else "刷新 Genbu")
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "已选 ${selectedOperationIds.size} 条",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { selectedOperationIds = visibleOperationIds },
                enabled = visibleOperationIds.isNotEmpty() && !controller.busy,
            ) { Text("全选") }
            OutlinedButton(
                onClick = { selectedOperationIds = emptySet() },
                enabled = selectedOperationIds.isNotEmpty() && !controller.busy,
            ) { Text("全不选") }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { showDeleteSelectedConfirmation = true },
                enabled = selectedOperationIds.isNotEmpty() && !controller.busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("删除所选 (${selectedOperationIds.size})") }
        }
        Text(
            "$visibleOperationCount / ${controller.tagHistory.size} 条构建记录 · ${visibleHistory.size} 个展示项",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedCard(
            Modifier.fillMaxWidth().weight(1f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            if (visibleHistory.isEmpty()) {
                Text(
                    "没有匹配的构建记录。",
                    Modifier.padding(18.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(visibleHistory, key = { _, item -> item.key }) { index, item ->
                        TagHistoryGroupCard(
                            controller = controller,
                            group = item.item,
                            visibleOperations = item.visibleOperations,
                            selectedOperationIds = selectedOperationIds,
                            onSelectionChanged = { operationIds, selected ->
                                selectedOperationIds = if (selected) selectedOperationIds + operationIds else selectedOperationIds - operationIds
                            },
                        )
                        if (index < visibleHistory.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

internal data class FilteredTagHistoryItem(
    val item: TagHistoryItem,
    val visibleOperations: List<TagOperation>,
) {
    val key: String get() = "tag-group-${item.groupId}"

    val visibleOperationCount: Int get() = visibleOperations.size
}

internal fun filterTagHistoryItems(
    items: List<TagHistoryItem>,
    query: String,
    onlyProblems: Boolean,
): List<FilteredTagHistoryItem> = items.mapNotNull { item ->
    val matchingOperations = item.operations.filter { operation ->
        (!onlyProblems || tagOperationIsProblem(operation)) && tagHistoryMatchesQuery(operation, query)
    }
    matchingOperations.takeIf { it.isNotEmpty() }?.let { FilteredTagHistoryItem(item, it) }
}

internal fun visibleTagOperationIds(items: List<FilteredTagHistoryItem>): Set<String> =
    items.flatMap(FilteredTagHistoryItem::visibleOperations).map(TagOperation::operationId).toSet()

internal fun groupTagState(operations: List<TagOperation>): TagOperationState = when {
    operations.any { it.state == TagOperationState.CONFLICT } -> TagOperationState.CONFLICT
    operations.any { it.state == TagOperationState.FAILED } -> TagOperationState.FAILED
    operations.any { it.state == TagOperationState.PARTIAL } -> TagOperationState.PARTIAL
    operations.isNotEmpty() && operations.all { it.state == TagOperationState.SUCCESS } -> TagOperationState.SUCCESS
    else -> operations.maxByOrNull(TagOperation::updatedAt)?.state ?: TagOperationState.CREATED
}

internal fun groupAnnouncementOperations(group: TagHistoryItem): List<TagOperation> =
    group.operations.sortedWith(compareBy<TagOperation> { it.createdAt }.thenBy { it.operationId })

@Composable
private fun TagHistoryGroupCard(
    controller: DesktopApplication,
    group: TagHistoryItem,
    visibleOperations: List<TagOperation>,
    selectedOperationIds: Set<String>,
    onSelectionChanged: (Set<String>, Boolean) -> Unit,
) {
    val problemCount = group.operations.count(::tagOperationIsProblem)
    val successCount = group.operations.count { it.state == TagOperationState.SUCCESS }
    val visibleOperationIds = visibleOperations.map(TagOperation::operationId).toSet()
    val selected = visibleOperationIds.isNotEmpty() && visibleOperationIds.all { it in selectedOperationIds }
    val copyingAnnouncement = controller.isTagAnnouncementCopying(group.groupId)
    val isBatch = group.batchId != null
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { checked -> onSelectionChanged(visibleOperationIds, checked) },
                )
                Spacer(Modifier.width(5.dp))
                Icon(Icons.Outlined.Sell, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(11.dp))
                Text(
                    "${group.folderName} · ${if (isBatch) "批量测试Tag" else "测试Tag"}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                StatusPill(groupTagState(group.operations).userFacingLabel())
                ActionIconButton(
                    "复制测试Tag发版信息",
                    onClick = { controller.copyTagHistoryGroupAnnouncement(group) },
                    modifier = Modifier.size(30.dp),
                    loading = copyingAnnouncement,
                ) {
                    Icon(Icons.Outlined.ContentCopy, "复制测试Tag发版信息", Modifier.size(16.dp))
                }
            }
            Text(
                buildString {
                    if (isBatch) append("${group.operations.size} 个服务 · 成功 $successCount")
                    else {
                        val operation = group.operations.singleOrNull()
                        append(operation?.serviceName.orEmpty())
                        operation?.tag?.takeIf(String::isNotBlank)?.let { append(" · ").append(it) }
                    }
                    if (problemCount > 0) append(" · 问题 $problemCount")
                    if (visibleOperations.size != group.operations.size) append(" · 当前显示 ${visibleOperations.size}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(group.createdAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            visibleOperations.forEachIndexed { index, operation ->
                TagHistoryRow(
                    controller = controller,
                    operation = operation,
                    showFolderName = false,
                    selectable = false,
                )
                if (index < visibleOperations.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

internal fun tagHistoryMatchesQuery(operation: TagOperation, query: String): Boolean {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return true
    return listOf(
        operation.serviceName,
        operation.folderName,
        operation.tag.orEmpty(),
        operation.sourceBranch,
        operation.targetBranch.orEmpty(),
        operation.message.orEmpty(),
    ).any { it.contains(normalizedQuery, ignoreCase = true) }
}

internal fun tagOperationIsProblem(operation: TagOperation): Boolean = operation.state in setOf(
    TagOperationState.CONFLICT,
    TagOperationState.FAILED,
    TagOperationState.PARTIAL,
    TagOperationState.CREATED,
    TagOperationState.PREFLIGHT_PASSED,
    TagOperationState.SOURCE_BRANCH_PUSHED,
)

internal fun tagOperationIsRetryableInterrupted(operation: TagOperation): Boolean = operation.state in setOf(
    TagOperationState.CREATED,
    TagOperationState.PREFLIGHT_PASSED,
    TagOperationState.SOURCE_BRANCH_PUSHED,
)

internal fun tagOperationRecordCopyText(operation: TagOperation): String =
    operation.tag?.let { "${operation.serviceName} · $it" } ?: buildString {
        append("服务名："); append(operation.serviceName); append('\n')
        append("状态："); append(operation.state.userFacingLabel())
        operation.message?.takeIf(String::isNotBlank)?.let { append('\n'); append("说明："); append(it) }
    }

@Composable
private fun TagHistoryRow(
    controller: DesktopApplication,
    operation: TagOperation,
    showFolderName: Boolean = true,
    selectable: Boolean = true,
    selected: Boolean = false,
    onSelectionChanged: (Boolean) -> Unit = {},
) {
    val isProblem = tagOperationIsProblem(operation)
    val copyRecord: () -> Unit = {
        controller.copyText(tagOperationRecordCopyText(operation), "构建记录已复制")
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectable) {
            Checkbox(selected, onSelectionChanged)
            Spacer(Modifier.width(5.dp))
        }
        Icon(Icons.Outlined.Sell, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${operation.serviceName} · ${operation.tag ?: "尚未生成测试Tag"}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                StatusPill(operation.state.userFacingLabel())
                genbuTagStatusLabels(operation, controller.isGenbuProbeEnabled(operation)).forEach { label ->
                    Spacer(Modifier.width(4.dp))
                    StatusPill(label)
                }
                Spacer(Modifier.width(2.dp))
                ActionIconButton("复制Tag构建记录", onClick = copyRecord, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Outlined.ContentCopy, "复制构建记录", Modifier.size(16.dp))
                }
            }
            Text(
                "${if (showFolderName) "${operation.folderName} · " else ""}${operation.sourceBranch}" +
                    (operation.targetBranch?.let { " → ${operation.remote}/$it" } ?: " · 当前分支"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            operation.message?.takeIf(String::isNotBlank)?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (isProblem) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (operation.state == TagOperationState.CONFLICT) {
                TagConflictActions(
                    controller = controller,
                    operation = operation,
                )
            } else if (tagOperationCanInspectWorkspace(operation)) {
                TagWorkspaceCheckActions(controller, operation)
            }
            if (tagOperationIsRetryableInterrupted(operation)) {
                TagInterruptedActions(controller, operation)
            }
            operation.genbuStatus.failureReason?.takeIf(String::isNotBlank)?.let { reason ->
                Text(
                    "Genbu 探测失败：$reason",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (operation.genbuStatus.stoppedByNewerRelease) {
                Text(
                    "后续测试Tag已发布，停止探测",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(operation.updatedAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TagInterruptedActions(controller: DesktopApplication, operation: TagOperation) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "上次Tag构建在源分支推送后被中断，尚未合并目标分支或创建测试Tag。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        OutlinedButton(
            onClick = { controller.retryInterruptedTag(operation) },
            enabled = !controller.busy,
        ) {
            Icon(Icons.Outlined.Refresh, null, Modifier.size(17.dp))
            Spacer(Modifier.width(5.dp))
            Text("重新构建测试Tag")
        }
    }
}

@Composable
private fun TagConflictActions(
    controller: DesktopApplication,
    operation: TagOperation,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            tagConflictGuidance(operation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            tagConflictFilesSummary(operation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { controller.openConflictWorkspace(operation) },
                enabled = !controller.busy,
            ) {
                Icon(Icons.Outlined.Code, null, Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text("打开 IDE 解决冲突")
            }
            OutlinedButton(
                onClick = { controller.inspectConflictWorkspace(operation) },
                enabled = !controller.busy,
            ) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text("重新检测工作区")
            }
            Button(
                onClick = { controller.retryConflict(operation) },
                enabled = !controller.busy,
            ) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(17.dp))
                Spacer(Modifier.width(5.dp))
                Text("已解决，重新构建测试Tag")
            }
        }
        TagWorkspaceCheckResult(controller.conflictWorkspaceCheck(operation))
    }
}

@Composable
private fun TagWorkspaceCheckActions(controller: DesktopApplication, operation: TagOperation) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(
            onClick = { controller.inspectConflictWorkspace(operation) },
            enabled = !controller.busy,
        ) {
            Icon(Icons.Outlined.Refresh, null, Modifier.size(17.dp))
            Spacer(Modifier.width(5.dp))
            Text("重新检测工作区")
        }
        TagWorkspaceCheckResult(controller.conflictWorkspaceCheck(operation))
    }
}

@Composable
private fun TagWorkspaceCheckResult(check: TagWorkspaceCheck?) {
    check ?: return
    Text(
        tagWorkspaceCheckSummary(check),
        style = MaterialTheme.typography.bodySmall,
        color = if (check.clean) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
    )
}

internal fun tagConflictGuidance(operation: TagOperation): String {
    val target = operation.targetBranch?.let { "${operation.remote}/$it" } ?: "目标分支"
    return "请将 ${operation.sourceBranch} 合入 $target，解决冲突后提交并推送 $target，再点击“已解决，重新构建测试Tag”。"
}

internal fun tagConflictFilesSummary(operation: TagOperation): String =
    operation.conflictFiles.takeIf { it.isNotEmpty() }
        ?.joinToString("、", prefix = "冲突文件：")
        ?: "冲突文件：未返回具体文件"

internal fun tagOperationCanInspectWorkspace(operation: TagOperation): Boolean =
    operation.state == TagOperationState.CONFLICT ||
        (operation.state == TagOperationState.FAILED && operation.message.orEmpty().contains("特性工作区存在未提交改动"))

internal fun tagWorkspaceCheckSummary(check: TagWorkspaceCheck): String =
    if (check.clean) "工作区已干净，可以重新构建测试Tag"
    else check.changes.joinToString("；", prefix = "仍有未提交改动：")

internal fun genbuTagStatusLabels(operation: TagOperation, probeEnabled: Boolean = false): List<String> = buildList {
    val status = operation.genbuStatus
    if (status.notFound) {
        add("未在Genbu中找到")
        return@buildList
    }
    if (status.failureReason != null) return@buildList
    when {
        status.released -> {
            add("已构建")
            add("UAT已发布")
        }
        status.built -> {
            add("已构建")
            add("UAT未发布")
        }
        probeEnabled || status.checkedAt != null -> {
            add("构建中")
            add("UAT未发布")
        }
    }
    if (status.productionReleased) add("已生产发布")
}
