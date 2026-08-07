package com.snowball.taskwt.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

class ManifestStore(
    private val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
    },
) {
    fun save(taskDirectory: Path, manifest: TaskManifest) {
        require(manifest.schemaVersion == CURRENT_TASK_MANIFEST_SCHEMA_VERSION) {
            "不能写入任务 JSON 版本 ${manifest.schemaVersion}，当前版本为 $CURRENT_TASK_MANIFEST_SCHEMA_VERSION"
        }
        taskDirectory.createDirectories()
        val target = taskDirectory.resolve(FILE_NAME)
        val temporary = Files.createTempFile(taskDirectory, ".$FILE_NAME-", ".tmp")
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

    fun load(taskDirectory: Path): TaskManifest {
        val content = Files.readString(taskDirectory.resolve(FILE_NAME))
        val version = json.parseToJsonElement(content)
            .jsonObject["schemaVersion"]
            ?.jsonPrimitive
            ?.intOrNull
        require(version == CURRENT_TASK_MANIFEST_SCHEMA_VERSION) {
            "任务 JSON 版本不受支持：${version ?: "缺少 schemaVersion"}，当前版本为 " +
                CURRENT_TASK_MANIFEST_SCHEMA_VERSION
        }
        return json.decodeFromString(content)
    }

    fun list(taskRoot: Path): List<Pair<Path, TaskManifest>> {
        if (!taskRoot.exists()) return emptyList()
        return Files.list(taskRoot).use { children ->
            children
                .filter { it.resolve(FILE_NAME).exists() }
                .toList()
                .map { directory -> directory to load(directory) }
        }
    }

    companion object {
        const val FILE_NAME = "taskwt.json"
    }
}
