package com.snowball.awm.desktop

import com.snowball.awm.core.TaskWorkspaceContext
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import kotlin.test.assertEquals

class CodexWorkspaceToolLauncherTest {
    @Test
    fun `opens encoded task directory through stable Codex deep link`() {
        val opened = mutableListOf<URI>()
        val launcher = CodexWorkspaceToolLauncher(ExternalUriOpener { opened += it })

        launcher.open(
            TaskWorkspaceContext(
                taskName = "支付任务",
                taskDirectory = Path.of("C:\\研发任务\\PAY 1024"),
                agentsFile = Path.of("C:\\研发任务\\PAY 1024\\AGENTS.md"),
                workspaces = emptyList(),
            ),
        )

        val expectedPath = Path.of("C:\\研发任务\\PAY 1024").toAbsolutePath().normalize().toString()
        val expectedUri = "codex://new?path=" +
            URLEncoder.encode(expectedPath, StandardCharsets.UTF_8).replace("+", "%20")

        assertEquals(expectedUri, opened.single().toASCIIString())
    }
}
