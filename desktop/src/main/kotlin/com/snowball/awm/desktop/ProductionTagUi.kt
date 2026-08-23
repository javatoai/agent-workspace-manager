package com.snowball.awm.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.snowball.awm.core.ProductionBaselineState
import com.snowball.awm.core.ProductionFeatureBatchState
import com.snowball.awm.core.ProductionTagBuildState
import com.snowball.awm.core.ProductionTagPipeline
import com.snowball.awm.desktop.GenbuSettingsState

@Composable
internal fun ProductionTagScreen(controller: DesktopApplication) {
    val feature = controller.productionTagController
    val state = feature.state
    val repository = controller.config.repositories.firstOrNull { it.id == state.selectedRepositoryId }
    val pipeline = state.pipeline
    val managedRepositories = controller.config.groups
        .flatMap { it.services }
        .filter { it.enabled }
        .map { it.repositoryId }
        .distinct()
        .mapNotNull { id -> controller.config.repositories.firstOrNull { it.id == id } }
    var serviceMenu by remember { mutableStateOf(false) }
    var historyMenu by remember { mutableStateOf(false) }
    var featureMenuIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        controller.refreshGenbu()
    }

    LaunchedEffect(repository?.id) {
        featureMenuIndex = null
        repository?.let { controller.loadRemoteBranches(it.id) }
    }

    LaunchedEffect(pipeline?.id, pipeline?.releaseSha, pipeline?.featureState, state.featureBranches, state.expectedTag) {
        if (pipeline?.releaseSha != null && pipeline.featureState == ProductionFeatureBatchState.MERGED &&
            state.featureBranches.all { it.isBlank() } && state.expectedTag == null
        ) {
            feature.refreshExpectedTag()
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("服务与流水线", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Genbu 使用仓库名称查询生产版本；所有 Git 写入都在 AWM 隔离 worktree 中完成。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    when (val genbu = controller.genbuSettingsState) {
                        GenbuSettingsState.Idle -> InlineMessage("Genbu 尚未检测，正在准备自动检测。", warning = true)
                        GenbuSettingsState.Loading -> InlineMessage("正在自动检测 Genbu…")
                        is GenbuSettingsState.Loaded -> InlineMessage("Genbu 已发现可用：${genbu.command}")
                        is GenbuSettingsState.Failed -> {
                            InlineMessage("Genbu 不可用：${genbu.message}", error = true)
                            OutlinedButton(onClick = controller::openProductionTagSettings) { Text("前往生产 Tag 设置") }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            OutlinedButton(onClick = { serviceMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(repository?.name ?: "选择服务", Modifier.weight(1f))
                                Icon(Icons.Outlined.KeyboardArrowDown, null)
                            }
                            DropdownMenu(serviceMenu, onDismissRequest = { serviceMenu = false }) {
                                managedRepositories.forEach { candidate ->
                                    DropdownMenuItem(
                                        text = { Text(candidate.name) },
                                        onClick = {
                                            serviceMenu = false
                                            feature.selectRepository(candidate.id)
                                            controller.loadRemoteBranches(candidate.id)
                                        },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        val history = state.pipelines.filter { it.repositoryId == repository?.id }
                        Box {
                            OutlinedButton(onClick = { historyMenu = true }, enabled = history.isNotEmpty()) {
                                Text("进行中 / 历史")
                                Icon(Icons.Outlined.KeyboardArrowDown, null)
                            }
                            DropdownMenu(historyMenu, onDismissRequest = { historyMenu = false }) {
                                history.forEach { item ->
                                    val lastTag = item.buildRecords.lastOrNull()?.expectedTag ?: "尚未打 Tag"
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(item.releaseBranch)
                                                Text(
                                                    "${if (item.closed) "已关闭" else "进行中"} · $lastTag · ${item.updatedAt}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                )
                                            }
                                        },
                                        trailingIcon = { Text(if (item.closed) "查看" else "继续") },
                                        onClick = { historyMenu = false; feature.selectPipeline(item.id) },
                                    )
                                }
                            }
                        }
                    }
                    if (pipeline == null || pipeline.closed) {
                        Button(
                            onClick = feature::createPipeline,
                            enabled = repository != null && !controller.busy &&
                                controller.genbuSettingsState is GenbuSettingsState.Loaded,
                        ) {
                            Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("新建生产 Tag 流水线")
                        }
                    }
                    state.inlineError?.let { InlineMessage(it, error = true) }
                }
            }
        }

        if (pipeline != null) {
            pipeline.activeOperation?.let { operation ->
                item {
                    ProductionCard("未完成操作", "AWM 已保留跨进程操作租约，继续前会只读核对远端结果。") {
                        KeyValue("操作", operation.action.name)
                        KeyValue("操作 ID", operation.id)
                        KeyValue("开始时间", operation.startedAt)
                        Button(onClick = feature::resumePipeline, enabled = !controller.busy) { Text("继续并对账") }
                    }
                }
            }
            item { BaselineCard(controller, pipeline) }
            if (!pipeline.closed && pipeline.releaseSha != null) {
                item {
                    FeatureCard(
                        controller = controller,
                        pipeline = pipeline,
                        expandedIndex = featureMenuIndex,
                        onExpandedIndexChange = { featureMenuIndex = it },
                    )
                }
            }
            val hasPendingFeatureDraft = state.featureBranches.any { it.isNotBlank() }
            if (!pipeline.closed && pipeline.featureState == ProductionFeatureBatchState.MERGED && !hasPendingFeatureDraft) {
                item { BuildTagCard(controller, state.expectedTag, state.expectedTagAlreadyBuilt, pipeline.activeOperation == null) }
            }
            item { BuildRecordsCard(pipeline) }
            item { AuditRecordsCard(pipeline) }
            if (!pipeline.closed) {
                item {
                    OutlinedButton(onClick = feature::closePipeline, enabled = !controller.busy && pipeline.activeOperation == null) {
                        Text("关闭当前流水线")
                    }
                }
            }
        }
    }
}

@Composable
private fun BaselineCard(controller: DesktopApplication, pipeline: ProductionTagPipeline) {
    val feature = controller.productionTagController
    val operationIdle = pipeline.activeOperation == null
    ProductionCard("1. 生产基线", "生产环境固定与远端 master 比较。") {
        KeyValue("当前生产 Tag", pipeline.productionTag)
        KeyValue("生产服务 / 环境", "${pipeline.productionService.ifBlank { pipeline.serviceName }} / ${pipeline.productionEnvironment.ifBlank { "PRD" }}")
        KeyValue("生产 Tag SHA", pipeline.productionTagSha)
        KeyValue("master SHA", pipeline.masterSha)
        KeyValue("查询时间 / 来源", "${pipeline.baselineCheckedAt.ifBlank { pipeline.updatedAt }} / ${pipeline.baselineSource}")
        if (pipeline.genbuCommand.isNotBlank()) KeyValue("Genbu 路径", pipeline.genbuCommand)
        if (pipeline.operator.isNotBlank()) KeyValue("Git 操作人", pipeline.operator)
        if (pipeline.productionPods.isNotEmpty()) {
            KeyValue("生产 Pod", pipeline.productionPods.joinToString { "${it.name}:${it.version}/ready=${it.ready}/restart=${it.restartCount}" })
        }
        when (pipeline.baselineState) {
            ProductionBaselineState.ALREADY_CONTAINED -> InlineMessage("生产 Tag 已包含在 master 中，无需合并。")
            ProductionBaselineState.MERGE_REQUIRED -> InlineMessage("生产 Tag 与 master 有差异，需要合并。", warning = true)
            ProductionBaselineState.AWAITING_MERGE_REQUEST -> InlineMessage("当前用户不能直接推送 master，请在合并请求中完成审批。", warning = true)
            ProductionBaselineState.NETWORK_ERROR -> InlineMessage("网络异常", error = true)
            ProductionBaselineState.CONFIGURATION_ERROR -> InlineMessage("配置异常", error = true)
            ProductionBaselineState.CHECKING -> InlineMessage("正在检查生产基线…")
        }
        pipeline.mergeRequest?.takeIf { it.targetBranch == "master" }?.let { request ->
            MergeRequestActions(controller, request.url)
        }
        pipeline.unmanagedReleaseSha?.let { sha ->
            InlineMessage("存在未接管的 Release：${pipeline.releaseBranch}@$sha。AWM 不会复用或覆盖该分支。", error = true)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pipeline.mergeRequest == null && pipeline.releaseSha == null) {
                OutlinedButton(onClick = feature::refreshBaseline, enabled = !controller.busy && operationIdle) {
                    Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("刷新生产基线")
                }
            }
            if (pipeline.baselineState == ProductionBaselineState.MERGE_REQUIRED) {
                Button(onClick = feature::mergeProduction, enabled = !controller.busy && operationIdle) { Text("合并生产 Tag 到 master") }
            }
            if (pipeline.baselineState == ProductionBaselineState.AWAITING_MERGE_REQUEST) {
                Button(onClick = feature::refreshMergeRequest, enabled = !controller.busy && operationIdle) { Text("刷新合并状态") }
            }
            if (pipeline.baselineState == ProductionBaselineState.ALREADY_CONTAINED && pipeline.releaseSha == null) {
                Button(onClick = feature::createRelease, enabled = !controller.busy && operationIdle && pipeline.unmanagedReleaseSha == null) {
                    Text("创建 ${pipeline.releaseBranch}")
                }
            }
        }
        pipeline.releaseSha?.let {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            KeyValue("Release 分支", pipeline.releaseBranch)
            KeyValue("Release SHA", it)
        }
    }
}

