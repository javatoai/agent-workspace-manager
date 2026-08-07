package com.snowball.taskwt.core

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

class AgentDocumentFormatException(message: String) : IllegalStateException(message)

interface AgentDocuments {
    fun readGlobal(): String
    fun saveGlobal(content: String)
    fun readGroup(groupId: String): String
    fun saveGroup(groupId: String, content: String)
    fun writeTaskDocument(
        taskDirectory: Path,
        manifest: TaskManifest,
        repositories: List<RepositoryInfo>,
        taskNotes: String? = null,
    ): Path
}

/**
 * Owns the three-level AGENTS.md protocol. The disk files are authoritative;
 * config.json intentionally stores no duplicate instruction text.
 */
class AgentDocumentService(
    private val paths: ApplicationPaths = ApplicationPaths.systemDefault(),
) : AgentDocuments {
    override fun readGlobal(): String = readOrEmpty(paths.globalAgents)

    override fun saveGlobal(content: String) {
        validateUserContent(content, "全局 Agent 说明")
        writeAtomically(paths.globalAgents, content)
    }

    override fun readGroup(groupId: String): String = readOrEmpty(paths.groupAgents(groupId))

    override fun saveGroup(groupId: String, content: String) {
        validateUserContent(content, "业务组 Agent 说明")
        writeAtomically(paths.groupAgents(groupId), content)
    }

    override fun writeTaskDocument(
        taskDirectory: Path,
        manifest: TaskManifest,
        repositories: List<RepositoryInfo>,
        taskNotes: String?,
    ): Path {
        taskDirectory.createDirectories()
        val target = taskDirectory.resolve(AgentsMdWriter.FILE_NAME)
        val preservedNotes = when {
            taskNotes != null -> taskNotes
            target.exists() -> extractTaskNotes(Files.readString(target))
            else -> ""
        }
        validateUserContent(preservedNotes, "任务人工说明")
        val generated = buildGeneratedContent(taskDirectory, manifest, repositories)
        val document = buildString {
            appendLine(GENERATED_BEGIN)
            append(generated.trimEnd())
            appendLine()
            appendLine(GENERATED_END)
            appendLine()
            appendLine(TASK_NOTES_BEGIN)
            if (preservedNotes.isNotBlank()) {
                appendLine(preservedNotes.trimEnd())
            }
            appendLine(TASK_NOTES_END)
        }
        writeAtomically(target, document)
        return target
    }

    fun renderPreview(
        taskDirectory: Path,
        manifest: TaskManifest,
        repositories: List<RepositoryInfo>,
        taskNotes: String,
    ): String {
        val generated = buildGeneratedContent(taskDirectory, manifest, repositories)
        return buildString {
            appendLine(GENERATED_BEGIN)
            append(generated.trimEnd())
            appendLine()
            appendLine(GENERATED_END)
            appendLine()
            appendLine(TASK_NOTES_BEGIN)
            if (taskNotes.isNotBlank()) appendLine(taskNotes.trimEnd())
            appendLine(TASK_NOTES_END)
        }
    }

    fun extractTaskNotes(document: String): String {
        val generatedBegin = uniqueMarkerIndex(document, GENERATED_BEGIN)
        val generatedEnd = uniqueMarkerIndex(document, GENERATED_END)
        val begin = document.indexOf(TASK_NOTES_BEGIN)
        val end = document.indexOf(TASK_NOTES_END)
        if (generatedBegin < 0 || generatedEnd < 0 || begin < 0 || end < 0 ||
            !(generatedBegin < generatedEnd && generatedEnd < begin && begin < end) ||
            document.indexOf(TASK_NOTES_BEGIN, begin + TASK_NOTES_BEGIN.length) >= 0 ||
            document.indexOf(TASK_NOTES_END, end + TASK_NOTES_END.length) >= 0
        ) {
            throw AgentDocumentFormatException(
                "AGENTS.md 的 TASKWT:TASK-NOTES 标记缺失或损坏；为避免覆盖人工内容，已停止生成。",
            )
        }
        return document.substring(begin + TASK_NOTES_BEGIN.length, end).trim('\r', '\n')
    }

    private fun uniqueMarkerIndex(document: String, marker: String): Int {
        val first = document.indexOf(marker)
        if (first < 0 || document.indexOf(marker, first + marker.length) >= 0) return -1
        return first
    }

    private fun buildGeneratedContent(
        taskDirectory: Path,
        manifest: TaskManifest,
        repositories: List<RepositoryInfo>,
    ): String {
        val global = readGlobal()
        val group = readGroup(manifest.groupId)
        validateUserContent(global, "全局 Agent 说明")
        validateUserContent(group, "业务组 Agent 说明")
        return buildString {
            append(AgentsMdWriter.render(taskDirectory, manifest, repositories, ""))
            appendInstructionSection("全局 Agent 说明", global)
            appendInstructionSection("业务组 Agent 说明", group)
            appendLine()
            appendLine("## 说明优先级")
            appendLine()
            appendLine("发生冲突时按：任务人工说明 > 业务组说明 > 全局说明。")
        }
    }

    private fun StringBuilder.appendInstructionSection(title: String, content: String) {
        if (content.isBlank()) return
        appendLine()
        appendLine("## $title")
        appendLine()
        appendLine(content.trim())
    }

    private fun readOrEmpty(path: Path): String =
        if (path.exists()) Files.readString(path) else ""

    private fun validateUserContent(content: String, label: String) {
        val reserved = listOf(GENERATED_BEGIN, GENERATED_END, TASK_NOTES_BEGIN, TASK_NOTES_END)
        require(reserved.none(content::contains)) { "$label 包含 TASKWT 保留标记，已拒绝保存或生成" }
    }

    private fun writeAtomically(target: Path, content: String) {
        target.parent.createDirectories()
        val temporary = Files.createTempFile(target.parent, ".${target.fileName}-", ".tmp")
        Files.writeString(temporary, content)
        try {
            Files.move(
                temporary,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        const val GENERATED_BEGIN = "<!-- TASKWT:GENERATED:BEGIN -->"
        const val GENERATED_END = "<!-- TASKWT:GENERATED:END -->"
        const val TASK_NOTES_BEGIN = "<!-- TASKWT:TASK-NOTES:BEGIN -->"
        const val TASK_NOTES_END = "<!-- TASKWT:TASK-NOTES:END -->"
    }
}
