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
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.rememberTooltipState
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
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.snowball.awm.core.AgentConflictResolution
import com.snowball.awm.core.BootstrapConfig
import com.snowball.awm.core.BootstrapPresets
import com.snowball.awm.core.GroupServiceConfig
import com.snowball.awm.core.BranchPrefixResolver
import com.snowball.awm.core.IdeType
import com.snowball.awm.core.IndependentCloneModuleConfig
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
import java.awt.Dimension
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.UUID


@Composable
internal fun BatchTagDialog(
    workspaces: List<ServiceWorkspace>,
    onDismiss: () -> Unit,
    onBuild: (List<ServiceWorkspace>) -> Unit,
) {
    var selected by remember(workspaces) { mutableStateOf(workspaces.map(ServiceWorkspace::selectionKey).toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量 UAT Tag") },
        text = {
            Column(Modifier.widthIn(min = 520.dp).heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                workspaces.forEach { workspace ->
                    val checked = workspace.selectionKey in selected
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            selected = if (checked) selected - workspace.selectionKey else selected + workspace.selectionKey
                        }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked, { selected = if (checked) selected - workspace.selectionKey else selected + workspace.selectionKey })
                        Column {
                            Text(workspace.moduleName.ifBlank { workspace.serviceName }, fontWeight = FontWeight.SemiBold)
                            Text("${workspace.serviceName} · ${workspace.branch}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onBuild(workspaces.filter { it.selectionKey in selected }) }, enabled = selected.isNotEmpty()) { Text("开始构建") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun BranchInfoDialog(content: String, onDismiss: () -> Unit, onCopy: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("分支信息") },
        text = {
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(11.dp)) {
                Text(content.ifBlank { "暂无分支信息" }, Modifier.padding(13.dp), fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = { Button(onClick = onCopy) { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("复制") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
internal fun AddRepositoryDialog(controller: DesktopApplication, onDismiss: () -> Unit, onAdd: (List<String>) -> Unit) {
    var paths by remember { mutableStateOf<List<String>>(emptyList()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量添加 Git 仓库") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("逐个校验所选目录，不递归扫描子目录；非 Git、Linked Worktree 和重复仓库会被跳过。")
                OutlinedButton(
                    onClick = { controller.chooseDirectories(paths.firstOrNull()) { paths = it } },
                    enabled = !controller.pathPickerBusy,
                ) {
                    Icon(Icons.Outlined.Folder, null)
                    Spacer(Modifier.width(6.dp))
                    Text("选择仓库目录（可多选）")
                }
                if (paths.isEmpty()) {
                    Text("尚未选择目录", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
                        Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(10.dp).verticalScroll(rememberScrollState())) {
                            paths.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                Text("新增服务默认采用标准 Worktree；添加后可在服务配置中改为独立克隆。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = { onAdd(paths) }, enabled = paths.isNotEmpty() && !controller.busy) { Text("校验并添加") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun ServiceEditorDialog(controller: DesktopApplication, service: GroupServiceConfig, onDismiss: () -> Unit, onSave: (GroupServiceConfig) -> Unit) {
    val json = remember { Json { prettyPrint = true; encodeDefaults = true } }
    val initialBootstrapText = remember(service) { json.encodeToString(service.bootstrap) }
    var name by remember { mutableStateOf(service.displayName) }
    var enabled by remember { mutableStateOf(service.enabled) }
    var ide by remember { mutableStateOf(service.ideType) }
    var strategy by remember { mutableStateOf(service.strategy) }
    var modules by remember { mutableStateOf(service.modules) }
    var cloneModules by remember { mutableStateOf(service.cloneModules.ifEmpty { listOf(IndependentCloneModuleConfig(id = "clone-default")) }) }
    var bootstrapText by remember { mutableStateOf(initialBootstrapText) }
    var bootstrapError by remember { mutableStateOf<String?>(null) }
    var showBootstrapExample by remember { mutableStateOf(false) }
    var bootstrapCopied by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val hasDraftChanges = name != service.displayName || enabled != service.enabled || ide != service.ideType ||
        strategy != service.strategy || modules != service.modules ||
        cloneModules != service.cloneModules.ifEmpty { listOf(IndependentCloneModuleConfig(id = "clone-default")) } ||
        bootstrapText != initialBootstrapText
    val requestDismiss = { if (hasDraftChanges) confirmDiscard = true else onDismiss() }
    Dialog(onDismissRequest = requestDismiss) {
        Surface(
            Modifier.widthIn(min = 780.dp, max = 920.dp).heightIn(min = 620.dp, max = 820.dp),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(11.dp)) {
                        Icon(Icons.Outlined.Settings, null, Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("服务配置", style = MaterialTheme.typography.titleLarge)
                        Text(service.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    MetaPill(strategy.displayName)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    Modifier.weight(1f).padding(20.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                SectionHeader("基础信息", "IDE 是系统建议，可手工修改；保存值始终作为最终依据")
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("展示名称") })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("启用服务", style = MaterialTheme.typography.titleSmall); Switch(enabled, { enabled = it })
                    Spacer(Modifier.width(8.dp))
                    IdeType.entries.forEach { value -> FilterChip(ide == value, { ide = value }, label = { Text(value.name) }) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SectionHeader("工作区策略", "决定新任务如何准备该服务的代码目录")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WorkspaceStrategy.entries.forEach { value -> FilterChip(strategy == value, { strategy = value }, label = { Text(value.displayName) }) }
                }
                if (strategy == WorkspaceStrategy.STANDARD_WORKTREE) {
                    Text("不同基础分支创建不同 Worktree；相同基础分支的代码模块在 AGENTS.md 中约定。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    modules.forEachIndexed { index, module ->
                        ModuleEditor(module, service.repositoryId, controller, canDelete = modules.size > 1, onChange = { changed -> modules = modules.mapIndexed { i, value -> if (i == index) changed else value } }, onDelete = { modules = modules.filterIndexed { i, _ -> i != index } })
                    }
                    OutlinedButton(onClick = {
                        modules = modules + ServiceModuleConfig(id = "module-${UUID.randomUUID()}", baseRef = "origin/master")
                    }) { Icon(Icons.Outlined.Add, null); Text("添加基础分支模块") }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Bootstrap JSON", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { bootstrapCopied = false; showBootstrapExample = true }) { Text("查看示例") }
                    }
                    OutlinedTextField(
                        bootstrapText,
                        { bootstrapText = it; bootstrapError = null },
                        Modifier.fillMaxWidth(),
                        label = { Text("copyRules 与 commands") },
                        minLines = 5,
                        isError = bootstrapError != null,
                        supportingText = bootstrapError?.let { message -> { Text(message) } },
                    )
                } else {
                    Text("独立克隆模块", fontWeight = FontWeight.SemiBold)
                    Text("选择该服务时会为以下每个固定分支创建独立目录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    cloneModules.forEachIndexed { index, module ->
                        CloneModuleEditor(
                            module = module,
                            repositoryId = service.repositoryId,
                            controller = controller,
                            canDelete = cloneModules.size > 1,
                            onChange = { changed -> cloneModules = cloneModules.mapIndexed { i, value -> if (i == index) changed else value } },
                            onDelete = { cloneModules = cloneModules.filterIndexed { i, _ -> i != index } },
                        )
                    }
                    OutlinedButton(onClick = {
                        cloneModules = cloneModules + IndependentCloneModuleConfig(id = "clone-${UUID.randomUUID()}")
                    }) { Icon(Icons.Outlined.Add, null); Text("添加克隆模块") }
                }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    Text("修改仅影响后续任务", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = requestDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val bootstrap = if (strategy == WorkspaceStrategy.STANDARD_WORKTREE) {
                            runCatching { json.decodeFromString<BootstrapConfig>(bootstrapText) }
                                .getOrElse { error -> bootstrapError = "JSON 格式错误：${error.message}"; return@Button }
                        } else service.bootstrap
                        val normalizedModules = if (strategy == WorkspaceStrategy.STANDARD_WORKTREE) modules.map { it.copy(name = it.name.trim()) } else emptyList()
                        runCatching {
                            service.copy(
                                displayName = name.trim(), enabled = enabled, ideType = ide, strategy = strategy,
                                modules = normalizedModules,
                                cloneModules = if (strategy == WorkspaceStrategy.INDEPENDENT_CLONE) {
                                    cloneModules.map { it.copy(name = it.name.trim(), branch = it.branch.trim(), uatRef = it.uatRef.trim()) }
                                } else emptyList(),
                                bootstrap = bootstrap,
                            )
                        }.onSuccess(onSave).onFailure { bootstrapError = it.message }
                    }, enabled = name.isNotBlank() && (strategy != WorkspaceStrategy.INDEPENDENT_CLONE || cloneModules.isNotEmpty() && cloneModules.all { it.branch.isNotBlank() }) && (strategy != WorkspaceStrategy.STANDARD_WORKTREE || modules.isNotEmpty())) { Icon(Icons.Outlined.Save, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("保存配置") }
                }
            }
        }
    }
    if (showBootstrapExample) {
        val example = remember { json.encodeToString(BootstrapPresets.example()) }
        Dialog(
            onDismissRequest = { showBootstrapExample = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                Modifier.width(820.dp).height(620.dp),
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Bootstrap JSON 示例", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        if (bootstrapCopied) Text("已复制", color = SuccessGreen, style = MaterialTheme.typography.labelMedium)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Surface(
                        Modifier.weight(1f).fillMaxWidth().padding(18.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            example,
                            Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showBootstrapExample = false }) { Text("关闭") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            controller.copyText(example, "Bootstrap 示例已复制")
                            bootstrapCopied = true
                        }) {
                            Icon(Icons.Outlined.ContentCopy, null)
                            Spacer(Modifier.width(5.dp))
                            Text("复制")
                        }
                    }
                }
            }
        }
    }
    if (confirmDiscard) {
        DiscardChangesDialog(
            title = "放弃服务配置修改？",
            message = "尚未保存的模块、分支、Tag 和 Bootstrap 配置将丢失。",
            onDismiss = { confirmDiscard = false },
            onDiscard = onDismiss,
        )
    }
}

@Composable
internal fun UatResultDialog(
    title: String,
    content: String,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            Modifier.widthIn(min = 680.dp, max = 860.dp).heightIn(min = 360.dp, max = 680.dp),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.fillMaxSize()) {
                Text(title, Modifier.padding(horizontal = 22.dp, vertical = 18.dp), style = MaterialTheme.typography.titleLarge)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Surface(
                    Modifier.weight(1f).fillMaxWidth().padding(18.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    SelectionContainer {
                        Text(
                            content,
                            Modifier.fillMaxSize().padding(14.dp)
                                .verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState()),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    OutlinedButton(onClick = onCopy) {
                        Icon(Icons.Outlined.ContentCopy, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("复制")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onDismiss) { Text("完成") }
                }
            }
        }
    }
}

@Composable
private fun CloneModuleEditor(
    module: IndependentCloneModuleConfig,
    repositoryId: String,
    controller: DesktopApplication,
    canDelete: Boolean,
    onChange: (IndependentCloneModuleConfig) -> Unit,
    onDelete: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(module.name, { onChange(module.copy(name = it)) }, Modifier.weight(1f), label = { Text("显示名称（可选）") })
                ActionIconButton("删除标准 Worktree 模块", onDelete, enabled = canDelete) {
                    Icon(Icons.Outlined.Delete, "删除模块")
                }
            }
            RemoteBranchPicker(module.branch, { onChange(module.copy(branch = it)) }, "固定远程分支", repositoryId, controller, Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) { Text("允许参与 UAT Tag", Modifier.weight(1f)); Switch(module.uatTagEnabled, { onChange(module.copy(uatTagEnabled = it)) }) }
            if (module.uatTagEnabled) {
                RemoteBranchPicker(module.uatRef, { onChange(module.copy(uatRef = it)) }, "UAT 目标分支", repositoryId, controller, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(module.initialUatTag.orEmpty(), { onChange(module.copy(initialUatTag = it.ifBlank { null })) }, Modifier.weight(1f), label = { Text("初始 Tag（可选）") })
                    OutlinedTextField(module.tagMessagePrefix, { onChange(module.copy(tagMessagePrefix = it)) }, Modifier.weight(1f), label = { Text("Tag 消息前缀") })
                }
            }
        }
    }
}

/** UI-only branch chooser: all Git I/O remains in DesktopApplication. */
@Composable
private fun RemoteBranchPicker(value: String, onValueChange: (String) -> Unit, label: String, repositoryId: String, controller: DesktopApplication, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val remote = runCatching { RemoteBranchRef.parse(value.trim()).remote }.getOrDefault("origin")
    val state = controller.remoteBranchState(repositoryId, remote)
    Box(modifier) {
        OutlinedTextField(value, onValueChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
            trailingIcon = {
                ActionIconButton("搜索并选择远程分支", {
                    controller.loadRemoteBranches(repositoryId, remote)
                    query = ""
                    expanded = true
                }) { Icon(Icons.Outlined.KeyboardArrowDown, "选择远程分支") }
            })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.widthIn(min = 560.dp, max = 720.dp), containerColor = MaterialTheme.colorScheme.surfaceVariant, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 8.dp), label = { Text("搜索远程分支") }, singleLine = true)
            when (state) {
                RemoteBranchesState.Loading -> DropdownMenuItem(text = { Text("正在读取远程分支…") }, onClick = {}, enabled = false)
                is RemoteBranchesState.Failed -> DropdownMenuItem(text = { Text("加载失败：${state.message}") }, onClick = { controller.loadRemoteBranches(repositoryId, remote, true) })
                is RemoteBranchesState.Loaded -> {
                    val branches = RemoteBranchSearch.filter(state.branches, query)
                    if (branches.isEmpty()) DropdownMenuItem(text = { Text("没有匹配分支") }, onClick = {}, enabled = false)
                    branches.forEach { branch -> DropdownMenuItem(text = { Text(branch) }, onClick = { onValueChange(branch); expanded = false }) }
                }
                RemoteBranchesState.Idle -> DropdownMenuItem(text = { Text("正在准备读取远程分支…") }, onClick = {}, enabled = false)
            }
        }
    }
}

@Composable
private fun ModuleEditor(module: ServiceModuleConfig, repositoryId: String, controller: DesktopApplication, canDelete: Boolean, onChange: (ServiceModuleConfig) -> Unit, onDelete: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    module.name,
                    { onChange(module.copy(name = it)) },
                    Modifier.weight(1f),
                    label = { Text("显示名称（可选）") },
                    placeholder = { Text("单分支默认服务名，多分支默认基础分支末段") },
                )
                RemoteBranchPicker(module.baseRef, { onChange(module.copy(baseRef = it)) }, "基础分支", repositoryId, controller, Modifier.weight(1f))
                ActionIconButton("删除独立克隆模块", onDelete, enabled = canDelete) {
                    Icon(Icons.Outlined.Delete, "删除模块")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("UAT Tag", Modifier.weight(1f)); Switch(module.uatTagEnabled, { onChange(module.copy(uatTagEnabled = it)) })
            }
            if (module.uatTagEnabled) RemoteBranchPicker(module.uatRef, { onChange(module.copy(uatRef = it)) }, "UAT 目标分支", repositoryId, controller, Modifier.fillMaxWidth())
            if (module.uatTagEnabled) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(module.initialUatTag.orEmpty(), { onChange(module.copy(initialUatTag = it.ifBlank { null })) }, Modifier.weight(1f), label = { Text("初始 Tag（可选）") })
                OutlinedTextField(module.tagMessagePrefix, { onChange(module.copy(tagMessagePrefix = it)) }, Modifier.weight(1f), label = { Text("Tag 消息前缀") }, supportingText = { Text("只影响说明首行，不改变 Tag 名") })
            }
        }
    }
}

