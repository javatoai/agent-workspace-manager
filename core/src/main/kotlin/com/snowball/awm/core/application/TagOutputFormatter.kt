package com.snowball.awm.core

object TagOutputFormatter {
    fun format(
        requirementLink: String,
        operations: List<TagOperation>,
        includeFailures: Boolean,
    ): String {
        val successes = operations.filter {
            it.state == TagOperationState.SUCCESS && !it.tag.isNullOrBlank()
        }
        val failures = operations.filter { it !in successes }
        return buildString {
            if (requirementLink.isNotBlank()) {
                append("需求链接：")
                append(requirementLink.trim())
                append("\n\n")
            }
            if (successes.isEmpty()) {
                append("（无）")
            } else {
                successes.forEachIndexed { index, operation ->
                    if (index > 0) append('\n')
                    append(operation.serviceName)
                    append(" · ")
                    append(operation.tag)
                }
            }
            if (includeFailures && failures.isNotEmpty()) {
                append("\n\n构建失败：\n")
                failures.forEachIndexed { index, operation ->
                    if (index > 0) append('\n')
                    append(operation.serviceName)
                    append(" · ")
                    append(operation.state.name)
                    operation.message?.takeIf { it.isNotBlank() }?.let {
                        append(" · ")
                        append(it)
                    }
                }
            }
            append("\n\n")
            if (includeFailures && failures.isNotEmpty()) {
                append("Tag未全部构建成功，请处理失败项后重试")
            } else {
                append("Tag 已构建完毕，请发布以上版本")
            }
        }
    }
}
