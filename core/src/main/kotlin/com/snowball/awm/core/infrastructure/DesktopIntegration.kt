package com.snowball.awm.core

import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

class DesktopIntegration(
    private val runner: CommandRunner = ProcessCommandRunner(),
) {
    fun copyPath(path: Path) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(path.toString()), null)
    }

    fun openUrl(url: String) {
        require(isHttpUrl(url)) { "不是有效的 http(s) 链接：$url" }
        val uri = URI(url.trim())
        val os = System.getProperty("os.name")
        when {
            Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) ->
                Desktop.getDesktop().browse(uri)
            os.startsWith("Windows", ignoreCase = true) ->
                runner.run(listOf("rundll32", "url.dll,FileProtocolHandler", uri.toString()))
            os.startsWith("Mac", ignoreCase = true) ->
                runner.run(listOf("open", uri.toString()))
            else ->
                runner.run(listOf("xdg-open", uri.toString()))
        }
    }

    fun reveal(path: Path) {
        val os = System.getProperty("os.name")
        when {
            os.startsWith("Windows", ignoreCase = true) ->
                runner.run(listOf("explorer.exe", "/select,${path.toAbsolutePath()}"))
            os.startsWith("Mac", ignoreCase = true) ->
                runner.run(listOf("open", "-R", path.toAbsolutePath().toString()))
            Desktop.isDesktopSupported() -> Desktop.getDesktop().open(path.toFile())
            else -> error("当前系统不支持打开文件管理器")
        }
    }

    fun openDirectory(path: Path) {
        require(path.toFile().isDirectory) { "目录不存在：$path" }
        val os = System.getProperty("os.name")
        when {
            Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN) ->
                Desktop.getDesktop().open(path.toFile())
            os.startsWith("Windows", ignoreCase = true) ->
                runner.run(listOf("explorer.exe", path.toAbsolutePath().toString()))
            os.startsWith("Mac", ignoreCase = true) ->
                runner.run(listOf("open", path.toAbsolutePath().toString()))
            else -> runner.run(listOf("xdg-open", path.toAbsolutePath().toString()))
        }
    }

    fun openTerminal(path: Path, configuredExecutable: String? = null) {
        val osName = System.getProperty("os.name")
        val command = TerminalLaunchCommand.build(
            configuredExecutable = configuredExecutable,
            target = path,
            osName = osName,
            windowsTerminalAvailable = configuredExecutable.isNullOrBlank() &&
                osName.startsWith("Windows", ignoreCase = true) && commandExists("wt.exe"),
        )
        launchDetached(command)
    }

    fun openDevelopmentTool(path: Path, type: DevelopmentToolType, configuredPath: String) {
        require(Files.isDirectory(path)) { "项目目录不存在：$path" }
        val application = Path.of(configuredPath).toAbsolutePath().normalize()
        require(Files.exists(application)) { "开发工具路径不存在：$application" }
        launchDetached(DevelopmentToolLaunchCommand.build(type, application.toString(), path))
    }

    private fun commandExists(command: String): Boolean {
        val result = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            runner.run(listOf("where.exe", command))
        } else {
            runner.run(listOf("which", command))
        }
        return result.succeeded
    }

    private fun launchDetached(command: List<String>) {
        ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }
}

/** Builds a terminal command without treating a macOS .app bundle as an executable file. */
object TerminalLaunchCommand {
    fun build(
        configuredExecutable: String?,
        target: Path,
        osName: String = System.getProperty("os.name"),
        windowsTerminalAvailable: Boolean = false,
    ): List<String> {
        val normalizedTarget = target.toAbsolutePath().normalize().toString()
        val configured = configuredExecutable?.trim()?.takeIf(String::isNotEmpty)
        return when {
            configured != null && osName.startsWith("Mac", ignoreCase = true) && configured.endsWith(".app", ignoreCase = true) ->
                listOf("open", "-a", configured, normalizedTarget)
            configured != null -> listOf(configured, normalizedTarget)
            osName.startsWith("Windows", ignoreCase = true) && windowsTerminalAvailable ->
                listOf("wt.exe", "-d", normalizedTarget)
            osName.startsWith("Windows", ignoreCase = true) ->
                listOf("powershell.exe", "-NoExit", "-Command", "Set-Location -LiteralPath '${
                    normalizedTarget.replace("'", "''")
                }'")
            osName.startsWith("Mac", ignoreCase = true) -> listOf("open", "-a", "Terminal", normalizedTarget)
            else -> listOf("x-terminal-emulator", "--working-directory", normalizedTarget)
        }
    }
}

object DevelopmentToolLaunchCommand {
    fun build(
        type: DevelopmentToolType,
        configuredPath: String,
        target: Path,
        osName: String = System.getProperty("os.name"),
    ): List<String> {
        require(configuredPath.isNotBlank()) { "开发工具路径不能为空" }
        val normalizedTarget = target.toAbsolutePath().normalize().toString()
        val normalizedPath = configuredPath.trim()
        if (osName.startsWith("Mac", ignoreCase = true) && normalizedPath.endsWith(".app", ignoreCase = true)) {
            if (type == DevelopmentToolType.VISUAL_STUDIO_CODE) {
                return listOf("open", "-n", "-a", normalizedPath, "--args", "--new-window", normalizedTarget)
            }
            return listOf("open", "-a", normalizedPath, normalizedTarget)
        }
        val arguments = buildList {
            if (type == DevelopmentToolType.VISUAL_STUDIO_CODE) add("--new-window")
            add(normalizedTarget)
        }
        return if (osName.startsWith("Windows", ignoreCase = true) &&
            (normalizedPath.endsWith(".cmd", true) || normalizedPath.endsWith(".bat", true))
        ) {
            listOf("cmd.exe", "/d", "/c", normalizedPath) + arguments
        } else if (osName.startsWith("Windows", ignoreCase = true) && type in JETBRAINS_TOOL_TYPES) {
            windowsShellLaunch(normalizedPath, arguments)
        } else {
            listOf(normalizedPath) + arguments
        }
    }

    /**
     * Some JetBrains launchers installed below Program Files require elevation. Launching them
     * directly with ProcessBuilder bypasses the Windows shell and fails with CreateProcess 740.
     */
    private fun windowsShellLaunch(application: String, arguments: List<String>): List<String> =
        listOf(
            "powershell.exe",
            "-NoProfile",
            "-Command",
            "Start-Process -FilePath ${powerShellLiteral(application)} -ArgumentList @(${arguments.joinToString(",") { powerShellLiteral(it) }})",
        )

    private fun powerShellLiteral(value: String): String = "'${value.replace("'", "''")}'"

    private val JETBRAINS_TOOL_TYPES = setOf(
        DevelopmentToolType.INTELLIJ_IDEA,
        DevelopmentToolType.WEBSTORM,
        DevelopmentToolType.PYCHARM,
        DevelopmentToolType.ANDROID_STUDIO,
        DevelopmentToolType.DEVECO_STUDIO,
    )
}
