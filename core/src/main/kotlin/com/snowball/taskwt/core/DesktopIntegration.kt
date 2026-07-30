package com.snowball.taskwt.core

import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Path

class DesktopIntegration(
    private val runner: CommandRunner = ProcessCommandRunner(),
) {
    fun copyPath(path: Path) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(path.toString()), null)
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

    fun openTerminal(path: Path, configuredExecutable: String? = null) {
        val os = System.getProperty("os.name")
        val command = when {
            configuredExecutable != null ->
                listOf(configuredExecutable, path.toAbsolutePath().toString())
            os.startsWith("Windows", ignoreCase = true) && commandExists("wt.exe") ->
                listOf("wt.exe", "-d", path.toAbsolutePath().toString())
            os.startsWith("Windows", ignoreCase = true) ->
                listOf("powershell.exe", "-NoExit", "-Command", "Set-Location -LiteralPath '${
                    path.toAbsolutePath().toString().replace("'", "''")
                }'")
            os.startsWith("Mac", ignoreCase = true) ->
                listOf("open", "-a", "Terminal", path.toAbsolutePath().toString())
            else -> listOf("x-terminal-emulator", "--working-directory", path.toAbsolutePath().toString())
        }
        launchDetached(command)
    }

    fun openIde(path: Path, executable: String) {
        launchDetached(listOf(executable, path.toAbsolutePath().toString()))
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
