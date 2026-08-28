package com.snowball.awm.core

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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

    @Test
    fun `windows system terminal uses literal arguments and keeps the window open`() {
        assertEquals(
            listOf(
                "powershell.exe", "-NoProfile", "-NoExit", "-Command",
                "& 'C:/Program Files/genbu.exe' '--version' 'a''b'",
            ),
            TerminalLaunchCommand.buildSystemCommand(
                listOf("C:/Program Files/genbu.exe", "--version", "a'b"),
                "Windows 11",
            ),
        )
    }

    @Test
    fun `mac system terminal executes a shell quoted command`() {
        assertEquals(
            listOf(
                "osascript", "-e",
                "tell application \"Terminal\" to do script \"'/Applications/Genbu Tool/genbu' '--version' 'a'\\\\''b'\"",
            ),
            TerminalLaunchCommand.buildSystemCommand(
                listOf("/Applications/Genbu Tool/genbu", "--version", "a'b"),
                "Mac OS X",
            ),
        )
    }

    @Test
    fun `linux system terminal executes command through a shell and waits`() {
        assertEquals(
            listOf(
                "x-terminal-emulator", "-e", "/bin/sh", "-c",
                "'/opt/genbu tool' '--version' 'a'\\''b'; printf '\\nPress Enter to close...'; read -r",
            ),
            TerminalLaunchCommand.buildSystemCommand(
                listOf("/opt/genbu tool", "--version", "a'b"),
                "Linux",
            ),
        )
    }

    @Test
    fun `copy display quotes only values that need shell protection`() {
        assertEquals(
            "'C:/Program Files/genbu.exe' --version 'a''b'",
            TerminalLaunchCommand.display(
                listOf("C:/Program Files/genbu.exe", "--version", "a'b"),
                "Windows 11",
            ),
        )
        assertEquals("git --version", TerminalLaunchCommand.display(listOf("git", "--version"), "Linux"))
    }

    @Test
    fun `empty system command is rejected`() {
        assertFailsWith<IllegalArgumentException> { TerminalLaunchCommand.buildSystemCommand(emptyList(), "Linux") }
        assertFailsWith<IllegalArgumentException> { TerminalLaunchCommand.display(emptyList(), "Linux") }
    }

    @Test
    fun windowsCliLaunchChangesToExecutableDirectoryBeforeRunningIt() {
        val workingDirectory = Path.of("D:/cli-list")
        assertEquals(
            listOf(
                "powershell.exe", "-NoProfile", "-NoExit", "-Command",
                "Set-Location -LiteralPath '" + workingDirectory.normalize() + "'; & 'D:/cli-list/genbu.exe'",
            ),
            TerminalLaunchCommand.buildSystemCommand(
                "D:/cli-list/genbu.exe",
                workingDirectory,
                "Windows 11",
            ),
        )
    }

    @Test
    fun windowsCliLaunchUsesANewWindowsTerminalWindowWhenAvailable() {
        val workingDirectory = Path.of("D:/cli-list")
        assertEquals(
            listOf(
                "wt.exe", "-w", "new", "new-tab", "-d", workingDirectory.normalize().toString(),
                "powershell.exe", "-NoProfile", "-NoExit", "-Command", "& 'D:/cli-list/genbu.exe'",
            ),
            TerminalLaunchCommand.buildSystemCommand(
                command = "D:/cli-list/genbu.exe",
                workingDirectory = workingDirectory,
                osName = "Windows 11",
                windowsTerminalAvailable = true,
            ),
        )
    }

    @Test
    fun windowsCliLaunchQuotesExecutableAndDirectoryIndependently() {
        val workingDirectory = Path.of("D:/Program Files/O'Brien")
        assertEquals(
            listOf(
                "powershell.exe", "-NoProfile", "-NoExit", "-Command",
                "Set-Location -LiteralPath '" +
                    workingDirectory.normalize().toString().replace("'", "''") +
                    "'; & 'D:/Program Files/O''Brien/genbu.exe'",
            ),
            TerminalLaunchCommand.buildSystemCommand(
                "D:/Program Files/O'Brien/genbu.exe",
                workingDirectory,
                "Windows 11",
            ),
        )
    }

    @Test
    fun linuxCliLaunchChangesToExecutableDirectoryBeforeRunningIt() {
        val workingDirectory = Path.of("/opt/genbu tool")
        val normalizedDirectory = workingDirectory.toAbsolutePath().normalize()
        assertEquals(
            listOf(
                "x-terminal-emulator", "-e", "/bin/sh", "-c",
                "cd '" + normalizedDirectory + "' && '/opt/genbu tool/genbu'; printf '\\nPress Enter to close...'; read -r",
            ),
            TerminalLaunchCommand.buildSystemCommand(
                "/opt/genbu tool/genbu",
                workingDirectory,
                "Linux",
            ),
        )
    }

    @Test
    fun macCliLaunchChangesToExecutableDirectoryBeforeRunningIt() {
        val workingDirectory = Path.of("/Applications/Genbu Tool")
        val escapedDirectory = workingDirectory.toAbsolutePath().normalize().toString().replace("\\", "\\\\")
        assertEquals(
            listOf(
                "osascript", "-e",
                "tell application \"Terminal\" to do script \"cd '" +
                    escapedDirectory +
                    "' && '/Applications/Genbu Tool/genbu'\"",
            ),
            TerminalLaunchCommand.buildSystemCommand(
                "/Applications/Genbu Tool/genbu",
                workingDirectory,
                "Mac OS X",
            ),
        )
    }

    @Test
    fun absoluteWindowsExecutablePathGetsItsParentWhileAPathCommandDoesNot() {
        assertEquals(
            Path.of("D:/cli-list"),
            TerminalLaunchCommand.parentDirectoryOfCommand("D:/cli-list/genbu.exe", "Windows 11"),
        )
        assertNull(TerminalLaunchCommand.parentDirectoryOfCommand("genbu.exe", "Windows 11"))
        assertNull(TerminalLaunchCommand.parentDirectoryOfCommand("genbu", "Linux"))
    }

    @Test
    fun blankStringSystemCommandIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            TerminalLaunchCommand.buildSystemCommand(" ", osName = "Linux")
        }
    }
}
