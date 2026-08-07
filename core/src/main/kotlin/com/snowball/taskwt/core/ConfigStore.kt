package com.snowball.taskwt.core

import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

class ConfigStore(
    private val paths: ApplicationPaths = ApplicationPaths.systemDefault(),
    private val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
    },
) {
    fun exists(): Boolean = paths.config.exists()

    fun load(): AppConfig {
        if (!exists()) return AppConfig()
        val content = Files.readString(paths.config)
        return json.decodeFromString(content)
    }

    fun save(config: AppConfig) {
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