@Composable
private fun FeatureCard(
    controller: DesktopApplication,
    pipeline: ProductionTagPipeline,
    expandedIndex: Int?,
    onExpandedIndexChange: (Int?) -> Unit,
) {
    val feature = controller.productionTagController
    val state = feature.state
    val operationIdle = pipeline.activeOperation == null
    val branches = when (val remote = controller.remoteBranchState(pipeline.repositoryId, "origin")) {
        is RemoteBranchesState.Loaded -> remote.branches
        is RemoteBranchesState.Loading -> remote.staleBranches
        is RemoteBranchesState.Failed -> remote.staleBranches
        RemoteBranchesState.Idle -> emptyList()
    }.map { it.removePrefix("origin/") }
        .filter { it.startsWith("feature/") }
        .distinct()
    ProductionCard("2. Feature 合并", "先固定并确认远端 SHA；再用一个按钮完成冲突检测与无冲突合并。") {
        state.featureBranches.forEachIndexed { index, value ->
            var dragDistance by remember(index, value) { mutableStateOf(0f) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { feature.updateFeatureInput(index, it) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Feature 分支 ${index + 1}") },
                        singleLine = true,
                        enabled = !controller.busy && pipeline.mergeRequest == null && operationIdle,
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    controller.loadRemoteBranches(pipeline.repositoryId, force = true)
                                    onExpandedIndexChange(index)
                                },
                                enabled = pipeline.mergeRequest == null && operationIdle,
                            ) { Icon(Icons.Outlined.KeyboardArrowDown, "选择远端分支") }
                        },
                    )
                    DropdownMenu(
                        expanded = expandedIndex == index,
                        onDismissRequest = { onExpandedIndexChange(null) },
                        modifier = Modifier.heightIn(max = 320.dp),
                    ) {
                        val candidates = branches.filter { value.isBlank() || it.contains(value, ignoreCase = true) }
                        if (candidates.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text(if (branches.isEmpty()) "正在读取远端分支…" else "没有匹配分支") },
                                onClick = {},
                                enabled = false,
                            )
                        }
                        candidates.take(100).forEach { branch ->
                            DropdownMenuItem(
                                text = { Text(branch) },
                                onClick = {
                                    feature.updateFeatureInput(index, branch)
                                    onExpandedIndexChange(null)
                                    feature.resolveFeatures()
                                },
                            )
                        }
                    }
                }
                Icon(
                    Icons.Outlined.DragHandle,
                    "拖动排序",
                    Modifier.size(40.dp).padding(8.dp).pointerInput(
                        index,
                        state.featureBranches.size,
                        controller.busy,
                        pipeline.mergeRequest,
                    ) {
                        val threshold = 32.dp.toPx()
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragDistance = 0f },
                            onDragCancel = { dragDistance = 0f },
                            onDragEnd = {
                                if (!controller.busy && pipeline.mergeRequest == null && operationIdle) when {
                                    dragDistance <= -threshold -> feature.moveFeatureInput(index, -1)
                                    dragDistance >= threshold -> feature.moveFeatureInput(index, 1)
                                }
                                dragDistance = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragDistance += amount.y
                            },
                        )
                    },
                )
                IconButton(onClick = { feature.removeFeatureInput(index) }, enabled = !controller.busy && pipeline.mergeRequest == null && operationIdle) {
                    Icon(Icons.Outlined.Delete, "移除")
                }
                IconButton(
                    onClick = { feature.moveFeatureInput(index, -1) },
                    enabled = !controller.busy && pipeline.mergeRequest == null && operationIdle && index > 0,
                ) { Icon(Icons.Outlined.KeyboardArrowUp, "上移") }
                IconButton(
                    onClick = { feature.moveFeatureInput(index, 1) },
                    enabled = !controller.busy && pipeline.mergeRequest == null && operationIdle && index < state.featureBranches.lastIndex,
                ) { Icon(Icons.Outlined.KeyboardArrowDown, "下移") }
            }
            pipeline.selectedFeatures.getOrNull(index)?.takeIf { it.branch == value && it.sha.isNotBlank() }?.let { selected ->
                Text("已固定 SHA：${selected.sha}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
        val requestedBranches = state.featureBranches.map(String::trim).filter(String::isNotBlank)
        val resolved = requestedBranches.isNotEmpty() &&
            pipeline.selectedFeatures.map { it.branch } == requestedBranches &&
            pipeline.selectedFeatures.all { it.sha.isNotBlank() }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = feature::addFeatureInput, enabled = !controller.busy && pipeline.mergeRequest == null && operationIdle) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("添加 Feature")
            }
            OutlinedButton(
                onClick = feature::resolveFeatures,
                enabled = !controller.busy && pipeline.mergeRequest == null && operationIdle && requestedBranches.isNotEmpty(),
            ) { Text("读取并固定 Feature SHA") }
            Button(
                onClick = feature::detectAndMergeFeatures,
                enabled = !controller.busy && pipeline.mergeRequest == null && operationIdle && resolved,
            ) { Text("检测并合并 ${requestedBranches.size} 个 Feature") }
        }
        when (pipeline.featureState) {
            ProductionFeatureBatchState.CONFLICT -> {
                InlineMessage("检测到冲突，已终止合并。请在 AWM 外解决后重新操作。", error = true)
                pipeline.conflicts.forEach { conflict ->
                    Text("${conflict.branch}：${conflict.files.ifEmpty { listOf("未能读取冲突文件") }.joinToString()}")
                }
            }
            ProductionFeatureBatchState.MERGED -> InlineMessage("Feature 已成功合并到 ${pipeline.releaseBranch}。")
            ProductionFeatureBatchState.AWAITING_MERGE_REQUEST -> {
                InlineMessage("Release 分支不能直接推送，请完成合并请求。", warning = true)
                pipeline.mergeRequest?.let { MergeRequestActions(controller, it.url) }
                Button(onClick = feature::refreshMergeRequest, enabled = !controller.busy) { Text("刷新合并状态") }
            }
            ProductionFeatureBatchState.CHECKING -> InlineMessage("正在检测冲突…")
            ProductionFeatureBatchState.IDLE -> Unit
        }
        if (pipeline.mergedFeatures.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                if (pipeline.featureState == ProductionFeatureBatchState.AWAITING_MERGE_REQUEST) "等待 MR 合并" else "已成功合并",
                fontWeight = FontWeight.SemiBold,
            )
            pipeline.mergedFeatures.forEach { merge ->
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("✓ ${merge.branch}", fontWeight = FontWeight.SemiBold)
                    KeyValue("Feature SHA", merge.sourceSha)
                    KeyValue("Merge Commit", merge.mergeCommit)
                    KeyValue("完成时间", merge.completedAt)
                }
            }
        }
    }
}