@Composable
internal fun DeleteTaskDialog(controller: DesktopApplication, task: TaskManifest, onDismiss: () -> Unit) {
    LaunchedEffect(task.taskDirectoryName) { controller.requestDeleteRisk(task) }
    val inspection = controller.deleteRiskInspections[task.taskDirectoryName]
    val loading = inspection == null || inspection.loading
    val risks = inspection?.risks.orEmpty()
    val inspectionError = inspection?.error
    val safetyCheckFailed = risks.any { it.statusCheckError != null }
    var discard by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除任务") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("会删除任务目录和其工作区，远程分支不会被修改。")
            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("正在检查 Git 状态…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            inspectionError?.let { Text("删除检查失败：$it", color = MaterialTheme.colorScheme.error) }
            risks.forEach {
                val unpushed = if (it.unpushedCommits > 0) "，${it.unpushedCommits} 个仅本地提交" else ""
                val detail = it.statusCheckError ?: "存在未提交改动、Git 操作或未推送提交$unpushed"
                Text("• ${it.serviceName}：$detail", color = MaterialTheme.colorScheme.error)
            }
            if (risks.any { it.statusCheckError == null }) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(discard, { discard = it }); Text("确认丢弃未提交改动") }
        } },
        confirmButton = { Button(
            onClick = {
                controller.deleteTask(task, risks.isNotEmpty(), onCompleted = onDismiss)
            },
            enabled = !loading && inspectionError == null && !safetyCheckFailed && (risks.isEmpty() || discard) && !controller.busy,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun ConfirmDialog(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(message) }, confirmButton = { Button(onClick = onConfirm) { Text("确认") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun DiscardChangesDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onDiscard: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = onDiscard,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("放弃修改") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("继续编辑") } },
    )
}

