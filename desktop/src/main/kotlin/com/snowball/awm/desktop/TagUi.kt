package com.snowball.awm.desktop

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun TagScreen(controller: DesktopApplication) {
    LazyColumn(
        Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        item {
            SectionHeader("Tag 构建历史", "构建操作请在研发任务的工作区行中执行；这里仅保留结果记录")
        }
        if (controller.tagHistory.isEmpty()) {
            item { EmptyState("还没有 Tag 构建历史", "进入研发任务，在对应工作区点击“Tag”。", "前往研发任务") { controller.navigation = NavigationItem.TASKS } }
        } else {
            items(controller.tagHistory, key = { it.operationId }) { operation ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(11.dp)) {
                            Icon(Icons.Outlined.Sell, null, Modifier.padding(9.dp).size(19.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("${operation.serviceName} · ${operation.tag ?: "尚未生成 Tag"}", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${operation.folderName} · ${operation.sourceBranch}" +
                                    (operation.targetBranch?.let { " → ${operation.remote}/$it" } ?: " · 当前分支"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            operation.message?.takeIf(String::isNotBlank)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(operation.updatedAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        StatusPill(operation.state.name)
                        ActionIconButton("复制 Tag 构建记录", onClick = {
                            val copy = buildString {
                                append("服务名："); append(operation.serviceName); append('\n')
                                operation.tag?.let { append("Tag："); append(it) } ?: run {
                                    append("状态："); append(operation.state.name)
                                    operation.message?.takeIf(String::isNotBlank)?.let { append('\n'); append("说明："); append(it) }
                                }
                            }
                            controller.copyText(copy, "构建记录已复制")
                        }) { Icon(Icons.Outlined.ContentCopy, "复制构建记录") }
                    }
                }
            }
        }
    }
}
