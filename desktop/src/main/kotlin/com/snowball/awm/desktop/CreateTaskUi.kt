package com.snowball.awm.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.snowball.awm.core.BranchReuseConflict
import com.snowball.awm.core.BranchReuseKey
import com.snowball.awm.core.GroupServiceConfig
import com.snowball.awm.core.BranchPrefixResolver
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
import com.snowball.awm.core.TaskBranchNaming
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
import com.snowball.awm.core.ModuleBaseOverride
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
internal fun CreateTaskDialog(
    controller: DesktopApplication,
    onDismiss: () -> Unit,
    onCreate: (String, String, String, List<String>, String, String, List<String>, Set<BranchReuseKey>, List<ModuleBaseOverride>) -> Unit,
) {
    val initialGroup = controller.config.groups.first()
    var draft by remember {
        mutableStateOf(RequirementDraftState(branch = initialGroup.defaultBranchPrefix))
    }
    var notes by remember { mutableStateOf("") }
    var groupId by remember { mutableStateOf(initialGroup.id) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedToolIds by remember { mutableStateOf(initialGroup.defaultWorkspaceToolIds.toSet()) }
    var rightTab by remember { mutableStateOf("notes") }
    var requirementMenuExpanded by remember { mutableStateOf(false) }
    var requirementSearch by remember { mutableStateOf("") }
    var serviceSearch by remember(groupId) { mutableStateOf("") }
    var confirmDiscard by remember { mutableStateOf(false) }
    var checkingBranchReuse by remember { mutableStateOf(false) }
    var branchConflicts by remember { mutableStateOf<List<BranchReuseConflict>?>(null) }
    val baseOverrideValues = remember(groupId) { mutableStateMapOf<String, String>() }
    val targetBranchValues = remember(groupId) { mutableStateMapOf<String, String>() }
    val group = controller.config.groups.first { it.id == groupId }
    val toolOptions = controller.workspaceToolOptions(groupId)
    fun effectiveBaseOverrides(): List<ModuleBaseOverride> = group.services.filter { it.id in selected }.flatMap { service ->
        taskModuleOverrides(service, draft.branch, baseOverrideValues, targetBranchValues)
    }
    val taskNameMissing = draft.taskName.isBlank()
    // An untouched create form is incomplete rather than erroneous. Reserve the
    // error treatment for an entered name that cannot become a safe directory.
    val taskNameError = draft.taskName.takeUnless(String::isBlank)
        ?.let(TaskNaming::directoryNameValidationError)
    val unresolvedBranch = BranchPrefixResolver.containsUnresolvedPlaceholder(draft.branch)
    val hasDraftChanges = draft.requirementLink.isNotBlank() || draft.taskName.isNotBlank() ||
        draft.branchEdited || notes.isNotBlank() || selected.isNotEmpty() || groupId != initialGroup.id ||
        selectedToolIds != initialGroup.defaultWorkspaceToolIds.toSet()
    val requestDismiss = { if (hasDraftChanges) confirmDiscard = true else onDismiss() }
    val preview = remember(
        draft.taskName,
        draft.branch,
        groupId,
        selected,
        draft.requirementLink,
        notes,
        baseOverrideValues.toMap(),
        targetBranchValues.toMap(),
    ) {
        controller.previewAgents(
            draft.taskName,
            draft.branch,
            groupId,
            selected,
            draft.requirementLink,
            notes,
            effectiveBaseOverrides(),
        )
    }
    LaunchedEffect(draft.requirementLink) {
        val requestedLink = draft.requirementLink
        controller.requestRequirementMetadata(requestedLink) { metadata ->
            draft = draft.applyMetadata(requestedLink, metadata)
        }
    }
    LaunchedEffect(Unit) { controller.requirementController.loadCandidates() }
    DisposableEffect(Unit) {
        onDispose {
            controller.cancelRemoteBranchLoads()
        }
    }
    Dialog(onDismissRequest = requestDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.90f).widthIn(max = 1540.dp),
            shape = RoundedCornerShape(22.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.fillMaxSize()) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(12.dp)) {
                            Icon(Icons.Outlined.Add, null, Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.onPrimary)
                        }
                        Spacer(Modifier.width(13.dp))
                        Column(Modifier.weight(1f)) {
                            Text("创建研发任务", style = MaterialTheme.typography.headlineSmall)
                            Text("选择组和服务，并实时确认最终 AGENTS.md", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        MetaPill("已选 ${selected.size} 个服务")
                    }
                }
                Row(Modifier.weight(1f).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Surface(
                        Modifier.weight(1f).fillMaxHeight(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f),
                        shape = RoundedCornerShape(15.dp),
                    ) {
                    Column(Modifier.fillMaxSize().padding(15.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                        SectionHeader("任务信息", "名称和分支将用于创建任务目录与 Git 分支")
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                            OutlinedTextField(
                                draft.requirementLink,
                                { draft = draft.changeRequirement(it, group.defaultBranchPrefix) },
                                Modifier.weight(1f),
                                label = { Text("飞书需求链接（可选）") },
                                supportingText = {
                                    when {
                                        draft.requirementTitle != null -> Text(draft.requirementTitle!!)
                                        draft.metadataLoading -> Text("正在读取需求标题…")
                                        draft.metadataHint != null -> Text(draft.metadataHint!!)
                                    }
                                },
                                singleLine = true,
                            )
                            Spacer(Modifier.width(8.dp))
                            Box {
                                OutlinedButton(
                                    onClick = { requirementMenuExpanded = true },
                                    enabled = controller.requirementController.candidates.isNotEmpty(),
                                    modifier = Modifier.padding(top = 8.dp),
                                ) { Text("选择需求") }
                                AwmDropdownMenu(
                                    expanded = requirementMenuExpanded,
                                    onDismissRequest = { requirementMenuExpanded = false },
                                    modifier = Modifier.widthIn(min = 520.dp, max = 680.dp),
                                ) {
                                    OutlinedTextField(
                                        requirementSearch,
                                        { requirementSearch = it },
                                        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                        label = { Text("搜索标题或链接") },
                                        singleLine = true,
                                    )
                                    val matchingLinks = controller.requirementController.candidates.filter {
                                        requirementSearch.isBlank() ||
                                            it.title.contains(requirementSearch, true) ||
                                            it.url.contains(requirementSearch, true)
                                    }
                                    Column(
                                        Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                                    ) {
                                        matchingLinks.forEach { candidate ->
                                            DropdownMenuItem(
                                                text = {
                                                    Column {
                                                        Text(candidate.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                        Text(
                                                            candidate.url,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                },
                                                onClick = {
                                                    draft = draft.changeRequirement(candidate.url, group.defaultBranchPrefix, candidate.title)
                                                    requirementMenuExpanded = false
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (controller.requirementController.candidatesLoading) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("正在拉取飞书需求链接", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        OutlinedTextField(
                            value = draft.taskName,
                            onValueChange = { draft = draft.editName(it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("文件夹名称") },
                            placeholder = { Text("例如：PAY-1024 支付订单优化") },
                            isError = taskNameError != null,
                            supportingText = { taskNameError?.let { Text(it) } },
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = draft.branch,
                            onValueChange = { draft = draft.editBranch(it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("任务分支") },
                            placeholder = { Text("例如：feature/PAY-1024") },
                            supportingText = if (unresolvedBranch) {
                                { Text("需求链接未解析出编号，请补充链接或手工修改分支") }
                            } else null,
                            colors = branchPickerFieldColors(),
                            singleLine = true,
                        )
                        if (controller.config.groups.size > 1) {
                            Spacer(Modifier.height(2.dp))
                            SectionHeader("所属组", "任务创建后归属不可自动迁移")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                controller.config.groups.forEach { candidate -> FilterChip(groupId == candidate.id, {
                                    groupId = candidate.id
                                    selected = emptySet()
                                    selectedToolIds = candidate.defaultWorkspaceToolIds.toSet()
                                    serviceSearch = ""
                                    draft = draft.changeGroup(candidate.defaultBranchPrefix)
                                }, label = { Text(candidate.name) }) }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        SectionHeader("选择服务", "标准服务创建 Worktree，独立克隆直接切换配置分支")
                        OutlinedTextField(
                            value = serviceSearch,
                            onValueChange = { serviceSearch = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("搜索服务") },
                            placeholder = { Text("按服务显示名称搜索") },
                            singleLine = true,
                        )
                        val visibleServices = group.services
                            .filter { it.enabled }
                            .filter { serviceSearch.isBlank() || it.displayName.contains(serviceSearch, ignoreCase = true) }
                        if (visibleServices.isEmpty()) {
                            Text("没有匹配的服务", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        visibleServices.forEach { service ->
                            val checked = service.id in selected
                            OutlinedCard(
                                Modifier.fillMaxWidth().clickable { selected = if (checked) selected - service.id else selected + service.id },
                                colors = CardDefaults.outlinedCardColors(
                                    containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surface,
                                ),
                                border = BorderStroke(1.dp, if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                Column(Modifier.padding(11.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(checked, { selected = if (checked) selected - service.id else selected + service.id })
                                        Column(Modifier.weight(1f)) {
                                            Text(service.displayName, style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                when (service.strategy) {
                                                    WorkspaceStrategy.STANDARD_WORKTREE -> if (service.modules.size > 1) "${service.modules.size} 个模块 · ${service.strategy.displayName}" else service.strategy.displayName
                                                    WorkspaceStrategy.INDEPENDENT_CLONE -> "${service.cloneModules.size} 个固定分支模块 · ${service.strategy.displayName}"
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    if (checked) {
                                        val serviceModules = if (service.strategy == WorkspaceStrategy.INDEPENDENT_CLONE) {
                                            service.cloneModules.map { module ->
                                                TaskModuleBranchOption(
                                                    id = module.id,
                                                    name = module.name.ifBlank { module.id },
                                                    defaultRef = module.branch,
                                                    remote = runCatching { RemoteBranchRef.parse(module.branch).remote }.getOrDefault("origin"),
                                                )
                                            }
                                        } else service.modules.map { module ->
                                            TaskModuleBranchOption(
                                                id = module.id,
                                                name = module.name.ifBlank { module.id },
                                                defaultRef = module.baseRef,
                                                remote = module.baseRemote,
                                            )
                                        }
                                        serviceModules.forEach { module ->
                                            val key = "${service.id}::${module.id}"
                                            RemoteBranchPicker(
                                                value = baseOverrideValues[key] ?: module.defaultRef,
                                                onValueChange = { baseOverrideValues[key] = it },
                                                label = "${module.name} · 本次基础分支",
                                                repositoryId = service.repositoryId,
                                                controller = controller,
                                                modifier = Modifier.fillMaxWidth().padding(start = 42.dp, top = 6.dp),
                                                remote = module.remote,
                                            )
                                            if (service.strategy == WorkspaceStrategy.STANDARD_WORKTREE) {
                                                val defaultTarget = defaultTaskModuleTargetBranch(draft.branch, service, module.id)
                                                TaskTargetBranchField(
                                                    value = targetBranchValues[key] ?: defaultTarget,
                                                    onValueChange = { value ->
                                                        if (value == defaultTarget) targetBranchValues.remove(key)
                                                        else targetBranchValues[key] = value
                                                    },
                                                    label = "${module.name} · 本次目标分支",
                                                    modifier = Modifier.fillMaxWidth().padding(start = 42.dp, top = 6.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(rightTab == "notes", { rightTab = "notes" }, label = { Text("任务人工说明") })
                            Spacer(Modifier.width(8.dp))
                            FilterChip(rightTab == "preview", { rightTab = "preview" }, label = { Text("AGENTS.md 预览") })
                            Spacer(Modifier.weight(1f))
                            if (rightTab == "preview") MetaPill("实时更新")
                        }
                        if (rightTab == "notes") {
                            OutlinedTextField(
                                notes,
                                { notes = it },
                                Modifier.fillMaxSize(),
                                label = { Text("任务人工说明") },
                                supportingText = { Text("只写入任务级人工区，创建后仍可在任务详情继续编辑。") },
                            )
                        } else {
                            Surface(
                                Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            ) {
                                AgentsMarkdownPreview(preview)
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        toolOptions.forEach { tool ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = tool.id in selectedToolIds,
                                    onCheckedChange = { checked -> selectedToolIds = if (checked) selectedToolIds + tool.id else selectedToolIds - tool.id },
                                    enabled = tool.available,
                                )
                                Text(if (tool.available) tool.displayName else "${tool.displayName}（不可用）", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when {
                                selected.isEmpty() -> "请选择至少一个服务"
                                unresolvedBranch -> "分支中仍有未解析的 {num}"
                                else -> "将创建 ${selected.size} 个服务入口"
                            },
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = requestDismiss) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val availableTools = selectedToolIds.filter { id -> toolOptions.firstOrNull { it.id == id }?.available == true }
                                checkingBranchReuse = controller.taskController.inspectCreateBranchReuse(
                                    name = draft.taskName,
                                    branch = draft.branch,
                                    groupId = groupId,
                                    serviceIds = selected.toList(),
                                    link = draft.requirementLink,
                                    notes = notes,
                                    baseOverrides = effectiveBaseOverrides(),
                                    onResolved = { conflicts ->
                                        if (conflicts.isEmpty()) {
                                            onCreate(
                                                draft.taskName,
                                                draft.branch,
                                                groupId,
                                                selected.toList(),
                                                draft.requirementLink,
                                                notes,
                                                availableTools,
                                                emptySet(),
                                                effectiveBaseOverrides(),
                                            )
                                        } else {
                                            branchConflicts = conflicts
                                        }
                                    },
                                    onFinished = { checkingBranchReuse = false },
                                )
                            },
                            enabled = !taskNameMissing && taskNameError == null && draft.branch.isNotBlank() &&
                                !unresolvedBranch &&
                                selected.isNotEmpty() && !controller.busy && !checkingBranchReuse,
                        ) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("创建任务") }
                    }
                }
            }
        }
    }
    if (confirmDiscard) {
        DiscardChangesDialog(
            title = "放弃创建任务？",
            message = "已填写的任务信息、服务选择和人工说明将不会保留。",
            onDismiss = { confirmDiscard = false },
            onDiscard = onDismiss,
        )
    }
    branchConflicts?.let { conflicts ->
        BranchReuseConfirmationDialog(
            conflicts = conflicts,
            onDismiss = { branchConflicts = null },
            onConfirm = { keys ->
                branchConflicts = null
                val availableTools = selectedToolIds.filter { id -> toolOptions.firstOrNull { it.id == id }?.available == true }
                onCreate(
                    draft.taskName,
                    draft.branch,
                    groupId,
                    selected.toList(),
                    draft.requirementLink,
                    notes,
                    availableTools,
                    keys,
                    effectiveBaseOverrides(),
                )
            },
        )
    }
}

@Composable
private fun TaskTargetBranchField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label) },
        singleLine = true,
        colors = branchPickerFieldColors(),
    )
}

private data class TaskModuleBranchOption(
    val id: String,
    val name: String,
    val defaultRef: String,
    val remote: String,
)

internal fun defaultTaskModuleTargetBranch(
    taskBranch: String,
    service: GroupServiceConfig,
    moduleId: String,
): String = taskModuleTargetBranchDefaults(taskBranch, service).getValue(moduleId)

private fun taskModuleTargetBranchDefaults(taskBranch: String, service: GroupServiceConfig): Map<String, String> =
    runCatching { TaskBranchNaming.derive(taskBranch, service.modules) }.getOrElse {
        if (service.modules.size == 1) {
            mapOf(service.modules.single().id to taskBranch)
        } else {
            service.modules.associate { module -> module.id to "$taskBranch-${module.name.trim().ifBlank { module.id }}" }
        }
    }

internal fun taskModuleOverrides(
    service: GroupServiceConfig,
    taskBranch: String,
    baseOverrides: Map<String, String>,
    targetOverrides: Map<String, String>,
): List<ModuleBaseOverride> {
    val keyPrefix = "${service.id}::"
    return when (service.strategy) {
        WorkspaceStrategy.STANDARD_WORKTREE -> {
            val targetDefaults = taskModuleTargetBranchDefaults(taskBranch, service)
            service.modules.map { module ->
                val key = "$keyPrefix${module.id}"
                ModuleBaseOverride(
                    serviceId = service.id,
                    moduleId = module.id,
                    baseRef = baseOverrides[key] ?: module.baseRef,
                    targetBranch = targetOverrides[key] ?: targetDefaults.getValue(module.id),
                )
            }
        }
        WorkspaceStrategy.INDEPENDENT_CLONE -> service.cloneModules.map { module ->
            val key = "$keyPrefix${module.id}"
            ModuleBaseOverride(
                serviceId = service.id,
                moduleId = module.id,
                baseRef = baseOverrides[key] ?: module.branch,
            )
        }
    }
}

/** Explicitly acknowledges reuse before any task directory or worktree is created. */
@Composable
internal fun BranchReuseConfirmationDialog(
    conflicts: List<BranchReuseConflict>,
    onDismiss: () -> Unit,
    onConfirm: (Set<BranchReuseKey>) -> Unit,
) {
    var sharedBranchAcknowledged by remember(conflicts) { mutableStateOf(false) }
    val hasOccupiedWorktree = conflicts.any(BranchReuseConflict::requiresForceAttach)
    val hasLockedWorktree = conflicts.any { it.lockedWorktreePaths.isNotEmpty() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认复用已有分支") },
        text = {
            Column(
                Modifier.widthIn(min = 620.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "以下分支已存在。确认后会在新任务目录中复用它们，不会创建同名新分支。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                conflicts.forEach { conflict ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${conflict.serviceName} · ${conflict.moduleName}", fontWeight = FontWeight.SemiBold)
                            Text(conflict.key.branch, style = MaterialTheme.typography.bodySmall)
                            if (conflict.localExists) {
                                Text("本地分支已存在", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (conflict.remoteRefs.isNotEmpty()) {
                                Text(
                                    "远程分支：${conflict.remoteRefs.joinToString()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            conflict.occupiedWorktreePaths.forEach { path ->
                                Text(
                                    "已被 Worktree 检出：$path",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            conflict.lockedWorktreePaths.forEach { path ->
                                Text(
                                    "该 Worktree 已锁定，需先在 Git 中 unlock：$path",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
                if (hasOccupiedWorktree) {
                    HorizontalDivider()
                    Text(
                        "已检出的分支将使用 Git 强制附加到新目录。两个目录共享同一分支和提交历史，请不要并行修改。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(sharedBranchAcknowledged, { sharedBranchAcknowledged = it })
                        Text("我了解该分支会被两个 Worktree 共享")
                    }
                }
                if (hasLockedWorktree) {
                    Text(
                        "锁定的 Worktree 不能自动清理或强制复用。请先执行 git worktree unlock 后重新预检。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(conflicts.map(BranchReuseConflict::key).toSet()) },
                enabled = !hasLockedWorktree && (!hasOccupiedWorktree || sharedBranchAcknowledged),
            ) { Text("确认复用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("返回修改") } },
    )
}

/** Read-only Material 3 rendering used exclusively for generated AGENTS.md previews. */
@Composable
private fun AgentsMarkdownPreview(content: String) {
    val verticalScroll = rememberScrollState()
    Box(Modifier.fillMaxSize().padding(15.dp).verticalScroll(verticalScroll)) {
        // Markdown tables and code blocks own their horizontal scrolling. Wrapping the whole
        // renderer in another horizontal scroll would measure those children with infinite
        // width and crashes on layouts that activate the renderer's internal overflow path.
        Markdown(
            content = content,
            colors = markdownColor(),
            // Default Markdown h1/h2 styles map to Material display styles, which are
            // too prominent inside a compact task preview.
            typography = markdownTypography(
                h1 = MaterialTheme.typography.titleLarge,
                h2 = MaterialTheme.typography.titleMedium,
                h3 = MaterialTheme.typography.titleSmall,
                h4 = MaterialTheme.typography.labelLarge,
                h5 = MaterialTheme.typography.labelLarge,
                h6 = MaterialTheme.typography.labelLarge,
                text = MaterialTheme.typography.bodyMedium,
                paragraph = MaterialTheme.typography.bodyMedium,
                table = MaterialTheme.typography.bodySmall,
                code = MaterialTheme.typography.bodySmall,
                inlineCode = MaterialTheme.typography.bodySmall,
            ),
            modifier = Modifier.fillMaxWidth(),
            // Parsing is asynchronous; retaining the last result avoids preview flicker while typing.
            retainState = true,
        )
    }
}

@Composable
internal fun AddTaskServicesDialog(
    controller: DesktopApplication,
    task: TaskManifest,
    onDismiss: () -> Unit,
    onAdd: (List<String>, Set<BranchReuseKey>, List<ModuleBaseOverride>) -> Unit,
) {
    val services = controller.addableServices(task)
    var selected by remember(task.folderName) { mutableStateOf<Set<String>>(emptySet()) }
    var checkingBranchReuse by remember(task.folderName) { mutableStateOf(false) }
    var branchConflicts by remember(task.folderName) { mutableStateOf<List<BranchReuseConflict>?>(null) }
    val baseOverrideValues = remember(task.folderName) { mutableStateMapOf<String, String>() }
    val targetBranchValues = remember(task.folderName) { mutableStateMapOf<String, String>() }
    fun effectiveBaseOverrides(): List<ModuleBaseOverride> = services.filter { it.id in selected }.flatMap { service ->
        taskModuleOverrides(service, task.featureBranch, baseOverrideValues, targetBranchValues)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加服务") },
        text = {
            Column(Modifier.widthIn(min = 560.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("新增服务沿用任务分支：${task.featureBranch}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                services.forEach { service ->
                    val checked = service.id in selected
                    OutlinedCard(
                        Modifier.fillMaxWidth().clickable { selected = if (checked) selected - service.id else selected + service.id },
                        colors = CardDefaults.outlinedCardColors(containerColor = if (checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(11.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked, { selected = if (checked) selected - service.id else selected + service.id })
                                Column(Modifier.weight(1f)) {
                                    Text(service.displayName, fontWeight = FontWeight.SemiBold)
                                    Text(service.strategy.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (checked) {
                                val moduleRefs = if (service.strategy == WorkspaceStrategy.INDEPENDENT_CLONE) {
                                    service.cloneModules.map { module ->
                                        TaskModuleBranchOption(
                                            id = module.id,
                                            name = module.name.ifBlank { module.id },
                                            defaultRef = module.branch,
                                            remote = runCatching { RemoteBranchRef.parse(module.branch).remote }.getOrDefault("origin"),
                                        )
                                    }
                                } else service.modules.map { module ->
                                    TaskModuleBranchOption(
                                        id = module.id,
                                        name = module.name.ifBlank { module.id },
                                        defaultRef = module.baseRef,
                                        remote = module.baseRemote,
                                    )
                                }
                                moduleRefs.forEach { module ->
                                    val key = "${service.id}::${module.id}"
                                    RemoteBranchPicker(
                                        value = baseOverrideValues[key] ?: module.defaultRef,
                                        onValueChange = { baseOverrideValues[key] = it },
                                        label = "${module.name} · 本次基础分支",
                                        repositoryId = service.repositoryId,
                                        controller = controller,
                                        modifier = Modifier.fillMaxWidth().padding(start = 42.dp, top = 6.dp),
                                        remote = module.remote,
                                    )
                                    if (service.strategy == WorkspaceStrategy.STANDARD_WORKTREE) {
                                        val defaultTarget = defaultTaskModuleTargetBranch(task.featureBranch, service, module.id)
                                        TaskTargetBranchField(
                                            value = targetBranchValues[key] ?: defaultTarget,
                                            onValueChange = { value ->
                                                if (value == defaultTarget) targetBranchValues.remove(key)
                                                else targetBranchValues[key] = value
                                            },
                                            label = "${module.name} · 本次目标分支",
                                            modifier = Modifier.fillMaxWidth().padding(start = 42.dp, top = 6.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    checkingBranchReuse = controller.taskController.inspectAddServicesBranchReuse(
                        task = task,
                        serviceIds = selected.toList(),
                        baseOverrides = effectiveBaseOverrides(),
                        onResolved = { conflicts ->
                            if (conflicts.isEmpty()) {
                                onAdd(selected.toList(), emptySet(), effectiveBaseOverrides())
                            } else {
                                branchConflicts = conflicts
                            }
                        },
                        onFinished = { checkingBranchReuse = false },
                    )
                },
                enabled = selected.isNotEmpty() && !controller.busy && !checkingBranchReuse,
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
    branchConflicts?.let { conflicts ->
        BranchReuseConfirmationDialog(
            conflicts = conflicts,
            onDismiss = { branchConflicts = null },
            onConfirm = { keys ->
                branchConflicts = null
                onAdd(selected.toList(), keys, effectiveBaseOverrides())
            },
        )
    }
}
