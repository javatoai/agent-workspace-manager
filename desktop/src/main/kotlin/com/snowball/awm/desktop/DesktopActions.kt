package com.snowball.awm.desktop

import com.snowball.awm.core.AppConfig
import com.snowball.awm.core.DesktopIntegration
import com.snowball.awm.core.DevelopmentToolType
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
    fun openWorkspace(workspace: ServiceWorkspace, type: DevelopmentToolType = workspace.developmentTool) {
        val path = config().developmentTools.firstOrNull { it.type == type }?.path
        if (path.isNullOrBlank()) {
            onSettingsRequired()
            onError(IllegalStateException("请先在设置中配置 ${type.displayName} 路径"))
            return
        }
        attempt { integration.openDevelopmentTool(Path.of(workspace.worktreePath), type, path) }
    }

    /** Creates and opens AWM's task-local work-data directory in IDEA. */
    fun openWorkData(taskDirectory: Path, type: DevelopmentToolType = config().defaultDevelopmentTool) {
        val path = config().developmentTools.firstOrNull { it.type == type }?.path
        if (path.isNullOrBlank()) {
            onSettingsRequired()
            onError(IllegalStateException("请先在设置中配置 ${type.displayName} 路径"))
            return
        }
        attempt {
            val directory = taskDirectory.resolve("ai-data")
            Files.createDirectories(directory)
            integration.openDevelopmentTool(directory, type, path)
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

internal fun temporaryDevelopmentToolSelectionEnabled(config: AppConfig): Boolean =
    config.allowTemporaryDevelopmentToolSelection

internal val DevelopmentToolType.displayName: String
    get() = when (this) {
        DevelopmentToolType.INTELLIJ_IDEA -> "IntelliJ IDEA"
        DevelopmentToolType.WEBSTORM -> "WebStorm"
        DevelopmentToolType.PYCHARM -> "PyCharm"
        DevelopmentToolType.VISUAL_STUDIO_CODE -> "Visual Studio Code"
        DevelopmentToolType.ANDROID_STUDIO -> "Android Studio"
        DevelopmentToolType.DEVECO_STUDIO -> "DevEco Studio"
    }
