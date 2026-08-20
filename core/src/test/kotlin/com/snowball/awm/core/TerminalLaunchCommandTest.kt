package com.snowball.awm.core

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalLaunchCommandTest {
    private val target = Path.of("D:/tasks/demo")

    @Test
    fun `mac application bundle is launched through open`() {
        assertEquals(
            listOf("open", "-a", "/Applications/iTerm.app", target.toAbsolutePath().normalize().toString()),
            TerminalLaunchCommand.build("/Applications/iTerm.app", target, "Mac OS X"),
        )
    }

    @Test
    fun `mac executable path is preserved`() {
        assertEquals(
            listOf("/usr/local/bin/kitty", target.toAbsolutePath().normalize().toString()),
            TerminalLaunchCommand.build("/usr/local/bin/kitty", target, "Mac OS X"),
        )
    }
}
