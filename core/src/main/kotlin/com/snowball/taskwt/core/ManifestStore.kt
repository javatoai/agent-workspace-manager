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

data class ManifestScanResult(
    val current: List<Pair<Path, TaskManifest>>,
    val ignoredLegacyDirectories: List<Path>,
    val failures: Map<Path, String> = emptyMap(),
)

interface TaskManifestRepository {
    fun save(taskDirectory: Path, manifest: TaskManifest)
    fun load(taskDirectory: Path): TaskManifest
    fun scan(taskRoot: Path): ManifestScanResult
}

class ManifestStore(
    private val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
    },
) : TaskManifestRepository {
    override fun save(taskDirectory: Path, manifest: TaskManifest) {
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

    override fun load(taskDirectory: Path): TaskManifest {
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

    fun list(taskRoot: Path): List<Pair<Path, TaskManifest>> = scan(taskRoot).current

    /** Legacy task files remain byte-for-byte untouched and are reported rather than imported. */
    override fun scan(taskRoot: Path): ManifestScanResult {
        if (!taskRoot.exists()) return ManifestScanResult(emptyList(), emptyList())
        return Files.list(taskRoot).use { children ->
            val directories = children
                .filter { it.resolve(FILE_NAME).exists() }
                .toList()
            val current = mutableListOf<Pair<Path, TaskManifest>>()
            val legacy = mutableListOf<Path>()
            val failures = linkedMapOf<Path, String>()
            directories.forEach { directory ->
                runCatching {
                    val content = Files.readString(directory.resolve(FILE_NAME))
                    val version = json.parseToJsonElement(content)
                        .jsonObject["schemaVersion"]
                        ?.jsonPrimitive
                        ?.intOrNull
                    if (version == CURRENT_TASK_MANIFEST_SCHEMA_VERSION) {
                        current.add(directory to json.decodeFromString(content))
                    } else {
                        legacy.add(directory)
                    }
                }.onFailure { error ->
                    failures[directory] = error.message ?: error::class.simpleName ?: "读取失败"
                }
            }
            ManifestScanResult(current, legacy, failures)
        }
    }

    companion object {
        const val FILE_NAME = "taskwt.json"
    }
}
