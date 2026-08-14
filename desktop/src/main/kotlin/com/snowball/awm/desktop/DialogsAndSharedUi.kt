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
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.material3.TooltipAnchorPosition
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
import androidx.compose.ui.focus.onFocusChanged
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
import com.snowball.awm.core.BootstrapCopyRule
import com.snowball.awm.core.BootstrapCommand
import com.snowball.awm.core.BootstrapPresets
import com.snowball.awm.core.GroupServiceConfig
import com.snowball.awm.core.BranchPrefixResolver
import com.snowball.awm.core.DevelopmentToolType
import com.snowball.awm.core.RepositoryConfig
import com.snowball.awm.core.RemoteBranchSearch
import com.snowball.awm.core.RemoteBranchRef
import com.snowball.awm.core.GroupConfig
import com.snowball.awm.core.MeegleProjectConfig
import com.snowball.awm.core.ServiceModuleConfig
import com.snowball.awm.core.StandardWorktreeModuleNaming
import com.snowball.awm.core.ServiceWorkspace
import com.snowball.awm.core.TaskManifest
import com.snowball.awm.core.RequirementMetadata
import com.snowball.awm.core.TaskNaming
import com.snowball.awm.core.TagOutputFormatter
import com.snowball.awm.core.TagBuildMode
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
import com.snowball.awm.core.validated
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
import java.nio.file.Path


