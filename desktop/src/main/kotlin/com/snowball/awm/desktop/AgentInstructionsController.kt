package com.snowball.awm.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.snowball.awm.core.AgentConflictResolution
import com.snowball.awm.core.AgentDocumentPropagationService
import com.snowball.awm.core.AgentDocumentService
import com.snowball.awm.core.AgentFileChange
import com.snowball.awm.core.AgentFileMonitor
import com.snowball.awm.core.AgentInstructionScope
import com.snowball.awm.core.AgentTaskTemplate
import com.snowball.awm.core.AgentTaskTemplateStore
import com.snowball.awm.core.ApplicationPaths
import com.snowball.awm.core.AwmTime
import com.snowball.awm.core.ModuleDisplayNaming
import com.snowball.awm.core.ModuleBaseOverride
import com.snowball.awm.core.TaskServiceSelection
import com.snowball.awm.core.TaskModuleSelection
import com.snowball.awm.core.RemoteBranchRef
import com.snowball.awm.core.RepositoryConfig
import com.snowball.awm.core.RequirementMaterialsDirectory
import com.snowball.awm.core.ServiceWorkspace
import com.snowball.awm.core.TaskApplicationService
import com.snowball.awm.core.TaskBranchNaming
import com.snowball.awm.core.TaskManifest
import com.snowball.awm.core.TaskNaming
import com.snowball.awm.core.WorkspaceLayout
import com.snowball.awm.core.WorkspaceStrategy
import com.snowball.awm.core.toInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

data class AgentInstructionsUiState(
    val revision: Long,
    val conflict: AgentFileChange.Conflict?,
    val templates: List<AgentTaskTemplate>,
)