@Composable
private fun BuildTagCard(
    controller: DesktopApplication,
    expectedTag: String?,
    alreadyBuilt: Boolean,
    operationIdle: Boolean,
) {
    ProductionCard("3. 构建生产 Tag", "只创建正式 Git Tag 并推送远端，不构建测试包或执行部署。") {
        KeyValue("预期 Tag", expectedTag ?: "正在计算…")
        if (alreadyBuilt && expectedTag != null) {
            InlineMessage("当前 Release SHA 已构建为 $expectedTag，不会重复创建。")
        }
        Button(
            onClick = controller.productionTagController::buildTag,
            enabled = !controller.busy && operationIdle && expectedTag != null && !alreadyBuilt,
        ) { Text("构建并推送生产 Tag ${expectedTag.orEmpty()}") }
    }
}

@Composable
private fun BuildRecordsCard(pipeline: ProductionTagPipeline) {
    if (pipeline.buildRecords.isEmpty()) return
    ProductionCard("生产 Tag 记录", "保留本流水线内每次正式 Tag 操作结果。") {
        pipeline.buildRecords.asReversed().forEach { record ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("预期 Tag：${record.expectedTag}", fontWeight = FontWeight.SemiBold)
                    Text("实际 Tag：${record.actualTag ?: "—"}")
                    KeyValue("Release SHA", record.releaseSha)
                    record.remoteTagSha?.let {
                        KeyValue("远端 Tag SHA", it)
                    }
                    record.failureReason?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    KeyValue("开始时间", record.startedAt.ifBlank { "—" })
                    KeyValue("结束时间", record.completedAt ?: "—")
                    record.remoteUrl?.let { KeyValue("远端", it) }
                }
                StatusPill(
                    when (record.state) {
                        ProductionTagBuildState.PUSHED -> "已推送"
                        ProductionTagBuildState.NO_PERMISSION -> "无推送权限"
                        ProductionTagBuildState.ALREADY_EXISTS -> "已存在"
                        ProductionTagBuildState.FAILED -> "失败"
                        ProductionTagBuildState.PREPARING -> "准备中"
                        ProductionTagBuildState.PUSHING -> "推送中"
                    },
                )
            }
        }
    }
}

