package com.snowball.taskwt.desktop

import com.snowball.taskwt.core.TaskWorkspaceContext
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Path
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

        assertEquals(
            "codex://new?path=C%3A%5C%E7%A0%94%E5%8F%91%E4%BB%BB%E5%8A%A1%5CPAY%201024",
            opened.single().toASCIIString(),
        )
    }
}
