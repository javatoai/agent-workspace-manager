package com.snowball.taskwt.desktop

import com.snowball.taskwt.core.TaskWorkspaceContext
import com.snowball.taskwt.core.TaskWorkspaceToolAvailability
import com.snowball.taskwt.core.TaskWorkspaceToolDescriptor
import com.snowball.taskwt.core.TaskWorkspaceToolLauncher
import java.nio.file.Files
import java.nio.file.Path

fun interface CursorCommandLocator {
    fun locate(): List<String>?
}

fun interface DetachedProcessLauncher {
    fun launch(command: List<String>)
}

class SystemDetachedProcessLauncher : DetachedProcessLauncher {
    override fun launch(command: List<String>) {
        require(command.isNotEmpty()) { "启动命令不能为空" }
        ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }
}

/**
 * Locates Cursor without persisting an executable path in TaskWT configuration.
 * Windows registry lookup is preferred because PATH commonly points at cursor.cmd.
 */
class SystemCursorCommandLocator(
    private val osName: String = System.getProperty("os.name"),
    private val pathValue: String = System.getenv("PATH").orEmpty(),
) : CursorCommandLocator {
    override fun locate(): List<String>? {
        if (osName.startsWith("Windows", ignoreCase = true)) {
            locateWindowsRegistryExecutable()?.let { return listOf(it) }
        }
        val candidates = if (osName.startsWith("Windows", ignoreCase = true)) {
            listOf("cursor.exe", "cursor.cmd", "cursor.bat", "cursor")
        } else {
            listOf("cursor")
        }
        pathValue.split(System.getProperty("path.separator"))
            .filter(String::isNotBlank)
            .forEach { directory ->
                candidates.forEach { name ->
                    val candidate = runCatching { Path.of(directory).resolve(name) }.getOrNull()
                    if (candidate != null && Files.isRegularFile(candidate)) {
                        return if (name.endsWith(".cmd", true) || name.endsWith(".bat", true)) {
                            listOf("cmd.exe", "/c", candidate.toString())
                        } else {
                            listOf(candidate.toString())
                        }
                    }
                }
            }
        return null
    }

    private fun locateWindowsRegistryExecutable(): String? {
        val keys = listOf(
            "HKCU\\Software\\Classes\\cursor\\shell\\open\\command",
            "HKCR\\cursor\\shell\\open\\command",
        )
        keys.forEach { key ->
            val output = runCatching {
                ProcessBuilder("reg.exe", "query", key, "/ve")
                    .redirectErrorStream(true)
                    .start()
                    .inputStream.bufferedReader().use { it.readText() }
            }.getOrNull().orEmpty()
            val executable = Regex("\\\"([^\\\"]+\\.exe)\\\"", RegexOption.IGNORE_CASE)
                .find(output)
                ?.groupValues
                ?.get(1)
            if (!executable.isNullOrBlank() && Files.isRegularFile(Path.of(executable))) return executable
        }
        return null
    }
}

/** Cursor adapter opens the task directory as a project and never sends a prompt. */
class CursorWorkspaceToolLauncher(
    locator: CursorCommandLocator = SystemCursorCommandLocator(),
    private val processLauncher: DetachedProcessLauncher = SystemDetachedProcessLauncher(),
) : TaskWorkspaceToolLauncher {
    private val command: List<String>? by lazy(locator::locate)

    override val descriptor = TaskWorkspaceToolDescriptor(
        id = ID,
        displayName = "Cursor",
        description = "任务完成后在 Cursor 中打开任务目录",
    )

    override fun availability(): TaskWorkspaceToolAvailability = command?.let {
        TaskWorkspaceToolAvailability.Available
    } ?: TaskWorkspaceToolAvailability.Unavailable("未找到 Cursor，请先安装并确保系统可以启动 Cursor")

    override fun open(context: TaskWorkspaceContext) {
        val resolved = command ?: error("当前系统未找到 Cursor")
        processLauncher.launch(resolved + context.taskDirectory.toAbsolutePath().normalize().toString())
    }

    companion object {
        const val ID = "cursor"
    }
}
