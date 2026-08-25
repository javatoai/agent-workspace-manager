package com.snowball.awm.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.concurrent.withLock

class UnsupportedConfigVersionException(
    val actualVersion: String?,
) : IllegalStateException(
    "配置版本不受支持：${actualVersion ?: "缺少 schemaVersion"}；" +
        "当前版本为 $CURRENT_APP_CONFIG_SCHEMA_VERSION。AWM 不读取或改写旧产品数据。",
)

/** Strict release-versioned configuration repository backed by an atomic JSON file. */
interface ConfigurationRepository {
    fun load(): AppConfig
    fun save(config: AppConfig)

    fun update(transform: (AppConfig) -> AppConfig): AppConfig {
        val updated = transform(load())
        save(updated)
        return load()
    }
}

class ConfigStore(
    private val paths: ApplicationPaths = ApplicationPaths.systemDefault(),
    private val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
    },
) : ConfigurationRepository {
    private val processMutationLock = mutationLocks.computeIfAbsent(
        paths.config.toAbsolutePath().normalize().toString().lowercase(Locale.ROOT),
    ) { ReentrantLock() }

    data class Backup(val path: Path, val modifiedAtMillis: Long)
    data class FileSnapshot(
        val path: Path,
        val exists: Boolean,
        val content: String? = null,
        val readError: String? = null,
    )
    data class ImportPreview(
        val source: Path,
        val changes: List<String>,
        val invalidDevelopmentTools: List<DevelopmentToolType>,
    )

    fun exists(): Boolean = paths.config.exists()

    /**
     * Reads the on-disk configuration verbatim for a read-only desktop preview.
     * It deliberately does not parse or validate the document, so users can
     * inspect a malformed or incompatible file without risking an overwrite.
     */
    fun fileSnapshot(): FileSnapshot {
        val path = paths.config.toAbsolutePath().normalize()
        if (!Files.exists(path)) return FileSnapshot(path = path, exists = false)
        return runCatching { FileSnapshot(path = path, exists = true, content = Files.readString(path)) }
            .getOrElse { error ->
                FileSnapshot(
                    path = path,
                    exists = true,
                    readError = error.message ?: error::class.simpleName ?: "无法读取配置文件",
                )
            }
    }

    override fun load(): AppConfig {
        if (!exists()) return AppConfig()
        val content = Files.readString(paths.config)
        val version = json.parseToJsonElement(content)
            .jsonObject["schemaVersion"]
            ?.jsonPrimitive
            ?.contentOrNull
        if (!SchemaVersionCompatibility.isCompatible(version, CURRENT_APP_CONFIG_SCHEMA_VERSION)) {
            throw UnsupportedConfigVersionException(version)
        }
        return json.decodeFromJsonElement<AppConfig>(json.parseToJsonElement(content))
    }

    override fun save(config: AppConfig) = withMutationLock {
        saveUnlocked(config)
    }

    override fun update(transform: (AppConfig) -> AppConfig): AppConfig = withMutationLock {
        val updated = transform(load())
        saveUnlocked(updated)
        load()
    }

    private fun saveUnlocked(config: AppConfig) {
        require(SchemaVersionCompatibility.isCompatible(config.schemaVersion, CURRENT_APP_CONFIG_SCHEMA_VERSION)) {
            "不能写入配置版本 ${config.schemaVersion}"
        }
        if (exists()) {
            // A matching version number is not sufficient: unknown fields or an
            // invalid value may have made desktop startup fall back to an empty UI.
            // Fully validating the existing document prevents that fallback from
            // overwriting bytes the application could not understand.
            load()
            createBackup()
        }
        paths.home.createDirectories()
        val temporary = Files.createTempFile(paths.home, ".config-", ".json.tmp")
        // Writing always stamps the current PATCH version. This is safe because
        // PATCH releases are only compatible when persisted fields are unchanged.
        Files.writeString(temporary, json.encodeToString(config.copy(schemaVersion = CURRENT_APP_CONFIG_SCHEMA_VERSION)))
        moveAtomically(temporary, paths.config)
    }

    fun backups(): List<Backup> {
        if (!paths.backups.exists()) return emptyList()
        return Files.list(paths.backups).use { stream ->
            stream.filter { it.name.startsWith("config-") && it.name.endsWith(".json") }
                .map { Backup(it, Files.getLastModifiedTime(it).toMillis()) }
                .sorted(Comparator.comparingLong<Backup> { it.modifiedAtMillis }.reversed())
                .toList()
        }
    }

    fun restore(backup: Path): AppConfig {
        val normalized = backup.toAbsolutePath().normalize()
        require(normalized.parent == paths.backups.toAbsolutePath().normalize()) { "只能恢复 AWM 配置备份目录中的文件" }
        require(Files.isRegularFile(normalized)) { "配置备份不存在：$normalized" }
        return withMutationLock {
            val imported = decodeAndValidate(Files.readString(normalized))
            saveUnlocked(imported)
            load()
        }
    }

    fun exportTo(target: Path): Path {
        require(exists()) { "当前没有可导出的配置文件" }
        load()
        val normalized = target.toAbsolutePath().normalize()
        normalized.parent?.createDirectories()
        Files.copy(paths.config, normalized, StandardCopyOption.REPLACE_EXISTING)
        return normalized
    }

    fun importFrom(source: Path): AppConfig {
        require(Files.isRegularFile(source)) { "导入配置不存在：$source" }
        return withMutationLock {
            val imported = decodeAndValidate(Files.readString(source))
            saveUnlocked(imported)
            load()
        }
    }

    fun previewImport(source: Path): ImportPreview {
        require(Files.isRegularFile(source)) { "导入配置不存在：$source" }
        val imported = decodeAndValidate(Files.readString(source))
        val current = load()
        val changes = buildList {
            if (current.taskRoot != imported.taskRoot) add("任务路径：${current.taskRoot.orEmpty()} → ${imported.taskRoot.orEmpty()}")
            if (current.requirementMaterialsRoot != imported.requirementMaterialsRoot) {
                add("需求资料根路径：${current.requirementMaterialsRoot.orEmpty()} → ${imported.requirementMaterialsRoot.orEmpty()}")
            }
            if (current.requirementMaterialsSubdirectory != imported.requirementMaterialsSubdirectory) {
                add("需求资料子目录：${current.requirementMaterialsSubdirectory.orEmpty()} → ${imported.requirementMaterialsSubdirectory.orEmpty()}")
            }
            if (current.groups.size != imported.groups.size) add("任务组：${current.groups.size} → ${imported.groups.size}")
            if (current.repositories.size != imported.repositories.size) add("仓库：${current.repositories.size} → ${imported.repositories.size}")
            if (current.developmentTools != imported.developmentTools) add("开发工具配置将更新")
            if (current.meegleProjects != imported.meegleProjects) add("飞书项目：${current.meegleProjects.size} → ${imported.meegleProjects.size}")
            if (isEmpty() && current != imported) add("配置内容存在其他变化")
        }
        val invalidTools = imported.developmentTools.filterNot { runCatching { Files.exists(Path.of(it.path)) }.getOrDefault(false) }
            .map(DevelopmentToolConfig::type)
        return ImportPreview(source.toAbsolutePath().normalize(), changes, invalidTools)
    }

    private fun createBackup() {
        paths.backups.createDirectories()
        val target = paths.backups.resolve("config-${System.currentTimeMillis()}.json")
        Files.copy(paths.config, target, StandardCopyOption.REPLACE_EXISTING)
        backups().drop(MAX_BACKUPS).forEach { Files.deleteIfExists(it.path) }
    }

    private fun decodeAndValidate(content: String): AppConfig {
        rejectRemovedSourceRepositoryStrategy(content, "配置文件提示：不再支持原仓库分支，请手工修改配置")
        val element = json.parseToJsonElement(content)
        val version = element.jsonObject["schemaVersion"]?.jsonPrimitive?.contentOrNull
        if (!SchemaVersionCompatibility.isCompatible(version, CURRENT_APP_CONFIG_SCHEMA_VERSION)) {
            throw UnsupportedConfigVersionException(version)
        }
        return json.decodeFromJsonElement<AppConfig>(element)
    }

    private fun moveAtomically(source: Path, target: Path) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun <T> withMutationLock(block: () -> T): T = processMutationLock.withLock {
        FileLocking.withExclusiveLock(
            paths.locks.resolve(CONFIG_LOCK_FILE),
            "配置正在被另一个 AWM 实例修改，请稍后重试",
            block,
        )
    }

    private companion object {
        const val MAX_BACKUPS = 10
        const val CONFIG_LOCK_FILE = "config.lock"
        val mutationLocks = ConcurrentHashMap<String, ReentrantLock>()
    }
}

internal fun rejectRemovedSourceRepositoryStrategy(content: String, message: String) {
    if (Regex("\\\"strategy\\\"\\s*:\\s*\\\"SOURCE_REPOSITORY\\\"").containsMatchIn(content)) {
        throw IllegalStateException(message)
    }
}
