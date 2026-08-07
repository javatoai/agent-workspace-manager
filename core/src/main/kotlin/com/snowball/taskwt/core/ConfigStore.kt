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

class UnsupportedConfigVersionException(
    val actualVersion: Int?,
) : IllegalStateException(
    "配置版本不受支持：${actualVersion ?: "缺少 schemaVersion"}；" +
        "当前版本为 $CURRENT_APP_CONFIG_SCHEMA_VERSION。请参照 docs/LEGACY-DATA-MIGRATION.md 手工迁移。",
)

/** Strict version-3 configuration repository backed by an atomic JSON file. */
interface ConfigurationRepository {
    fun load(): AppConfig
    fun save(config: AppConfig)
}

class ConfigStore(
    private val paths: ApplicationPaths = ApplicationPaths.systemDefault(),
    private val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
    },
) : ConfigurationRepository {
    fun exists(): Boolean = paths.config.exists()

    override fun load(): AppConfig {
        if (!exists()) return AppConfig()
        val content = Files.readString(paths.config)
        val version = json.parseToJsonElement(content)
            .jsonObject["schemaVersion"]
            ?.jsonPrimitive
            ?.intOrNull
        if (version != CURRENT_APP_CONFIG_SCHEMA_VERSION) {
            throw UnsupportedConfigVersionException(version)
        }
        return json.decodeFromString(content)
    }

    override fun save(config: AppConfig) {
        require(config.schemaVersion == CURRENT_APP_CONFIG_SCHEMA_VERSION) {
            "不能写入配置版本 ${config.schemaVersion}"
        }
        if (exists()) {
            // A matching version number is not sufficient: unknown fields or an
            // invalid value may have made desktop startup fall back to an empty UI.
            // Fully validating the existing document prevents that fallback from
            // overwriting bytes the application could not understand.
            load()
        }
        paths.home.createDirectories()
        val temporary = Files.createTempFile(paths.home, ".config-", ".json.tmp")
        Files.writeString(temporary, json.encodeToString(config))
        moveAtomically(temporary, paths.config)
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
}
