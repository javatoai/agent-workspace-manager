package com.snowball.awm.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

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
            ?.contentOrNull
        if (!SchemaVersionCompatibility.isCompatible(version, CURRENT_APP_CONFIG_SCHEMA_VERSION)) {
            throw UnsupportedConfigVersionException(version)
        }
        return json.decodeFromString(content)
    }

    override fun save(config: AppConfig) {
        require(SchemaVersionCompatibility.isCompatible(config.schemaVersion, CURRENT_APP_CONFIG_SCHEMA_VERSION)) {
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
        // Writing always stamps the current PATCH version. This is safe because
        // PATCH releases are only compatible when persisted fields are unchanged.
        Files.writeString(temporary, json.encodeToString(config.copy(schemaVersion = CURRENT_APP_CONFIG_SCHEMA_VERSION)))
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