@Composable
internal fun BatchTagDialog(
    workspaces: List<ServiceWorkspace>,
    onDismiss: () -> Unit,
    onBuild: (List<ServiceWorkspace>) -> Unit,
) {
    var selected by remember(workspaces) { mutableStateOf(workspaces.map(ServiceWorkspace::selectionKey).toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("批量 Tag") },
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
        modifier = Modifier.widthIn(min = 1040.dp, max = 1280.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = { Text("分支信息") },
        text = {
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(11.dp)) {
                SelectionContainer {
                    Text(
                        content.ifBlank { "暂无分支信息" },
                        Modifier.fillMaxWidth().heightIn(max = 620.dp).padding(13.dp).verticalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace,
                    )
                }
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

internal data class ServiceModuleEditorDraft(
    val id: String,
    val name: String = StandardWorktreeModuleNaming.DEFAULT_NAME,
    val strategy: WorkspaceStrategy = WorkspaceStrategy.STANDARD_WORKTREE,
    val baseRef: String = "origin/master",
    val baseRemote: String = "origin",
    val tagEnabled: Boolean = true,
    val tagMode: TagBuildMode = TagBuildMode.MERGE_TO_TARGET_BRANCH,
    val tagTargetRef: String = "origin/release/test",
    val tagMessagePrefix: String = "Tag",
) {
    fun toConfig(): ServiceModuleConfig = ServiceModuleConfig(
        id = id,
        name = name.trim().ifBlank { StandardWorktreeModuleNaming.DEFAULT_NAME },
        strategy = strategy,
        baseRef = normalizeBaseRefForStrategy(strategy, baseRef),
        baseRemote = baseRemote.trim(),
        tagEnabled = tagEnabled,
        tagMode = tagMode,
        tagTargetRef = if (tagMode == TagBuildMode.CURRENT_BRANCH) null else tagTargetRef.trim(),
        tagMessagePrefix = tagMessagePrefix.trim(),
    )
}

internal fun normalizeBaseRefForStrategy(strategy: WorkspaceStrategy, baseRef: String): String {
    return baseRef.trim()
}

internal fun ServiceModuleConfig.toEditorDraft(): ServiceModuleEditorDraft = ServiceModuleEditorDraft(
    id = id,
    name = name,
    strategy = strategy,
    baseRef = baseRef,
    baseRemote = baseRemote,
    tagEnabled = tagEnabled,
    tagMode = tagMode,
    tagTargetRef = tagTargetRef.orEmpty(),
    tagMessagePrefix = tagMessagePrefix,
)

@Composable
internal fun ServiceEditorDialog(controller: DesktopApplication, service: GroupServiceConfig, onDismiss: () -> Unit, onSave: (GroupServiceConfig) -> Unit) {
    val widthPolicy = serviceEditorDialogWidthPolicy()
    val json = remember { Json { prettyPrint = true; encodeDefaults = true } }
    val initialBootstrapText = remember(service) { json.encodeToString(service.bootstrap) }
    var name by remember { mutableStateOf(service.displayName) }
    var enabled by remember { mutableStateOf(service.enabled) }
    var developmentTool by remember { mutableStateOf(service.developmentTool) }
    var commitMessageTemplate by remember { mutableStateOf(service.commitMessageTemplate) }
    val initialModuleDrafts = remember(service) { service.modules.map(ServiceModuleConfig::toEditorDraft) }
    var modules by remember(service) { mutableStateOf(initialModuleDrafts) }
    var bootstrapConfig by remember(service) { mutableStateOf(service.bootstrap) }
    var bootstrapText by remember { mutableStateOf(initialBootstrapText) }
    var bootstrapMode by remember { mutableStateOf("form") }
    var bootstrapError by remember { mutableStateOf<String?>(null) }
    var serviceValidationError by remember { mutableStateOf<String?>(null) }
    var showBootstrapExample by remember { mutableStateOf(false) }
    var bootstrapCopied by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val hasDraftChanges = name != service.displayName || enabled != service.enabled || developmentTool != service.developmentTool || commitMessageTemplate != service.commitMessageTemplate ||
        modules != initialModuleDrafts ||
        bootstrapConfig != service.bootstrap || bootstrapText != initialBootstrapText
    val requestDismiss = { if (hasDraftChanges) confirmDiscard = true else onDismiss() }
    Dialog(onDismissRequest = requestDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier
                .fillMaxWidth(widthPolicy.fillFraction)
                .fillMaxHeight(0.90f)
                .widthIn(min = widthPolicy.minWidthDp.dp, max = widthPolicy.maxWidthDp.dp)
                .heightIn(min = 620.dp, max = 900.dp),
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
                    MetaPill("${modules.count { it.strategy == WorkspaceStrategy.STANDARD_WORKTREE }} Worktree · ${modules.count { it.strategy == WorkspaceStrategy.INDEPENDENT_CLONE }} 克隆")
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    Modifier.weight(1f).padding(20.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                SectionHeader("基础信息", "IDE 是系统建议，可手工修改；保存值始终作为最终依据")
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("展示名称") })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("启用服务", style = MaterialTheme.typography.titleSmall)
                    Switch(enabled, { enabled = it })
                }
                Text("默认开发工具", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DevelopmentToolType.entries.forEach { value ->
                        FilterChip(developmentTool == value, { developmentTool = value }, label = { Text(value.displayName) })
                    }
                }
                OutlinedTextField(commitMessageTemplate, { commitMessageTemplate = it }, Modifier.fillMaxWidth(), label = { Text("默认提交信息模板（支持 {num}）") }, singleLine = true)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SectionHeader("工作区模块", "同一服务可同时包含标准 Worktree 和独立克隆模块")
                modules.forEachIndexed { index, module ->
                    ModuleEditor(module, service.repositoryId, controller, canDelete = modules.size > 1, onChange = { changed -> modules = modules.mapIndexed { i, value -> if (i == index) changed else value } }, onDelete = { modules = modules.filterIndexed { i, _ -> i != index } })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        modules = modules + ServiceModuleEditorDraft(id = "module-${UUID.randomUUID()}", strategy = WorkspaceStrategy.STANDARD_WORKTREE)
                    }) { Icon(Icons.Outlined.Add, null); Text("添加 Worktree 模块") }
                    OutlinedButton(onClick = {
                        modules = modules + ServiceModuleEditorDraft(id = "clone-${UUID.randomUUID()}", strategy = WorkspaceStrategy.INDEPENDENT_CLONE, tagEnabled = false)
                    }) { Icon(Icons.Outlined.Add, null); Text("添加克隆模块") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader("Bootstrap", "创建工作区后复制本机文件并执行初始化命令")
                    Spacer(Modifier.weight(1f))
                    FilterChip(bootstrapMode == "form", {
                        val parsed = runCatching { json.decodeFromString<BootstrapConfig>(bootstrapText).validated() }
                            .getOrElse { error -> bootstrapError = "JSON 格式错误：${error.message}"; return@FilterChip }
                        bootstrapConfig = parsed
                        bootstrapMode = "form"
                        bootstrapError = null
                    }, label = { Text("表单配置") })
                    Spacer(Modifier.width(6.dp))
                    FilterChip(bootstrapMode == "json", {
                        bootstrapText = json.encodeToString(bootstrapConfig)
                        bootstrapMode = "json"
                        bootstrapError = null
                    }, label = { Text("高级 JSON") })
                    TextButton(onClick = { bootstrapCopied = false; showBootstrapExample = true }) { Text("查看示例") }
                }
                if (bootstrapMode == "form") {
                    BootstrapFormEditor(
                        config = bootstrapConfig,
                        repositoryRoot = controller.config.repositories.firstOrNull { it.id == service.repositoryId }?.rootPath,
                        controller = controller,
                        onChange = {
                            bootstrapConfig = it
                            bootstrapText = json.encodeToString(it)
                            bootstrapError = null
                        },
                        onError = { bootstrapError = it },
                    )
                } else {
                    OutlinedTextField(
                        bootstrapText,
                        { bootstrapText = it; bootstrapError = null },
                        Modifier.fillMaxWidth(),
                        label = { Text("Bootstrap JSON") },
                        minLines = 8,
                        isError = bootstrapError != null,
                        supportingText = bootstrapError?.let { message -> { Text(message) } },
                    )
                }
                bootstrapError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    Text("修改仅影响后续任务", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = requestDismiss) { Text("取消") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val bootstrap = if (bootstrapMode == "json") {
                            runCatching { json.decodeFromString<BootstrapConfig>(bootstrapText).validated() }
                                .getOrElse { error -> bootstrapError = "JSON 格式错误：${error.message}"; return@Button }
                        } else bootstrapConfig
                        runCatching {
                            val normalizedModules = modules.map(ServiceModuleEditorDraft::toConfig)
                            validateServiceWorkspaceModules(normalizedModules)
                            service.copy(
                                displayName = name.trim(), enabled = enabled, developmentTool = developmentTool,
                                commitMessageTemplate = commitMessageTemplate.trim(),
                                modules = normalizedModules,
                                bootstrap = bootstrap,
                            )
                        }.onSuccess(onSave).onFailure { serviceValidationError = OperationFailureDetails.format(it) }
                    }, enabled = name.isNotBlank() && modules.isNotEmpty() && modules.all { it.baseRef.isNotBlank() }) { Icon(Icons.Outlined.Save, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("保存配置") }
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
    serviceValidationError?.let { details ->
        TagResultDialog(
            title = "服务配置无法保存",
            content = details,
            onDismiss = { serviceValidationError = null },
            onCopy = { controller.copyText(details, "错误详情已复制") },
        )
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

internal data class ServiceEditorDialogWidthPolicy(
    val fillFraction: Float,
    val minWidthDp: Int,
    val maxWidthDp: Int,
)

internal fun serviceEditorDialogWidthPolicy(): ServiceEditorDialogWidthPolicy =
    ServiceEditorDialogWidthPolicy(
        fillFraction = 0.46f,
        minWidthDp = 410,
        maxWidthDp = 640,
    )

@Composable
private fun BootstrapFormEditor(
    config: BootstrapConfig,
    repositoryRoot: String?,
    controller: DesktopApplication,
    onChange: (BootstrapConfig) -> Unit,
    onError: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("复制规则", fontWeight = FontWeight.SemiBold)
        config.copyRules.forEachIndexed { index, rule ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(rule.source, { value ->
                            onChange(config.copy(copyRules = config.copyRules.replaceAt(index, rule.copy(source = value))))
                        }, Modifier.weight(1f), label = { Text("仓库内源路径") }, singleLine = true)
                        OutlinedButton(onClick = {
                            if (repositoryRoot.isNullOrBlank()) {
                                onError("请先配置原始仓库目录")
                            } else controller.chooseFile(repositoryRoot) { chosen ->
                                runCatching { repositoryRelativePath(repositoryRoot, chosen) }
                                    .onSuccess { relative ->
                                        onChange(config.copy(copyRules = config.copyRules.replaceAt(index, rule.withSelectedSource(relative))))
                                    }
                                    .onFailure { onError(it.message) }
                            }
                        }) { Text("选择文件") }
                        OutlinedButton(onClick = {
                            if (repositoryRoot.isNullOrBlank()) {
                                onError("请先配置原始仓库目录")
                            } else controller.chooseDirectory(repositoryRoot) { chosen ->
                                runCatching { repositoryRelativePath(repositoryRoot, chosen) }
                                    .onSuccess { relative ->
                                        onChange(config.copy(copyRules = config.copyRules.replaceAt(index, rule.withSelectedSource(relative))))
                                    }
                                    .onFailure { onError(it.message) }
                            }
                        }) { Text("选择目录") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(rule.target, { value ->
                            onChange(config.copy(copyRules = config.copyRules.replaceAt(index, rule.copy(target = value))))
                        }, Modifier.weight(1f), label = { Text("工作区目标路径") }, singleLine = true)
                        Checkbox(rule.overwrite, { checked ->
                            onChange(config.copy(copyRules = config.copyRules.replaceAt(index, rule.copy(overwrite = checked))))
                        })
                        Text("允许覆盖")
                        IconButton(onClick = { onChange(config.copy(copyRules = config.copyRules.filterIndexed { i, _ -> i != index })) }) {
                            Icon(Icons.Outlined.Delete, "删除复制规则")
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = {
            onChange(config.copy(copyRules = config.copyRules + BootstrapCopyRule(source = "", target = "")))
        }) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(4.dp)); Text("添加复制规则") }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("初始化命令", fontWeight = FontWeight.SemiBold)
        config.commands.forEachIndexed { index, command ->
            var argumentsText by remember(command) { mutableStateOf(command.arguments.joinToString("\n")) }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(command.name, { value -> onChange(config.copy(commands = config.commands.replaceAt(index, command.copy(name = value)))) }, Modifier.weight(1f), label = { Text("名称") }, singleLine = true)
                        OutlinedTextField(command.executable, { value -> onChange(config.copy(commands = config.commands.replaceAt(index, command.copy(executable = value)))) }, Modifier.weight(1f), label = { Text("可执行程序") }, singleLine = true)
                    }
                    OutlinedTextField(argumentsText, { value ->
                        argumentsText = value
                        onChange(config.copy(commands = config.commands.replaceAt(index, command.copy(arguments = value.lines().filter { it.isNotBlank() }))))
                    }, Modifier.fillMaxWidth(), label = { Text("参数（每行一个）") }, minLines = 2)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(command.workingDirectory, { value -> onChange(config.copy(commands = config.commands.replaceAt(index, command.copy(workingDirectory = value)))) }, Modifier.weight(1f), label = { Text("工作目录") }, singleLine = true)
                        OutlinedTextField(command.timeoutSeconds.toString(), { value -> value.toLongOrNull()?.takeIf { it > 0 }?.let { timeout -> onChange(config.copy(commands = config.commands.replaceAt(index, command.copy(timeoutSeconds = timeout)))) } }, Modifier.width(150.dp), label = { Text("超时（秒）") }, singleLine = true)
                        Checkbox(command.enabled, { checked -> onChange(config.copy(commands = config.commands.replaceAt(index, command.copy(enabled = checked)))) })
                        Text("启用")
                        IconButton(onClick = { onChange(config.copy(commands = config.commands.filterIndexed { i, _ -> i != index })) }) { Icon(Icons.Outlined.Delete, "删除命令") }
                    }
                    val preview = (listOf(command.executable) + command.arguments).joinToString(" ") { argument -> if (argument.any(Char::isWhitespace)) "\"$argument\"" else argument }
                    SelectionContainer { Text("预览：$preview", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        OutlinedButton(onClick = {
            onChange(config.copy(commands = config.commands + BootstrapCommand(name = "初始化", executable = "")))
        }) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(4.dp)); Text("添加命令") }
    }
}

private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> = mapIndexed { current, existing ->
    if (current == index) value else existing
}

internal fun BootstrapCopyRule.withSelectedSource(relative: String): BootstrapCopyRule = copy(
    source = relative,
    target = if (target.isBlank() || target == source) relative else target,
)

private fun repositoryRelativePath(repositoryRoot: String, selectedPath: String): String {
    val root = Path.of(repositoryRoot).toAbsolutePath().normalize()
    val selected = Path.of(selectedPath).toAbsolutePath().normalize()
    require(selected.startsWith(root)) { "只能选择原始仓库内的文件或目录" }
    val relative = root.relativize(selected)
    require(relative.none { it.toString() == ".." }) { "路径不能包含 .." }
    require(relative.none { it.toString().equals(".git", ignoreCase = true) }) { "不能选择 .git 内容" }
    require(!java.nio.file.Files.isSymbolicLink(selected)) { "不能选择符号链接" }
    return relative.toString().replace('\\', '/')
}

internal fun validateServiceWorkspaceModules(
    modules: List<ServiceModuleConfig>,
) {
    StandardWorktreeModuleNaming.requireValid(modules)
    require(modules.map { it.id.lowercase() }.distinct().size == modules.size) { "模块 ID 不能重复（忽略大小写）" }
}

@Composable
internal fun TagResultDialog(
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
private fun ModuleEditor(module: ServiceModuleEditorDraft, repositoryId: String, controller: DesktopApplication, canDelete: Boolean, onChange: (ServiceModuleEditorDraft) -> Unit, onDelete: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WorkspaceStrategy.entries.forEach { strategy ->
                    FilterChip(
                        selected = module.strategy == strategy,
                        onClick = {
                            onChange(module.copy(
                                strategy = strategy,
                                baseRef = normalizeBaseRefForStrategy(strategy, module.baseRef),
                                baseRemote = module.baseRemote,
                            ))
                        },
                        label = { Text(strategy.displayName) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                OutlinedTextField(
                    module.name,
                    { onChange(module.copy(name = it)) },
                    Modifier.weight(1f),
                    label = { Text("模块名") },
                    placeholder = { Text(StandardWorktreeModuleNaming.DEFAULT_NAME) },
                    supportingText = { Text("仅允许英文字母、数字、-、_、/") },
                )
                ActionIconButton("删除模块", onDelete, Modifier.padding(top = 8.dp), enabled = canDelete) {
                    Icon(Icons.Outlined.Delete, "删除模块")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RemoteNamePicker(
                    value = module.baseRemote,
                    repositoryId = repositoryId,
                    controller = controller,
                    onSelected = { remote ->
                        val branch = module.baseRef.substringAfter('/', module.baseRef)
                        onChange(module.copy(baseRemote = remote, baseRef = "$remote/$branch"))
                    },
                    modifier = Modifier.width(160.dp),
                )
                RemoteBranchPicker(
                    module.baseRef,
                    { onChange(module.copy(baseRef = it)) },
                    "基础分支",
                    repositoryId,
                    controller,
                    Modifier.weight(1f),
                    remote = module.baseRemote,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tag", Modifier.weight(1f)); Switch(module.tagEnabled, { onChange(module.copy(tagEnabled = it)) })
            }
            if (module.tagEnabled) TagModeSelector(module.tagMode) { mode ->
                onChange(module.copy(tagMode = mode, tagTargetRef = if (mode == TagBuildMode.CURRENT_BRANCH) "" else module.tagTargetRef.ifBlank { "origin/release/test" }))
            }
            if (module.tagEnabled) TagConfigurationFields(
                targetVisible = module.tagMode == TagBuildMode.MERGE_TO_TARGET_BRANCH,
                targetRef = module.tagTargetRef,
                onTargetRefChange = { onChange(module.copy(tagTargetRef = it)) },
                messagePrefix = module.tagMessagePrefix,
                onMessagePrefixChange = { onChange(module.copy(tagMessagePrefix = it)) },
                repositoryId = repositoryId,
                controller = controller,
            )
        }
    }
}

@Composable
internal fun RemoteNamePicker(
    value: String,
    repositoryId: String,
    controller: DesktopApplication,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(repositoryId) { mutableStateOf(false) }
    val state = controller.repositoryRemotesState(repositoryId)
    LaunchedEffect(repositoryId) {
        controller.loadRepositoryRemotes(repositoryId)
    }
    if (!shouldShowRemoteNamePicker(value, state)) return
    Box(modifier) {
        OutlinedButton(
            onClick = {
                expanded = true
                controller.loadRepositoryRemotes(repositoryId)
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text(value, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Outlined.KeyboardArrowDown, null, Modifier.size(17.dp))
        }
        AwmDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            when (state) {
                RepositoryRemotesState.Idle, RepositoryRemotesState.Loading ->
                    DropdownMenuItem({ Text("正在读取远程…") }, onClick = {}, enabled = false)
                is RepositoryRemotesState.Failed -> {
                    DropdownMenuItem({ Text(state.message, color = MaterialTheme.colorScheme.error) }, onClick = {}, enabled = false)
                    DropdownMenuItem({ Text("重试") }, onClick = { controller.loadRepositoryRemotes(repositoryId, force = true) })
                }
                is RepositoryRemotesState.Loaded -> state.remotes.forEach { remote ->
                    DropdownMenuItem(
                        text = { Text(remote) },
                        onClick = { onSelected(remote); expanded = false },
                    )
                }
            }
        }
    }
}

/**
 * A sole, still-valid source remote has no user choice to expose. Keep the picker visible
 * while loading or after an error, and when an old configuration refers to a removed remote,
 * so we never silently replace the selected source.
 */
internal fun shouldShowRemoteNamePicker(
    selectedRemote: String,
    state: RepositoryRemotesState,
): Boolean = when (state) {
    is RepositoryRemotesState.Loaded ->
        state.remotes.singleOrNull()?.equals(selectedRemote, ignoreCase = true) != true
    is RepositoryRemotesState.Failed -> true
    RepositoryRemotesState.Idle, RepositoryRemotesState.Loading -> false
}

internal data class TagConfigurationFieldLayout(
    val heightDp: Int?,
    val singleLine: Boolean,
    val targetWeight: Float,
    val messageWeight: Float,
)

internal fun tagConfigurationFieldLayout(): TagConfigurationFieldLayout =
    TagConfigurationFieldLayout(
        heightDp = null,
        singleLine = true,
        targetWeight = 1f,
        messageWeight = 1f,
    )

private fun Modifier.tagConfigurationFieldHeight(heightDp: Int?): Modifier =
    if (heightDp == null) this else height(heightDp.dp)

@Composable
private fun TagConfigurationFields(
    targetVisible: Boolean,
    targetRef: String,
    onTargetRefChange: (String) -> Unit,
    messagePrefix: String,
    onMessagePrefixChange: (String) -> Unit,
    repositoryId: String,
    controller: DesktopApplication,
) {
    val layout = tagConfigurationFieldLayout()
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (targetVisible) {
            RemoteBranchPicker(
                targetRef,
                onTargetRefChange,
                "Tag 目标分支",
                repositoryId,
                controller,
                Modifier.weight(layout.targetWeight).tagConfigurationFieldHeight(layout.heightDp),
            )
        }
        OutlinedTextField(
            messagePrefix,
            onMessagePrefixChange,
            Modifier.weight(layout.messageWeight).tagConfigurationFieldHeight(layout.heightDp),
            label = { Text("Tag 消息前缀") },
            singleLine = layout.singleLine,
            colors = branchPickerFieldColors(),
        )
    }
}

@Composable
private fun TagModeSelector(mode: TagBuildMode, onChange: (TagBuildMode) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = mode == TagBuildMode.MERGE_TO_TARGET_BRANCH,
            onClick = { onChange(TagBuildMode.MERGE_TO_TARGET_BRANCH) },
            label = { Text("合并到目标分支后打 Tag") },
        )
        FilterChip(
            selected = mode == TagBuildMode.CURRENT_BRANCH,
            onClick = { onChange(TagBuildMode.CURRENT_BRANCH) },
            label = { Text("当前分支直接打 Tag") },
        )
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
    loading: Boolean = false,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, modifier = modifier, enabled = enabled && !loading) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                content()
            }
        }
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
