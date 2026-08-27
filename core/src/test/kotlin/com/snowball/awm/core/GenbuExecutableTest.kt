package com.snowball.awm.core

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GenbuExecutableTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `configured absolute executable wins without probing`() {
        val executable = Files.createFile(temporary.resolve("configured-genbu.exe"))
        val runner = RecordingRunner(CommandResult(1, "", "must not run"))
        val genbu = ConfiguredGenbuExecutable(
            configuredPath = { executable.toString() },
            runner = runner,
            osName = "Windows 11",
            bundledDirectories = { emptyList() },
        )

        assertEquals(executable.toString(), genbu.resolve())
        assertEquals(GenbuCommandSource.CONFIGURED, genbu.source())
        assertEquals(0, runner.calls)
    }

    @Test
    fun `portable executable in a candidate directory is automatically detected`() {
        val directory = Files.createDirectories(temporary.resolve("Downloads"))
        val executable = Files.createFile(directory.resolve("genbu.exe"))
        val genbu = ConfiguredGenbuExecutable(
            configuredPath = { null },
            runner = RecordingRunner(CommandResult(1, "", "not on PATH")),
            osName = "Windows 11",
            bundledDirectories = { listOf(directory) },
        )

        assertEquals(executable.toAbsolutePath().normalize().toString(), genbu.probe())
        assertEquals(GenbuCommandSource.PROBED, genbu.source())
    }

    @Test
    fun `PATH probing accepts only an existing absolute executable`() {
        val executable = Files.createFile(temporary.resolve("genbu.exe"))
        val genbu = ConfiguredGenbuExecutable(
            configuredPath = { null },
            runner = RecordingRunner(CommandResult(0, "C:\\missing\\genbu.exe\n$executable\n", "")),
            osName = "Windows 11",
            bundledDirectories = { emptyList() },
        )

        assertEquals(executable.toString(), genbu.probe())
        assertEquals(GenbuCommandSource.PROBED, genbu.source())
    }

    @Test
    fun `failed automatic detection retains the bare command fallback`() {
        val genbu = ConfiguredGenbuExecutable(
            configuredPath = { null },
            runner = RecordingRunner(CommandResult(1, "", "not found")),
            osName = "Windows 11",
            bundledDirectories = { emptyList() },
        )

        assertEquals("genbu.exe", genbu.probe())
        assertEquals(GenbuCommandSource.PATH_FALLBACK, genbu.source())
    }

    @Test
    fun `detect rescans locations and ignores a still-valid configured path`() {
        val configured = Files.createFile(temporary.resolve("configured-genbu.exe"))
        val directory = Files.createDirectories(temporary.resolve("Downloads"))
        val moved = Files.createFile(directory.resolve("genbu.exe"))
        val genbu = ConfiguredGenbuExecutable(
            configuredPath = { configured.toString() },
            runner = RecordingRunner(CommandResult(1, "", "not found")),
            osName = "Windows 11",
            bundledDirectories = { listOf(directory) },
        )

        assertEquals(moved.toAbsolutePath().normalize().toString(), genbu.detect())
        assertEquals(configured.toString(), genbu.resolve())
        assertEquals(GenbuCommandSource.CONFIGURED, genbu.source())
    }

    @Test
    fun `macOS probing uses a zsh login shell`() {
        val commands = mutableListOf<List<String>>()
        val genbu = ConfiguredGenbuExecutable(
            configuredPath = { null },
            runner = object : CommandRunner {
                override fun run(
                    command: List<String>,
                    workingDirectory: Path?,
                    timeout: Duration,
                    environment: Map<String, String>,
                ): CommandResult {
                    commands += command
                    return CommandResult(1, "", "not found")
                }
            },
            osName = "Mac OS X",
            bundledDirectories = { emptyList() },
        )

        genbu.probe()

        assertEquals(listOf(listOf("/bin/zsh", "-lc", "command -v genbu")), commands)
    }

    @Test
    fun `manual path normalization rejects relative and missing files`() {
        assertFailsWith<IllegalArgumentException> { normalizeGenbuExecutablePath("genbu.exe") }
        assertFailsWith<IllegalArgumentException> { normalizeGenbuExecutablePath(temporary.resolve("missing.exe").toString()) }
        assertEquals(null, normalizeGenbuExecutablePath(" "))
    }

    private class RecordingRunner(private val result: CommandResult) : CommandRunner {
        var calls = 0

        override fun run(
            command: List<String>,
            workingDirectory: Path?,
            timeout: Duration,
            environment: Map<String, String>,
        ): CommandResult {
            calls += 1
            return result
        }
    }
}
