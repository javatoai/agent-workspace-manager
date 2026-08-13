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

    @Test
    fun `loads early 0_7 operation fields and normalizes state`() {
        val taskDirectory = Files.createTempDirectory("tag-operation-legacy-")
        val directory = taskDirectory.resolve("tag-operations")
        Files.createDirectories(directory)
        Files.writeString(
            directory.resolve("legacy.json"),
            """{
              "operationId":"legacy","folderName":"TASK","serviceName":"service","repositoryId":"repo",
              "featureBranch":"feature/task","testBranch":"release/test","remote":"origin",
              "state":"TEST_BRANCH_PUSHED","createdAt":"2026-01-01","updatedAt":"2026-01-01"
            }""".trimIndent(),
        )

        val operation = TagOperationStore().load(taskDirectory, "legacy")

        assertEquals("feature/task", operation.sourceBranch)
        assertEquals("release/test", operation.targetBranch)
        assertEquals(TagOperationState.TARGET_BRANCH_PUSHED, operation.state)
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
        sourceBranch = "feature/$id",
        targetBranch = "release/test",
        remote = "origin",
        state = TagOperationState.SUCCESS,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        tag = "v1.0.0",
    )
}
