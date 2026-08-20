package com.snowball.awm.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.snowball.awm.core.GroupConfig
import com.snowball.awm.core.GroupServiceConfig
import com.snowball.awm.core.RepositoryConfig
import com.snowball.awm.core.WorkspaceStrategy

@Composable
internal fun ServicesScreen(controller: DesktopApplication) {
    var editTarget by remember { mutableStateOf<Pair<String, GroupServiceConfig>?>(null) }
    var removeTarget by remember { mutableStateOf<Pair<String, GroupServiceConfig>?>(null) }
    var addToGroup by remember { mutableStateOf<String?>(null) }
    var selectedGroupId by remember { mutableStateOf(controller.config.groups.firstOrNull()?.id) }
    val groupIds = controller.config.groups.map(GroupConfig::id)
    LaunchedEffect(groupIds) {
        selectedGroupId = resolveServiceGroupSelection(selectedGroupId, groupIds)
    }
    val group = controller.config.groups.firstOrNull { it.id == selectedGroupId } ?: return
    val showGroupNavigation = serviceGroupNavigationVisible(groupIds.size)
    Row(
        Modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (showGroupNavigation) {
            Surface(
                Modifier.width(250.dp).fillMaxHeight(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                LazyColumn(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(controller.config.groups, key = GroupConfig::id) { candidate ->
                        val selected = candidate.id == group.id
                        Surface(
                            Modifier.fillMaxWidth().clickable { selectedGroupId = candidate.id },
                            color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    candidate.name,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    "${candidate.services.size} 个服务",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        LazyColumn(
            if (showGroupNavigation) Modifier.weight(1f).fillMaxHeight() else Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "group-${group.id}") {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.36f),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), shape = RoundedCornerShape(11.dp)) {
                        Icon(Icons.Outlined.Dns, null, Modifier.padding(9.dp).size(19.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(group.name, style = MaterialTheme.typography.titleMedium)
                        Text("${group.services.size} 个服务 · Tag ${if (group.tagEnabled) "已开启" else "已关闭"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = { addToGroup = group.id }) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text("添加仓库") }
                }
            }
            }
            if (group.services.isEmpty()) item(key = "empty-${group.id}") {
                OutlinedCard(Modifier.fillMaxWidth(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(9.dp))
                        Text("该组还没有服务，添加一个 Git 仓库开始配置。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(group.services, key = { "${group.id}-${it.id}" }) { service ->
                val index = group.services.indexOfFirst { it.id == service.id }
                val repository = controller.config.repositories.firstOrNull { it.id == service.repositoryId }
                ServiceCard(service, repository, index > 0, index in 0 until group.services.lastIndex,
                    onEdit = { editTarget = group.id to service },
                    onUp = { controller.moveService(group.id, service.id, -1) },
                    onDown = { controller.moveService(group.id, service.id, 1) },
                    onRemove = { removeTarget = group.id to service })
            }
        }
    }
    addToGroup?.let { groupId -> AddRepositoryDialog(controller, onDismiss = { addToGroup = null }) { paths ->
        controller.settingsController.addRepositories(groupId, paths) { addToGroup = null }
    } }
    editTarget?.let { (groupId, service) -> ServiceEditorDialog(controller, service, onDismiss = { editTarget = null }) {
        controller.settingsController.updateService(groupId, it) { editTarget = null }
    } }
    removeTarget?.let { (groupId, service) ->
        ConfirmDialog(
            title = "从组中移除服务？",
            message = "将移除“${service.displayName}”在当前组中的配置，不会删除原仓库，也不会改动已有任务。仍被任务引用时操作会被安全阻止。",
            confirmLabel = "移除服务",
            destructive = true,
            enabled = !controller.settingsBusy,
            onDismiss = { removeTarget = null },
            onConfirm = { controller.removeService(groupId, service.id) { removeTarget = null } },
        )
    }
}

internal fun serviceGroupNavigationVisible(groupCount: Int): Boolean = groupCount > 1

internal fun resolveServiceGroupSelection(currentId: String?, groupIds: List<String>): String? =
    currentId?.takeIf(groupIds::contains) ?: groupIds.firstOrNull()

@Composable
private fun ServiceCard(
    service: GroupServiceConfig,
    repository: RepositoryConfig?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEdit: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    ElevatedCard(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
        Row(Modifier.padding(horizontal = 17.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                Icon(
                    if (service.modules.any { it.strategy == WorkspaceStrategy.STANDARD_WORKTREE }) Icons.Outlined.AccountTree else Icons.Outlined.ContentCopy,
                    null,
                    Modifier.padding(10.dp).size(21.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(service.displayName, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(8.dp))
                    MetaPill("混合模块")
                    if (!service.enabled) { Spacer(Modifier.width(6.dp)); MetaPill("已停用") }
                }
                Spacer(Modifier.height(3.dp))
                Text(repository?.rootPath ?: "仓库配置缺失", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${service.modules.count { it.strategy == WorkspaceStrategy.STANDARD_WORKTREE }} 个 Worktree · ${service.modules.count { it.strategy == WorkspaceStrategy.INDEPENDENT_CLONE }} 个克隆", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ActionIconButton("上移服务", onUp, enabled = canMoveUp) { Icon(Icons.Outlined.KeyboardArrowUp, "上移") }
            ActionIconButton("下移服务", onDown, enabled = canMoveDown) { Icon(Icons.Outlined.KeyboardArrowDown, "下移") }
            OutlinedButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("配置") }
            ActionIconButton("从当前组移除服务", onRemove) { Icon(Icons.Outlined.Delete, "移除", tint = MaterialTheme.colorScheme.error) }
        }
    }
}