@Composable
internal fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { OutlinedTextField(value, { value = it }, label = { Text("名称") }, singleLine = true) }, confirmButton = { Button(onClick = { onSave(value) }, enabled = value.isNotBlank()) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
internal fun EmptyState(
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    action: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                Modifier.padding(horizontal = 48.dp, vertical = 38.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(18.dp)) {
                    Icon(Icons.Outlined.Workspaces, null, Modifier.padding(15.dp).size(34.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (action != null && actionLabel != null) {
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = action) { Text(actionLabel) }
                }
            }
        }
    }
}

/** Visible hover help for compact desktop icon actions. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ActionIconButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, modifier = modifier, enabled = enabled) { content() }
    }
}

@Composable
internal fun StatusPill(text: String) {
    val color = MaterialTheme.colorScheme.statusColor(text)
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(50), border = BorderStroke(1.dp, color.copy(alpha = 0.18f))) {
        Text(statusLabel(text), Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = color, style = MaterialTheme.typography.labelSmall)
    }
}

private enum class RequirementStatusCategory { PLANNING, DEVELOPMENT, TESTING, DONE, PAUSED, UNKNOWN }

private fun requirementStatusCategory(status: String): RequirementStatusCategory {
    val normalized = status.trim().lowercase()
    fun matches(vararg values: String) = values.any { it.lowercase() in normalized }
    return when {
        matches("已完成", "已验收", "已发布", "已关闭", "done", "closed", "resolved", "完成") -> RequirementStatusCategory.DONE
        matches("已取消", "取消", "暂停", "挂起", "拒绝", "不做", "终止") -> RequirementStatusCategory.PAUSED
        matches("提测", "待测试", "测试中", "验收中", "待验收") -> RequirementStatusCategory.TESTING
        matches("开发中", "研发中", "进行中", "实现中", "编码中") -> RequirementStatusCategory.DEVELOPMENT
        matches("待排期", "排期中", "规划中", "待开始", "未开始", "待开发") -> RequirementStatusCategory.PLANNING
        else -> RequirementStatusCategory.UNKNOWN
    }
}

@Composable
private fun RequirementStatusPill(status: String) {
    val color = when (requirementStatusCategory(status)) {
        RequirementStatusCategory.PLANNING -> MaterialTheme.colorScheme.primary
        RequirementStatusCategory.DEVELOPMENT -> MaterialTheme.colorScheme.tertiary
        RequirementStatusCategory.TESTING -> WarningAmber
        RequirementStatusCategory.DONE -> SuccessGreen
        RequirementStatusCategory.PAUSED, RequirementStatusCategory.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(50), border = BorderStroke(1.dp, color.copy(alpha = 0.18f))) {
        Text(status, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = color, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun RequirementStatePill(state: RequirementUiState) {
    when (state) {
        RequirementUiState.NotLoaded -> NeutralRequirementPill("未读取")
        RequirementUiState.Loading -> NeutralRequirementPill("读取中")
        RequirementUiState.Failed -> NeutralRequirementPill("读取失败")
        is RequirementUiState.Loaded -> state.metadata.status
            ?.takeIf(String::isNotBlank)
            ?.let { RequirementStatusPill(it) }
            ?: NeutralRequirementPill("未读取")
    }
}

@Composable
private fun NeutralRequirementPill(text: String) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 3.dp), color = color, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun MetaPill(text: String) {
    Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f), shape = RoundedCornerShape(50)) {
        Text(text, Modifier.padding(horizontal = 9.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SectionHeader(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun MetricCard(title: String, value: String, caption: String, modifier: Modifier = Modifier) {
    Surface(
        modifier,
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(15.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = 23.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun statusLabel(status: String): String = when (status) {
    "CREATING" -> "创建中"
    "READY" -> "就绪"
    "READY_WITH_WARNINGS" -> "有警告"
    "FAILED" -> "失败"
    "ARCHIVED" -> "已归档"
    "SUCCESS" -> "成功"
    "CONFLICT" -> "有冲突"
    "PARTIAL" -> "部分完成"
    else -> status
}

internal val WorkspaceStrategy.displayName: String
    get() = when (this) {
        WorkspaceStrategy.STANDARD_WORKTREE -> "标准 Worktree"
        WorkspaceStrategy.INDEPENDENT_CLONE -> "独立克隆"
    }

internal val ThemePreference.displayName: String
    get() = when (this) {
        ThemePreference.SYSTEM -> "跟随系统"
        ThemePreference.LIGHT -> "浅色"
        ThemePreference.DARK -> "深色"
    }
