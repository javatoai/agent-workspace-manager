package com.snowball.awm.core

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class DevelopmentToolLaunchCommandTest {
    private val target = Path.of("D:/tasks/demo")

    @Test
    fun `vscode uses a new window while jetbrains tools use the Windows shell`() {
        assertEquals(
            listOf("C:/Code.exe", "--new-window", target.toAbsolutePath().normalize().toString()),
            DevelopmentToolLaunchCommand.build(
                DevelopmentToolType.VISUAL_STUDIO_CODE,
                "C:/Code.exe",
                target,
                "Windows 11",
            ),
        )
        assertEquals(
            windowsShellLaunch("C:/idea64.exe", target.toAbsolutePath().normalize().toString()),
            DevelopmentToolLaunchCommand.build(
                DevelopmentToolType.INTELLIJ_IDEA,
                "C:/idea64.exe",
                target,
                "Windows 11",
            ),
        )
        assertEquals(
            windowsShellLaunch("C:/pycharm64.exe", target.toAbsolutePath().normalize().toString()),
            DevelopmentToolLaunchCommand.build(
                DevelopmentToolType.PYCHARM,
                "C:/pycharm64.exe",
                target,
                "Windows 11",
            ),
        )
        assertEquals(
            windowsShellLaunch("C:/studio64.exe", target.toAbsolutePath().normalize().toString()),
            DevelopmentToolLaunchCommand.build(
                DevelopmentToolType.ANDROID_STUDIO,
                "C:/studio64.exe",
                target,
                "Windows 11",
            ),
        )
    }

    private fun windowsShellLaunch(application: String, directory: String): List<String> =
        listOf(
            "powershell.exe",
            "-NoProfile",
            "-Command",
            "Start-Process -FilePath '$application' -ArgumentList @('$directory')",
        )

    @Test
    fun `mac application bundle is opened without shell interpolation`() {
        assertEquals(
            listOf("open", "-a", "/Applications/DevEco-Studio.app", target.toAbsolutePath().normalize().toString()),
            DevelopmentToolLaunchCommand.build(
                DevelopmentToolType.DEVECO_STUDIO,
                "/Applications/DevEco-Studio.app",
                target,
                "Mac OS X",
            ),
        )
        assertEquals(
            listOf(
                "open", "-n", "-a", "/Applications/Visual Studio Code.app", "--args", "--new-window",
                target.toAbsolutePath().normalize().toString(),
            ),
            DevelopmentToolLaunchCommand.build(
                DevelopmentToolType.VISUAL_STUDIO_CODE,
                "/Applications/Visual Studio Code.app",
                target,
                "Mac OS X",
            ),
        )
    }

    @Test
    fun `windows batch launch is wrapped as separate command arguments`() {
        assertEquals(
            listOf("cmd.exe", "/d", "/c", "C:/tools/code.cmd", "--new-window", target.toAbsolutePath().normalize().toString()),
            DevelopmentToolLaunchCommand.build(
                DevelopmentToolType.VISUAL_STUDIO_CODE,
                "C:/tools/code.cmd",
                target,
                "Windows 11",
            ),
        )
    }
}
