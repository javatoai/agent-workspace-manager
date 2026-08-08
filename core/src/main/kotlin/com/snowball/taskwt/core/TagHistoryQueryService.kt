package com.snowball.taskwt.core

import java.nio.file.Path

/** Reads persisted Tag operations without exposing JSON storage details to Desktop UI code. */
class TagHistoryQueryService(
    private val operations: TagOperationStore = TagOperationStore(),
) {
    fun list(config: AppConfig, tasks: List<TaskManifest>): List<TagOperation> {
        val taskRoot = config.taskRoot?.let(Path::of) ?: return emptyList()
        return tasks.flatMap { task ->
            runCatching { operations.list(taskRoot.resolve(task.taskDirectoryName)) }.getOrDefault(emptyList())
        }.sortedByDescending(TagOperation::updatedAt)
    }
}
