package com.snowball.awm.core

import java.time.Duration
import java.util.Locale

enum class MeegleCommandSource { CONFIGURED, PROBED, PATH_FALLBACK }

/** Resolves the Meegle CLI command used for every invocation. */
fun interface MeegleExecutable {
    /** The command to invoke; may trigger a first-time probe. */
    fun resolve(): String

    /** The current command without triggering a probe; safe on the UI thread. */
    fun current(): String = resolve()

    /** Re-detects the command; static implementations never change. */
    fun probe(): String = resolve()

    fun source(): MeegleCommandSource = MeegleCommandSource.PATH_FALLBACK

    companion object {
        /** The historical behavior: rely on the process PATH with a bare command. */
        fun pathFallback(isWindows: Boolean = defaultIsWindows()): MeegleExecutable =
            MeegleExecutable { meegleFallbackCommand(isWindows) }
    }
}

fun meegleFallbackCommand(isWindows: Boolean): String = if (isWindows) "meegle.cmd" else "meegle"

/**
 * The detection command per platform. GUI processes inherit a minimal PATH on
 * macOS/Linux, so detection runs inside a login shell that sources the user's
 * profile (Homebrew puts `/opt/homebrew/bin` on PATH via ~/.zprofile).
 */
fun meegleProbeCommand(osName: String): List<String> {
    val os = osName.lowercase(Locale.ROOT)
    return when {
        os.contains("win") -> listOf("where.exe", "meegle")
        os.contains("mac") -> listOf("/bin/zsh", "-lc", "command -v meegle")
        else -> listOf("/bin/bash", "-lc", "command -v meegle")
    }
}

/** First output line that is an absolute path; anything else means "not found". */
fun parseMeegleProbeOutput(output: String, osName: String): String? {
    val isWindows = osName.lowercase(Locale.ROOT).contains("win")
    val windowsAbsolute = Regex("""^[A-Za-z]:[\\/].+""")
    return output.lineSequence()
        .map { it.trim() }
        .firstOrNull { line ->
            if (isWindows) windowsAbsolute.matches(line) else line.startsWith("/")
        }
}

private fun defaultIsWindows(): Boolean =
    System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")

/** Normalizes a user-entered executable path; null means "auto-detect". */
fun normalizeMeegleExecutablePath(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val path = java.nio.file.Path.of(trimmed)
    require(path.isAbsolute) { "Meegle 命令路径必须是绝对路径" }
    require(java.nio.file.Files.exists(path)) { "Meegle 命令路径不存在：$trimmed" }
    require(java.nio.file.Files.isExecutable(path)) { "Meegle 命令不可执行：$trimmed" }
    return trimmed
}

/**
 * Three-level resolution: explicit configuration, then a cached login-shell
 * probe, then the bare-command PATH fallback that preserves legacy errors.
 */
class ConfiguredMeegleExecutable(
    private val configuredPath: () -> String?,
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val osName: String = System.getProperty("os.name"),
    private val probeTimeout: Duration = Duration.ofSeconds(5),
) : MeegleExecutable {
    @Volatile private var probedPath: String? = null
    @Volatile private var probeAttempted = false

    override fun resolve(): String {
        configured()?.let { return it }
        if (!probeAttempted) probe()
        return probedPath ?: meegleFallbackCommand(isWindows())
    }

    override fun current(): String =
        configured() ?: probedPath ?: meegleFallbackCommand(isWindows())

    @Synchronized
    override fun probe(): String {
        configured()?.let {
            probedPath = null
            probeAttempted = false
            return it
        }
        val found = runCatching {
            val result = runner.run(meegleProbeCommand(osName), timeout = probeTimeout)
            if (result.succeeded) parseMeegleProbeOutput(result.stdout, osName) else null
        }.getOrNull()
        probedPath = found
        probeAttempted = true
        return found ?: meegleFallbackCommand(isWindows())
    }

    override fun source(): MeegleCommandSource = when {
        configured() != null -> MeegleCommandSource.CONFIGURED
        probedPath != null -> MeegleCommandSource.PROBED
        else -> MeegleCommandSource.PATH_FALLBACK
    }

    private fun configured(): String? = configuredPath()?.trim()?.takeIf(String::isNotEmpty)

    private fun isWindows(): Boolean = osName.lowercase(Locale.ROOT).contains("win")
}
