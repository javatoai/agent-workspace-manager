package com.snowball.awm.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.time.Duration
import java.nio.file.Files
import java.nio.file.Path

class BootstrapServiceTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `copies explicit file and continues after a failed command`() {
        val source = temporary.resolve("source")
        val target = temporary.resolve("target")
        Files.createDirectories(source)
        Files.createDirectories(target)
        GitTestSupport.run(source, "init")
        GitTestSupport.run(target, "init")
        Files.writeString(source.resolve(".env.example"), "example")
        val config = BootstrapConfig(
            copyRules = listOf(BootstrapCopyRule(".env.example", ".env")),
            commands = listOf(
                BootstrapCommand(
                    name = "expected failure",
                    executable = "git",
                    arguments = listOf("not-a-real-subcommand"),
                    timeoutSeconds = 10,
                ),
                BootstrapCommand(
                    name = "still runs",
                    executable = "git",
                    arguments = listOf("status", "--short"),
                    timeoutSeconds = 10,
                ),
            ),
        )

        val result = BootstrapService().initialize(source, target, config)

        assertFalse(result.succeeded)
        assertTrue(Files.exists(target.resolve(".env")))
        assertEqualsCompat(3, result.steps.size)
        assertTrue(result.steps.last().succeeded)
    }

    @Test
    fun `rejects path traversal`() {
        val source = temporary.resolve("source")
        val target = temporary.resolve("target")
        Files.createDirectories(source)
        Files.createDirectories(target)
        GitTestSupport.run(source, "init")
        GitTestSupport.run(target, "init")
        Files.writeString(temporary.resolve("secret"), "secret")

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            BootstrapService().initialize(
                source,
                target,
                BootstrapConfig(copyRules = listOf(BootstrapCopyRule("../secret", "secret"))),
            )
        }

        assertFalse(Files.exists(target.resolve("secret")))
    }

    @Test
    fun `rejects enabled commands with non-positive timeout`() {
        val source = temporary.resolve("source-timeout")
        val target = temporary.resolve("target-timeout")
        Files.createDirectories(source)
        Files.createDirectories(target)

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            BootstrapService().initialize(
                source,
                target,
                BootstrapConfig(
                    commands = listOf(
                        BootstrapCommand("invalid", "git", timeoutSeconds = 0),
                    ),
                ),
            )
        }
    }

    @Test
    fun `resolves codegraph to cmd shim on Windows before running bootstrap`() {
        val source = temporary.resolve("source-codegraph")
        val target = temporary.resolve("target-codegraph")
        val bin = temporary.resolve("bin")
        Files.createDirectories(source)
        Files.createDirectories(target)
        Files.createDirectories(bin)
        val shim = Files.createFile(bin.resolve("codegraph.cmd")).toRealPath()
        val runner = RecordingRunner()

        val result = BootstrapService(
            runner = runner,
            osName = "Windows 11",
            environment = mapOf("PATH" to bin.toString(), "PATHEXT" to ".com;.exe;.bat;.cmd"),
        ).initialize(
            source,
            target,
            BootstrapPresets.codeGraph(),
        )

        assertTrue(result.succeeded, result.warnings.joinToString())
        assertEquals(listOf(shim.toString(), "init", "-i"), runner.commands.single())
    }

    @Test
    fun `runs the resolved Windows cmd shim through the real process runner`() {
        assumeTrue(System.getProperty("os.name").contains("win", ignoreCase = true))

        val source = temporary.resolve("source-codegraph-process")
        val target = temporary.resolve("target-codegraph-process")
        val bin = temporary.resolve("bin-codegraph-process")
        Files.createDirectories(source)
        Files.createDirectories(target)
        Files.createDirectories(bin)
        Files.writeString(
            bin.resolve("codegraph.cmd"),
            "@echo off\r\necho codegraph shim ok\r\nexit /b 0\r\n",
        )

        val result = BootstrapService(
            osName = "Windows 11",
            environment = mapOf("PATH" to bin.toString(), "PATHEXT" to ".com;.exe;.bat;.cmd"),
        ).initialize(source, target, BootstrapPresets.codeGraph())

        assertTrue(result.succeeded, result.warnings.joinToString())
        assertTrue(result.steps.single().message.contains("codegraph shim ok"))
    }

    @Test
    fun `rejects symbolic link copy sources`() {
        val source = temporary.resolve("source-link")
        val target = temporary.resolve("target-link")
        val outside = temporary.resolve("outside.txt")
        Files.createDirectories(source)
        Files.createDirectories(target)
        Files.writeString(outside, "outside")
        val link = source.resolve("linked.txt")
        try {
            Files.createSymbolicLink(link, outside)
        } catch (_: UnsupportedOperationException) {
            return
        } catch (_: java.io.IOException) {
            return
        }

        val result = BootstrapService().initialize(
            source,
            target,
            BootstrapConfig(copyRules = listOf(BootstrapCopyRule("linked.txt", "copied.txt"))),
        )

        assertFalse(result.succeeded)
        assertFalse(Files.exists(target.resolve("copied.txt")))
    }

    private fun assertEqualsCompat(expected: Int, actual: Int) {
        assertTrue(expected == actual, "expected=$expected actual=$actual")
    }

    private class RecordingRunner : CommandRunner {
        val commands = mutableListOf<List<String>>()

        override fun run(
            command: List<String>,
            workingDirectory: Path?,
            timeout: Duration,
            environment: Map<String, String>,
        ): CommandResult {
            commands += command
            return CommandResult(0, "", "")
        }
    }
}
