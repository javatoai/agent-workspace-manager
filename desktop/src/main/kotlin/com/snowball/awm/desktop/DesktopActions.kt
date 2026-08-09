package com.snowball.awm.desktop

import com.snowball.awm.core.AppConfig
import com.snowball.awm.core.DesktopIntegration
import com.snowball.awm.core.IdeType
import com.snowball.awm.core.ServiceWorkspace
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import java.nio.file.Files

/**
 * The sole adapter for clipboard and operating-system actions. UI and feature
 * controllers receive this small boundary instead of calling AWT or the shell.
 */
class DesktopActions internal constructor(
    private val integration: DesktopIntegration,
    private val config: () -> AppConfig,
    private val onSettingsRequired: () -> Unit,
    private val onStatus: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    fun openWorkspace(workspace: ServiceWorkspace) {
        val executable = when (workspace.ideType) {
            IdeType.IDEA -> config().ideaExecutable
            IdeType.WEBSTORM -> config().webStormExecutable
        }
        if (executable.isNullOrBlank()) {
            onSettingsRequired()
            onError(IllegalStateException("请先在设置中配置 IDE 可执行文件"))
            return
        }
        attempt { integration.openIde(Path.of(workspace.worktreePath), executable) }
    }

    /** Creates and opens AWM's task-local work-data directory in IDEA. */
    fun openWorkData(taskDirectory: Path) {
        val executable = config().ideaExecutable
        if (executable.isNullOrBlank()) {
            onSettingsRequired()
            onError(IllegalStateException("请先在设置中配置 IDEA 可执行文件"))
            return
        }
        attempt {
            val directory = taskDirectory.resolve("ai-data")
            Files.createDirectories(directory)
            integration.openIde(directory, executable)
        }
    }

    fun reveal(path: Path) = attempt { integration.reveal(path) }
    fun openDirectory(path: Path) = attempt { integration.openDirectory(path) }
    fun terminal(path: Path) = attempt { integration.openTerminal(path, config().terminalExecutable) }
    fun openUrl(url: String) = attempt { integration.openUrl(url) }

    fun copy(text: String, message: String = "已复制") = attempt {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }.onSuccess { onStatus(message) }

    private fun attempt(block: () -> Unit): Result<Unit> = runCatching(block).onFailure(onError)
}
