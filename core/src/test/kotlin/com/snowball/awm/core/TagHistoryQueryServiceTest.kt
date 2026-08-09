package com.snowball.awm.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class TagHistoryQueryServiceTest {
    @Test
    fun `returns operations from all tasks newest first`() {
        val root = Files.createTempDirectory("tag-history-query-")
        val store = TagOperationStore()
        val olderTask = task("older")
        val newerTask = task("newer")
        store.save(root.resolve("older"), operation("older-op", "2026-08-08 09:00:00"))
        store.save(root.resolve("newer"), operation("newer-op", "2026-08-08 10:00:00"))

        val result = TagHistoryQueryService(store).list(
            AppConfig(taskRoot = root.toString()),
            listOf(olderTask, newerTask),
        )

        assertEquals(listOf("newer-op", "older-op"), result.map(TagOperation::operationId))
    }

    private fun task(name: String) = TaskManifest(
        folderName = name,
        taskDirectoryName = name,
        featureBranch = "feature/$name",
        createdAt = "2026-08-08 08:00:00",
        updatedAt = "2026-08-08 08:00:00",
        lifecycleStatus = TaskLifecycleStatus.ACTIVE,
        services = emptyList(),
    )

    private fun operation(id: String, updatedAt: String) = TagOperation(
        operationId = id,
        folderName = id,
        serviceName = "service",
        repositoryId = "repo",
        featureBranch = "feature/$id",
        testBranch = "release/test",
        remote = "origin",
        state = TagOperationState.SUCCESS,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        tag = "v1.0.0",
    )
}
