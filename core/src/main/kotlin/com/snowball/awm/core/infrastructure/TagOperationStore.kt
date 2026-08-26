package com.snowball.awm.core

import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

class TagOperationStore(
    private val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {
    fun save(taskDirectory: Path, operation: TagOperation) {
        val directory = taskDirectory.resolve("tag-operations")
        directory.createDirectories()
        val target = directory.resolve("${operation.operationId}.json")
        val temporary = Files.createTempFile(directory, ".${operation.operationId}-", ".json.tmp")
        Files.writeString(temporary, json.encodeToString(operation))
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

    fun load(taskDirectory: Path, operationId: String): TagOperation =
        json.decodeFromString<TagOperation>(
            Files.readString(taskDirectory.resolve("tag-operations").resolve("$operationId.json")),
        ).normalizeLegacyState()

    fun list(taskDirectory: Path): List<TagOperation> {
        val directory = taskDirectory.resolve("tag-operations")
        if (!directory.exists()) return emptyList()
        return Files.list(directory).use { files ->
            files
                .filter { it.fileName.toString().endsWith(".json") }
                .map { json.decodeFromString<TagOperation>(Files.readString(it)).normalizeLegacyState() }
                .sorted(compareByDescending { it.updatedAt })
                .toList()
        }
    }

    /** Deletes only persisted Tag-operation records and the legacy summary for one task. */
    fun clear(taskDirectory: Path): Int {
        var deleted = 0
        val directory = taskDirectory.resolve("tag-operations")
        if (directory.exists()) {
            Files.list(directory).use { files ->
                files.filter { it.fileName.toString().endsWith(".json") }.forEach { record ->
                    if (Files.deleteIfExists(record)) deleted++
                }
            }
        }
        if (Files.deleteIfExists(taskDirectory.resolve("tag-build-history.jsonl"))) deleted++
        return deleted
    }

    /**
     * Deletes the selected operation records for one task.
     *
     * Operation IDs are compared with the file name rather than interpolated into a
     * path. This keeps the operation selection bounded to this task's direct
     * JSON files directly under the task's `tag-operations` directory, even if a
     * caller supplies an unexpected ID.
     * The legacy JSONL file is rewritten atomically and only valid entries whose
     * operation ID is selected are removed; malformed or unrelated lines are kept.
     */
    fun deleteSelected(taskDirectory: Path, operationIds: Collection<String>): Int {
        val selected = operationIds.filter(String::isNotBlank).toSet()
        if (selected.isEmpty()) return 0

        var deleted = 0
        val directory = taskDirectory.resolve("tag-operations")
        if (directory.exists()) {
            Files.list(directory).use { files ->
                files
                    .filter { it.fileName.toString().endsWith(".json") }
                    .forEach { record ->
                        val fileOperationId = record.fileName.toString().removeSuffix(".json")
                        if (fileOperationId in selected && Files.deleteIfExists(record)) {
                            deleted++
                        }
                    }
            }
        }

        rewriteLegacyHistory(taskDirectory.resolve("tag-build-history.jsonl"), selected)
        return deleted
    }

    fun appendHistory(taskDirectory: Path, entry: TagBuildHistoryEntry) {
        taskDirectory.createDirectories()
        val bytes = (Json.encodeToString(entry) + System.lineSeparator())
            .toByteArray(StandardCharsets.UTF_8)
        FileChannel.open(
            taskDirectory.resolve("tag-build-history.jsonl"),
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE,
            java.nio.file.StandardOpenOption.APPEND,
        ).use { channel ->
            channel.lock().use {
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(false)
            }
        }
    }

    private fun rewriteLegacyHistory(historyFile: Path, selected: Set<String>) {
        if (!historyFile.exists()) return
        val original = Files.readString(historyFile, StandardCharsets.UTF_8)
        // Kotlin's default limit (0) retains the trailing empty element, so the
        // original final newline is preserved when lines are joined below.
        val retained = original.split('\n').filter { line ->
            val entry = runCatching {
                json.decodeFromString<TagBuildHistoryEntry>(line.trimEnd('\r'))
            }.getOrNull()
            entry == null || entry.operationId !in selected
        }
        val rewritten = retained.joinToString("\n")
        if (rewritten == original) return

        val parent = historyFile.parent ?: return
        val temporary = Files.createTempFile(parent, ".${historyFile.fileName}-", ".tmp")
        try {
            Files.writeString(temporary, rewritten, StandardCharsets.UTF_8)
            try {
                Files.move(
                    temporary,
                    historyFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, historyFile, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

@Suppress("DEPRECATION")
private fun TagOperation.normalizeLegacyState(): TagOperation = copy(
    state = when (state) {
        TagOperationState.FEATURE_PUSHED -> TagOperationState.SOURCE_BRANCH_PUSHED
        TagOperationState.TEST_BRANCH_PUSHED -> TagOperationState.TARGET_BRANCH_PUSHED
        else -> state
    },
)
