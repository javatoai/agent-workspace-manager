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
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

@Composable
internal fun TagScreen(controller: DesktopApplication) {
    var query by remember { mutableStateOf("") }
    var onlyProblems by remember { mutableStateOf(false) }
    val problemCount = controller.tagHistory.count(::tagOperationIsProblem)
    val visibleHistory = controller.tagHistory.filter { operation ->
        (!onlyProblems || tagOperationIsProblem(operation)) && tagHistoryMatchesQuery(operation, query)
    }
    Column(
        Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (controller.tagHistory.isEmpty()) {
            EmptyState("还没有构建记录", "进入研发任务，在对应工作区点击“Tag”。", "前往研发任务") { controller.navigation = NavigationItem.TASKS }
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
                placeholder = { Text("按服务、任务、Tag 或分支筛选") },
                singleLine = true,
            )
            FilterChip(
                selected = onlyProblems,
                onClick = { onlyProblems = !onlyProblems },
                label = { Text("仅问题${if (problemCount > 0) " ($problemCount)" else ""}") },
            )
        }
        Text(
            "${visibleHistory.size} / ${controller.tagHistory.size} 条构建记录",
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
                    itemsIndexed(visibleHistory, key = { _, operation -> operation.operationId }) { index, operation ->
                        TagHistoryRow(controller, operation)
                        if (index < visibleHistory.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
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
)

@Composable
private fun TagHistoryRow(controller: DesktopApplication, operation: TagOperation) {
    val isProblem = tagOperationIsProblem(operation)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Sell, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${operation.serviceName} · ${operation.tag ?: "尚未生成 Tag"}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                StatusPill(operation.state.name)
            }
            Text(
                "${operation.folderName} · ${operation.sourceBranch}" +
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
            Text(operation.updatedAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ActionIconButton("复制 Tag 构建记录", onClick = {
            val copy = operation.tag?.let { "${operation.serviceName} · $it" } ?: buildString {
                append("服务名："); append(operation.serviceName); append('\n')
                append("状态："); append(operation.state.name)
                operation.message?.takeIf(String::isNotBlank)?.let { append('\n'); append("说明："); append(it) }
            }
            controller.copyText(copy, "构建记录已复制")
        }) { Icon(Icons.Outlined.ContentCopy, "复制构建记录") }
    }
}
