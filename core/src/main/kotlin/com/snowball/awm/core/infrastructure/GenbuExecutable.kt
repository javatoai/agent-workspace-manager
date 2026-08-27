package com.snowball.awm.core

import java.nio.file.Files
import java.nio.file.FileSystems
import java.nio.file.Path
import java.time.Duration
import java.util.Locale

enum class GenbuCommandSource { CONFIGURED, PROBED, PATH_FALLBACK }

fun interface GenbuExecutable {
    fun resolve(): String
    fun current(): String = resolve()
    fun probe(): String = resolve()
    fun source(): GenbuCommandSource = GenbuCommandSource.PATH_FALLBACK

    companion object {
        fun pathFallback(isWindows: Boolean = defaultGenbuIsWindows()): GenbuExecutable =
            GenbuExecutable { if (isWindows) "genbu.exe" else "genbu" }
    }
}

fun normalizeGenbuExecutablePath(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val path = Path.of(trimmed)
    require(path.isAbsolute) { "Genbu 命令路径必须是绝对路径" }
    require(Files.isRegularFile(path)) { "Genbu 命令路径不存在或不是文件：$trimmed" }
    require(Files.isExecutable(path)) { "Genbu 命令不可执行：$trimmed" }
    return path.toAbsolutePath().normalize().toString()
}

class ConfiguredGenbuExecutable(
    private val configuredPath: () -> String?,
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val osName: String = System.getProperty("os.name"),
    private val probeTimeout: Duration = Duration.ofSeconds(5),
    private val bundledDirectories: () -> List<Path> = ::defaultGenbuBundledDirectories,
) : GenbuExecutable {
    @Volatile private var probedPath: String? = null
    @Volatile private var probeAttempted = false

    override fun resolve(): String = synchronized(this) {
        configured()?.let { return it }
        if (!probeAttempted) probeLocked()
        return probedPath ?: fallback()
    }

    override fun current(): String = configured() ?: probedPath ?: fallback()

    override fun probe(): String = synchronized(this) {
        configured()?.let {
            probedPath = null
            probeAttempted = false
            return it
        }
        probeAttempted = false
        probeLocked()
        return probedPath ?: fallback()
    }

    override fun source(): GenbuCommandSource = when {
        configured() != null -> GenbuCommandSource.CONFIGURED
        probedPath != null -> GenbuCommandSource.PROBED
        else -> GenbuCommandSource.PATH_FALLBACK
    }

    private fun probeLocked() {
        val executableName = if (isWindows()) "genbu.exe" else "genbu"
        val bundled = bundledDirectories()
            .asSequence()
            .map { it.resolve(executableName).toAbsolutePath().normalize() }
            .firstOrNull(Files::isRegularFile)
            ?.toString()
        val fromPath = if (bundled == null) runCatching {
            val command = if (isWindows()) listOf("where.exe", executableName) else listOf("/bin/bash", "-lc", "command -v genbu")
            val result = runner.run(command, timeout = probeTimeout)
            result.takeIf(CommandResult::succeeded)?.stdout?.lineSequence()?.map(String::trim)?.firstOrNull { line ->
                runCatching { Path.of(line).isAbsolute && Files.isRegularFile(Path.of(line)) }.getOrDefault(false)
            }
        }.getOrNull() else null
        probedPath = bundled ?: fromPath
        probeAttempted = true
    }

    private fun configured(): String? = configuredPath()?.trim()?.takeIf(String::isNotEmpty)?.let { raw ->
        runCatching { normalizeGenbuExecutablePath(raw) }.getOrNull()
    }
    private fun fallback(): String = if (isWindows()) "genbu.exe" else "genbu"
    private fun isWindows(): Boolean = osName.lowercase(Locale.ROOT).contains("win")
}

private fun defaultGenbuIsWindows(): Boolean =
    System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")

private fun defaultGenbuBundledDirectories(): List<Path> = buildList {
    System.getProperty("jpackage.app-path")?.let { value -> runCatching { Path.of(value).parent }.getOrNull()?.let(::add) }
    System.getProperty("user.dir")?.let { value -> runCatching { Path.of(value) }.getOrNull()?.let(::add) }
    System.getProperty("user.home")?.let { value -> runCatching { Path.of(value).resolve("Downloads") }.getOrNull()?.let(::add) }
    // Windows engineering machines often keep portable CLIs on another drive,
    // for example T:\Downloads\genbu.exe. Checking one conventional location
    // per root is bounded and avoids a recursive disk scan.
    FileSystems.getDefault().rootDirectories.forEach { root -> add(root.resolve("Downloads")) }
}.distinct()