/** Owns three-level AGENTS.md editing, monitoring, propagation and conflict resolution. */
class AgentInstructionsController internal constructor(
    private val session: AppSessionStore,
    private val paths: ApplicationPaths,
    private val documents: AgentDocumentService,
    private val propagation: AgentDocumentPropagationService,
    private val tasks: TaskApplicationService,
    private val monitor: AgentFileMonitor,
    private val templateStore: AgentTaskTemplateStore = AgentTaskTemplateStore(paths),
    private val operations: OperationRunner,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val taskDirectory: (TaskManifest) -> Path,
    private val desktopActions: DesktopActions,
    private val isBusy: () -> Boolean,
    private val showError: (Throwable) -> Unit,
    private val showStatus: (String) -> Unit,
) {
    private var revision by mutableStateOf(0L)
    private var conflict by mutableStateOf<AgentFileChange.Conflict?>(null)
    private var templates by mutableStateOf(runCatching { templateStore.list() }.getOrDefault(emptyList()))
    val state: AgentInstructionsUiState get() = AgentInstructionsUiState(revision, conflict, templates)

    fun readGlobal(): String = read(paths.globalAgents)
    fun readGroup(groupId: String): String = read(paths.groupAgents(groupId))
    fun markGlobalEdited(content: String) = monitor.markLocalEdit(paths.globalAgents, content)
    fun markGroupEdited(groupId: String, content: String) = monitor.markLocalEdit(paths.groupAgents(groupId), content)

    fun saveGlobal(content: String): Boolean = operations.run("正在保存全局 AGENTS.md…", "全局 AGENTS.md 已保存", block = {
        requireNoReservedMarkers(content)
        monitor.save(paths.globalAgents, content)
        requirePropagationSucceeded(propagation.propagate(session.config, AgentInstructionScope.Global).failures)
    })

    fun saveGroup(groupId: String, content: String): Boolean = operations.run("正在保存组 AGENTS.md…", "组 AGENTS.md 已保存", block = {
        requireNoReservedMarkers(content)
        monitor.save(paths.groupAgents(groupId), content)
        requirePropagationSucceeded(propagation.propagate(session.config, AgentInstructionScope.Group(groupId)).failures)
    })

    fun saveTemplate(id: String?, name: String, content: String): Boolean =
        operations.run("正在保存模板…", "模板已保存", block = {
            val trimmedName = name.trim()
            val trimmedContent = content.trim()
            requireNoReservedMarkers(trimmedContent)
            val now = AwmTime.format(Instant.now())
            val current = templateStore.list()
            val updated = if (id == null || current.none { it.id == id }) {
                current + AgentTaskTemplate(UUID.randomUUID().toString(), trimmedName, trimmedContent, now)
            } else {
                current.map { if (it.id == id) it.copy(name = trimmedName, content = trimmedContent, updatedAt = now) else it }
            }
            templateStore.saveAll(updated)
        }, onSuccess = { refreshTemplates() })

    fun deleteTemplate(id: String): Boolean =
        operations.run("正在删除模板…", "模板已删除", block = {
            templateStore.saveAll(templateStore.list().filterNot { it.id == id })
        }, onSuccess = { refreshTemplates() })

    private fun refreshTemplates() {
        templates = runCatching { templateStore.list() }.getOrDefault(templates)
        revision++
    }

    fun readTaskNotes(task: TaskManifest): String = runCatching {
        val content = monitor.track(taskDirectory(task).resolve("AGENTS.md")).content
        if (content.isNotBlank()) documents.extractTaskNotes(content) else ""
    }.getOrElse { showError(it); "" }

    fun saveTaskNotes(task: TaskManifest, notes: String): Boolean = operations.run("正在保存任务说明…", "任务说明已保存", block = {
        requireNoReservedMarkers(notes)
        val directory = taskDirectory(task)
        val path = directory.resolve("AGENTS.md")
        val current = monitor.snapshot(path)?.content ?: monitor.track(path).content
        monitor.save(path, replaceTaskNotes(current, notes))
        tasks.saveTaskNotes(session.config, directory, notes)
        monitor.checkNow()
    })

    fun markTaskNotesEdited(task: TaskManifest, notes: String) {
        val path = taskDirectory(task).resolve("AGENTS.md")
        runCatching {
            requireNoReservedMarkers(notes)
            val current = monitor.snapshot(path)?.content ?: monitor.track(path).content
            monitor.markLocalEdit(path, replaceTaskNotes(current, notes))
        }.onFailure(showError)
    }

    fun revealGlobal() = runCatching { desktopActions.reveal(documents.ensureGlobalFile()).getOrThrow() }.onFailure(showError)
    fun revealGroup(groupId: String) = runCatching { desktopActions.reveal(documents.ensureGroupFile(groupId)).getOrThrow() }.onFailure(showError)
    fun onWindowFocused() { if (!isBusy()) monitor.checkNow() }

    fun resolveConflict(resolution: AgentConflictResolution): Boolean {
        val current = conflict ?: return false
        val task = session.tasks.firstOrNull {
            taskDirectory(it).resolve("AGENTS.md").toAbsolutePath().normalize() == current.path.toAbsolutePath().normalize()
        }
        return operations.run("正在处理 Agent 文件冲突…", "Agent 文件冲突已处理", block = {
            if (resolution == AgentConflictResolution.USE_LOCAL && task != null) {
                val notes = documents.extractTaskNotes(current.localContent)
                monitor.resolve(current.path, AgentConflictResolution.USE_DISK)
                tasks.saveTaskNotes(session.config, taskDirectory(task), notes)
                monitor.checkNow()
            } else {
                monitor.resolve(current.path, resolution)
            }
        }, onSuccess = {
            conflict = null
            revision++
            if (resolution == AgentConflictResolution.USE_LOCAL && task == null) synchronize(current.path)
        })
    }

    fun handleFileChange(change: AgentFileChange) {
        when (change) {
            is AgentFileChange.Conflict -> conflict = change
            is AgentFileChange.Reloaded -> { revision++; synchronize(change.path) }
        }
    }

    /** Renders exactly what [saveTaskNotes] would write, without touching disk. */
    fun previewTask(task: TaskManifest, notes: String): String =
        documents.renderPreview(taskDirectory(task), task, session.config.repositories.map(RepositoryConfig::toInfo), notes)

    fun preview(
        folderName: String,
        branch: String,
        groupId: String,
        serviceIds: Set<String>,
        requirementLink: String,
        notes: String,
        serviceSelections: List<TaskServiceSelection> = emptyList(),
        requirementMaterials: RequirementMaterialsDirectory = RequirementMaterialsDirectory(),
    ): String {
        val config = session.config
        val root = config.taskRoot?.let(Path::of) ?: paths.temp
        val normalizedName = folderName.ifBlank { "任务名称" }
        val normalizedBranch = branch.trim().ifBlank { "feature/example" }
        val directoryName = runCatching { TaskNaming.requireValidDirectoryName(normalizedName) }.getOrDefault("任务名称")
        val now = AwmTime.format(Instant.now())
        val directory = root.resolve(directoryName)
        val repositories = config.repositories.associateBy(RepositoryConfig::id)
        val selectionsByService = serviceSelections.associateBy(TaskServiceSelection::serviceId)
        val workspaces = config.group(groupId).services.filter { it.id in serviceIds }.flatMap { service ->
            val repository = repositories[service.repositoryId] ?: return@flatMap emptyList()
            val selectedModules = selectionsByService[service.id]?.modules ?: service.modules.map { configured ->
                TaskModuleSelection.configured(
                    configured,
                    if (service.modules.size == 1) normalizedBranch else "$normalizedBranch-${configured.name}",
                )
            }
            selectedModules.map { selectedModule ->
                val baseRef = selectedModule.baseRef
                val module = selectedModule.toConfig()
                val defaultTarget = if (service.modules.size == 1) normalizedBranch else "$normalizedBranch-${module.name}"
                val target = selectedModule.targetBranch ?: if (module.strategy == WorkspaceStrategy.STANDARD_WORKTREE) defaultTarget else ""
                val branch = if (module.strategy == WorkspaceStrategy.INDEPENDENT_CLONE && target.isBlank()) {
                    RemoteBranchRef.parse(baseRef).branch
                } else target
                ServiceWorkspace(
                    repository.id, service.displayName, repository.rootPath,
                    directory.resolve(WorkspaceLayout.moduleDirectoryName(service, module)).toString(),
                    service.developmentTool, branch, groupServiceId = service.id, moduleId = module.id,
                    moduleName = ModuleDisplayNaming.resolve(module.name, service.displayName, module.baseRef, service.modules.size),
                    strategy = module.strategy, originUrl = repository.originUrl, baseRef = module.baseRef,
                    moduleSource = selectedModule.source, targetBranch = target.ifBlank { null }, tagEnabled = module.tagEnabled,
                    tagMode = module.tagMode, tagTargetRef = module.tagTargetRef, tagMessagePrefix = module.tagMessagePrefix,
                )
            }
        }
        val manifest = TaskManifest(
            folderName = normalizedName,
            taskDirectoryName = directoryName,
            featureBranch = normalizedBranch,
            requirementLink = requirementLink.trim(),
            createdAt = now,
            updatedAt = now,
            requirementMaterials = requirementMaterials,
            services = workspaces,
            groupId = groupId,
        )
        return documents.renderPreview(directory, manifest, config.repositories.map(RepositoryConfig::toInfo), notes)
    }

    private fun read(path: Path): String = runCatching { monitor.track(path).content }.getOrElse { showError(it); "" }

    private fun synchronize(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        val instructionScope = when {
            normalized == paths.globalAgents.toAbsolutePath().normalize() -> AgentInstructionScope.Global
            else -> session.config.groups.firstOrNull { paths.groupAgents(it.id).toAbsolutePath().normalize() == normalized }
                ?.let { AgentInstructionScope.Group(it.id) } ?: return
        }
        scope.launch {
            val result = withContext(ioDispatcher) {
                runCatching { requirePropagationSucceeded(propagation.propagate(session.config, instructionScope).failures) }
            }
            result.onSuccess { showStatus("Agent 文件已从磁盘同步") }.onFailure(showError)
        }
    }

    private fun replaceTaskNotes(document: String, notes: String): String {
        documents.extractTaskNotes(document)
        val begin = document.indexOf(AgentDocumentService.TASK_NOTES_BEGIN) + AgentDocumentService.TASK_NOTES_BEGIN.length
        val end = document.indexOf(AgentDocumentService.TASK_NOTES_END)
        return buildString {
            append(document.substring(0, begin)); appendLine()
            if (notes.isNotBlank()) appendLine(notes.trimEnd())
            append(document.substring(end))
        }
    }

    private fun requireNoReservedMarkers(content: String) {
        val marker = listOf(
            AgentDocumentService.GENERATED_BEGIN,
            AgentDocumentService.GENERATED_END,
            AgentDocumentService.TASK_NOTES_BEGIN,
            AgentDocumentService.TASK_NOTES_END,
        ).firstOrNull(content::contains) ?: return
        require(false) { "内容不能包含 AWM 保留标记：$marker" }
    }

    private fun requirePropagationSucceeded(failures: Map<Path, String>) {
        if (failures.isEmpty()) return
        error("部分任务 AGENTS.md 同步失败：" + failures.entries.joinToString { (path, reason) -> "${path.fileName}（$reason）" })
    }
}
