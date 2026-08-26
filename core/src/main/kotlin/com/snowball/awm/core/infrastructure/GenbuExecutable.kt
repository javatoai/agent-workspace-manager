package com.snowball.awm.core

import java.time.Duration
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

enum class GenbuCommandSource { PROBED, CONFIGURED_FALLBACK, PATH_FALLBACK }

/** Resolves the locally installed Genbu CLI without any user configuration. */
fun interface GenbuExecutable {
    fun resolve(): String

    fun current(): String = resolve()

    fun probe(): String = resolve()

    fun source(): GenbuCommandSource = GenbuCommandSource.PATH_FALLBACK
}

fun genbuFallbackCommand(isWindows: Boolean): String = if (isWindows) "genbu.exe" else "genbu"

fun genbuProbeCommand(osName: String): List<String> = when {
    osName.lowercase(Locale.ROOT).contains("win") -> listOf("where.exe", "genbu.exe")
    osName.lowercase(Locale.ROOT).contains("mac") -> listOf("/bin/zsh", "-lc", "command -v genbu")
    else -> listOf("/bin/bash", "-lc", "command -v genbu")
}

fun genbuProbeCommandDisplay(osName: String): String = genbuProbeCommand(osName).joinToString(" ")

fun parseGenbuProbeOutput(output: String, osName: String): String? {
    val isWindows = osName.lowercase(Locale.ROOT).contains("win")
    val windowsAbsolute = Regex("""^[A-Za-z]:[\\/].+""")
    return output.lineSequence().map(String::trim).firstOrNull { value ->
        if (isWindows) windowsAbsolute.matches(value) else value.startsWith("/")
    }
}

/** Normalizes a user-entered Genbu executable path; null means no configured fallback. */
fun normalizeGenbuExecutablePath(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val path = Path.of(trimmed)
    require(path.isAbsolute) { "Genbu 命令路径必须是绝对路径" }
    require(Files.isRegularFile(path)) { "Genbu 命令路径不是文件：$trimmed" }
    require(Files.isExecutable(path)) { "Genbu 命令不可执行：$trimmed" }
    return path.toString()
}

/** Resolves automatic detection first, then an optional user-configured fallback. */
class ConfiguredGenbuExecutable(
    private val configuredPath: () -> String? = { null },
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val osName: String = System.getProperty("os.name"),
    private val probeTimeout: Duration = Duration.ofSeconds(5),
) : GenbuExecutable {
    @Volatile private var probedPath: String? = null
    @Volatile private var probeAttempted = false

    override fun resolve(): String = synchronized(this) {
        if (!probeAttempted) probeLocked()
        probedPath ?: configured() ?: genbuFallbackCommand(isWindows())
    }

    override fun current(): String = probedPath ?: configured() ?: genbuFallbackCommand(isWindows())

    override fun probe(): String = synchronized(this) {
        probeAttempted = false
        probeLocked()
        probedPath ?: configured() ?: genbuFallbackCommand(isWindows())
    }

    override fun source(): GenbuCommandSource = when {
        probedPath != null -> GenbuCommandSource.PROBED
        configured() != null -> GenbuCommandSource.CONFIGURED_FALLBACK
        else -> GenbuCommandSource.PATH_FALLBACK
    }

    private fun probeLocked() {
        probedPath = runCatching {
            val result = runner.run(genbuProbeCommand(osName), timeout = probeTimeout)
            result.takeIf(CommandResult::succeeded)?.stdout?.let { parseGenbuProbeOutput(it, osName) }
        }.getOrNull()
        probeAttempted = true
    }

    private fun isWindows(): Boolean = osName.lowercase(Locale.ROOT).contains("win")

    private fun configured(): String? = configuredPath()?.trim()?.takeIf(String::isNotEmpty)
}
