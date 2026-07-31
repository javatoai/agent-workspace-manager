package com.snowball.taskwt.core

import java.nio.file.Files
import java.nio.file.Path

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

    fun render(
        taskDirectory: Path,
        manifest: TaskManifest,
        allRepositories: List<RepositoryInfo>,
        appendix: String,
    ): String {
        val taskRepoIds = manifest.services.map { it.repositoryId }.toSet()
        val others = allRepositories
            .filter { it.id !in taskRepoIds }
            .sortedBy { it.name.lowercase() }
        val appendixTrimmed = appendix.trim()

        return buildString {
            appendLine("# 任务工作区说明（AGENTS）")
            appendLine()
            appendLine("> 本文件由 Task Worktree Manager 自动生成，请勿手改；变更任务后会覆盖重写。")
            appendLine()
            appendLine("## 基本信息")
            appendLine()
            appendLine("| 项 | 值 |")
            appendLine("|----|-----|")
            appendLine("| 文件夹名 | ${escapeCell(manifest.folderName)} |")
            appendLine("| 任务目录 | `${taskDirectory.toAbsolutePath().normalize()}` |")
            appendLine("| Feature 分支 | `${manifest.featureBranch}` |")
            appendLine("| 状态 | ${manifest.status.name} |")
            appendLine("| 更新时间 | ${manifest.updatedAt} |")
            appendLine()
            appendLine("## 需求链接")
            appendLine()
            appendLine(manifest.requirementLink.ifBlank { "（未填写）" })
            appendLine()
            appendLine("## 本任务可改动的 Worktree")
            appendLine()
            appendLine("| 服务名 | 仓库路径 | Worktree 路径 | 分支 | 状态 |")
            appendLine("|--------|----------|---------------|------|------|")
            if (manifest.services.isEmpty()) {
                appendLine("| （无） |  |  |  |  |")
            } else {
                manifest.services.forEach { workspace ->
                    appendLine(
                        "| ${escapeCell(workspace.serviceName)} | `${workspace.repositoryPath}` | " +
                            "`${workspace.worktreePath}` | `${workspace.branch}` | ${workspace.status.name} |",
                    )
                }
            }
            appendLine()
            appendLine("## 其它本地服务（只读上下文）")
            appendLine()
            appendLine(
                "下列仓库**不在**本任务 Worktree 中。可用于阅读代码、梳理调用链与全局上下文；" +
                    "**禁止**在这些主 checkout 路径下直接改代码或提交。",
            )
            appendLine()
            appendLine("| 服务名 | 本地仓库路径 |")
            appendLine("|--------|----------------|")
            if (others.isEmpty()) {
                appendLine("| （无） |  |")
            } else {
                others.forEach { repo ->
                    appendLine("| ${escapeCell(repo.name)} | `${repo.rootPath}` |")
                }
            }
            appendLine()
            appendLine("## Agent 使用提示")
            appendLine()
            appendLine("- **改代码**：仅允许修改上方「本任务可改动的 Worktree」中的路径；不要改主 checkout，也不要改「其它本地服务」表中的路径。")
            appendLine("- **读代码**：可以阅读「其它本地服务」及本任务 worktree，以了解全链路上下文。")
            appendLine(
                "- **范围不够时**：若评估改动需要额外服务仓库，**不要擅自改主仓**；应明确告知用户，" +
                    "请其在 Task Worktree Manager 中「添加服务」为本任务增加对应 Worktree 后再改。",
            )
            appendLine("- 所有本任务服务共用同一 Feature 分支名（见上表）。")
            appendLine("- 需求上下文以「需求链接」为准：")
            appendLine(
                "  - 飞书项目链接（`project.feishu.cn` obt/rta）：优先用飞书项目 skill/CLI" +
                    "（如 `feishu-project-helper`）查询详情/评论/Tech Doc。",
            )
            appendLine("  - 其它 http(s)：可用浏览器打开。")
            appendLine("  - 纯文本：直接以文本为上下文。")
            if (appendixTrimmed.isNotEmpty()) {
                appendLine()
                appendLine("## 自定义说明")
                appendLine()
                appendLine(appendixTrimmed)
            }
            appendLine()
        }
    }

    private fun escapeCell(value: String): String = value.replace("|", "\\|")
}