@Composable
private fun AuditRecordsCard(pipeline: ProductionTagPipeline) {
    if (pipeline.auditEvents.isEmpty()) return
    ProductionCard("生产操作审计", "按操作 ID 保留基线检查、合并、Release 与 Tag 的状态和远端证据。") {
        pipeline.auditEvents.asReversed().forEach { event ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("${event.action} · ${event.state}", fontWeight = FontWeight.SemiBold)
                KeyValue("操作 ID", event.operationId)
                KeyValue("开始时间", event.startedAt)
                KeyValue("结束时间", event.completedAt ?: "—")
                event.remoteUrl?.let { KeyValue("远端", it) }
                KeyValue("生产 Tag", event.productionTag)
                KeyValue("生产 Tag SHA", event.productionTagSha)
                KeyValue("master SHA", event.masterSha)
                KeyValue("Release 分支", event.releaseBranch)
                KeyValue("Release SHA", event.releaseSha ?: "—")
                if (event.productionService.isNotBlank() || event.productionEnvironment.isNotBlank()) {
                    KeyValue("生产服务 / 环境", "${event.productionService} / ${event.productionEnvironment}")
                }
                if (event.features.isNotEmpty()) {
                    event.features.forEach { feature -> KeyValue("Feature", "${feature.branch}@${feature.sha}") }
                }
                if (event.productionPods.isNotEmpty()) {
                    KeyValue(
                        "生产 Pod",
                        event.productionPods.joinToString("\n") {
                            "${it.name} · ${it.version} · ${it.phase} · ready=${it.ready} · restart=${it.restartCount}"
                        },
                    )
                }
                if (event.operator.isNotBlank()) KeyValue("操作人", event.operator)
                if (event.genbuCommand.isNotBlank()) KeyValue("Genbu", event.genbuCommand)
                event.sourceBranch?.let { KeyValue("源分支", it) }
                event.targetRef?.let { KeyValue("目标", it) }
                event.mergeRequestPlatform?.let { KeyValue("代码平台", it) }
                event.mergeRequestUrl?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) } }
                event.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun MergeRequestActions(controller: DesktopApplication, url: String) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { controller.openUrl(url) }) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("打开合并请求")
        }
        TextButton(onClick = { controller.copyText(url, "合并请求链接已复制") }) { Text("复制链接") }
    }
}

@Composable
private fun ProductionCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            content()
        }
    }
}

@Composable
private fun KeyValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.width(150.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(value, Modifier.weight(1f), fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun InlineMessage(message: String, warning: Boolean = false, error: Boolean = false) {
    val container = when {
        error -> MaterialTheme.colorScheme.errorContainer
        warning -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val content = when {
        error -> MaterialTheme.colorScheme.onErrorContainer
        warning -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(
        color = container,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, content.copy(alpha = 0.18f)),
    ) {
        Text(message, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), color = content)
    }
}
