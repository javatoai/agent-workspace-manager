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
