package com.snowball.awm.core

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** Writes the generated system section of a task-level AGENTS.md document. */
object AgentsMdWriter {
    const val FILE_NAME = "AGENTS.md"

    fun write(
        taskDirectory: Path,
        manifest: TaskManifest,
        allRepositories: List<RepositoryInfo>,
        appendix: String,
    ) {
        Files.writeString(taskDirectory.resolve(FILE_NAME), render(taskDirectory, manifest, allRepositories, appendix))
    }

    /**
     * Renders only task context that an agent needs to safely change code.
     *
     * Task names, branches and lifecycle state are intentionally omitted: they duplicate the UI
     * and become stale quickly, whereas the worktree baseline and path define the edit boundary.
     */
    fun render(
        taskDirectory: Path,
        manifest: TaskManifest,
        allRepositories: List<RepositoryInfo>,
        appendix: String,
    ): String {
        val taskRepositoryIds = manifest.services.map { it.repositoryId }.toSet()
        val otherRepositories = allRepositories
            .filter { it.id !in taskRepositoryIds }
            .sortedBy { it.name.lowercase(Locale.ROOT) }
        val notes = appendix.trim()

        return buildString {
            appendLine("**任务工作区说明（AGENTS）**")
            appendLine()
            appendLine("> 本文件由 Agent Workspace Manager 生成。系统区会随任务说明保存而更新；人工说明区会被保留。")
            appendLine()
            appendLine("## 需求链接")
            appendLine()
            appendLine(manifest.requirementLink.ifBlank { "（未填写）" })
            appendLine()
            appendLine("## 本任务可改动的 Worktree")
            appendLine()
            appendLine("| 服务名 | 创建基线 | 策略 | Worktree 路径 |")
            appendLine("|--------|----------|------|---------------|")
            val workspaces = manifest.services.distinctBy { workspace ->
                listOf(
                    workspace.serviceName,
                    workspace.baseRef.orEmpty(),
                    workspace.strategy.name,
                    workspace.worktreePath,
                )
            }
            if (workspaces.isEmpty()) {
                appendLine("| （无） |  |  |  |")
            } else {
                workspaces.forEach { workspace ->
                    appendLine(
                        "| ${escapeCell(workspace.serviceName)} | `${workspace.baseRef ?: workspace.branch}` | " +
                            "${workspace.strategy.name} | `${workspace.worktreePath}` |",
                    )
                }
            }
            appendLine()
            appendLine("## 其他本地服务（只读上下文）")
            appendLine()
            appendLine("下列仓库不在本任务 Worktree 中。它们仅用于阅读代码和梳理调用链，禁止直接修改或提交。")
            appendLine()
            appendLine("| 服务名 | 本地仓库路径 |")
            appendLine("|--------|--------------|")
            if (otherRepositories.isEmpty()) {
                appendLine("| （无） |  |")
            } else {
                otherRepositories.forEach { repository ->
                    appendLine("| ${escapeCell(repository.name)} | `${repository.rootPath}` |")
                }
            }
            appendLine()
            appendLine("## Agent 使用提示")
            appendLine()
            appendLine("- 只允许修改上方“本任务可改动的 Worktree”中的路径；不要修改主 checkout 或其他本地服务。")
            appendLine("- 需要额外服务时，请先让用户在 Agent Workspace Manager 中为本任务添加服务。")
            if (notes.isNotEmpty()) {
                appendLine()
                appendLine("## 任务人工说明")
                appendLine()
                appendLine(notes)
            }
            appendLine()
        }
    }

    private fun escapeCell(value: String): String = value.replace("|", "\\|")
}
