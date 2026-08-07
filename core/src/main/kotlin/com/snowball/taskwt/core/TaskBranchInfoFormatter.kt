package com.snowball.taskwt.core

object TaskBranchInfoFormatter {
    fun format(task: TaskManifest): String = buildString {
        if (task.requirementLink.isNotBlank()) {
            append("需求链接：")
            append(task.requirementLink.trim())
            append("\n\n")
        }
        task.services.forEachIndexed { index, workspace ->
            if (index > 0) append('\n')
            append(workspace.serviceName)
            append('：')
            append(workspace.branch)
        }
    }
}
