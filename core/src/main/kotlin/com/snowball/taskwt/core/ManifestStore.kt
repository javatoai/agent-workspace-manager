package com.snowball.taskwt.core

import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

class ManifestStore(
    private val json: Json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {
    fun save(taskDirectory: Path, manifest: TaskManifest) {
        taskDirectory.createDirectories()
        val target = taskDirectory.resolve(FILE_NAME)
        val temporary = taskDirectory.resolve("$FILE_NAME.tmp")
        Files.writeString(temporary, json.encodeToString(manifest))
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

    fun load(taskDirectory: Path): TaskManifest =
        json.decodeFromString(Files.readString(taskDirectory.resolve(FILE_NAME)))

    fun list(taskRoot: Path): List<Pair<Path, TaskManifest>> {
        if (!taskRoot.exists()) return emptyList()
        return Files.list(taskRoot).use { children ->
            children
                .filter { it.resolve(FILE_NAME).exists() }
                .toList()
                .mapNotNull { directory ->
                    runCatching { directory to load(directory) }.getOrNull()
                }
        }
    }

    companion object {
        const val FILE_NAME = "taskwt.json"
    }
}
