package com.snowball.awm.desktop

import com.snowball.awm.core.TaskWorkspaceContext
import com.snowball.awm.core.TaskWorkspaceToolAvailability
import com.snowball.awm.core.TaskWorkspaceToolDescriptor
import com.snowball.awm.core.TaskWorkspaceToolLauncher
import java.awt.Desktop
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

fun interface ExternalUriOpener {
    fun open(uri: URI)
}

/** Process boundary that allowlists the Codex scheme before handing it to the OS. */
class SystemExternalUriOpener : ExternalUriOpener {
    override fun open(uri: URI) {
        require(uri.scheme.equals("codex", ignoreCase = true)) { "不允许打开非 Codex URI：${uri.scheme}" }
        when {
            Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE) ->
                Desktop.getDesktop().browse(uri)
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true) ->
                ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", uri.toASCIIString()).start()
            System.getProperty("os.name").startsWith("Mac", ignoreCase = true) ->
                ProcessBuilder("open", uri.toASCIIString()).start()
            else -> error("当前系统不支持打开 Codex 桌面深链")
        }
    }
}

/**
 * Codex-specific adapter; no other application layer needs to know its URI
 * protocol. The deep link opens an empty local task and never embeds a prompt.
 */
class CodexWorkspaceToolLauncher(
    private val uriOpener: ExternalUriOpener = SystemExternalUriOpener(),
) : TaskWorkspaceToolLauncher {
    override val descriptor = TaskWorkspaceToolDescriptor(
        id = ID,
        displayName = "Codex",
        description = "任务完成后在 Codex 中打开任务目录",
    )

    override fun availability(): TaskWorkspaceToolAvailability = TaskWorkspaceToolAvailability.Available

    override fun open(context: TaskWorkspaceContext) {
        val path = context.taskDirectory.toAbsolutePath().normalize().toString()
        val encoded = URLEncoder.encode(path, StandardCharsets.UTF_8).replace("+", "%20")
        uriOpener.open(URI.create("codex://new?path=$encoded"))
    }

    companion object {
        const val ID = "codex"
    }
}
