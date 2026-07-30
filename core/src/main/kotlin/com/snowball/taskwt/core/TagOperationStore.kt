package com.snowball.taskwt.core

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
        val temporary = directory.resolve("${operation.operationId}.json.tmp")
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
        json.decodeFromString(
            Files.readString(taskDirectory.resolve("tag-operations").resolve("$operationId.json")),
        )

    fun list(taskDirectory: Path): List<TagOperation> {
        val directory = taskDirectory.resolve("tag-operations")
        if (!directory.exists()) return emptyList()
        return Files.list(directory).use { files ->
            files
                .filter { it.fileName.toString().endsWith(".json") }
                .map { json.decodeFromString<TagOperation>(Files.readString(it)) }
                .sorted(compareByDescending { it.updatedAt })
                .toList()
        }
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
}
