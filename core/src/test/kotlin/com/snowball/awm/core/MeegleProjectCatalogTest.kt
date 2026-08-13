package com.snowball.awm.core

import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MeegleProjectCatalogTest {
    @Test
    fun `catalog requests all projects and returns their three identities`() {
        val commands = mutableListOf<List<String>>()
        val runner = object : CommandRunner {
            override fun run(command: List<String>, workingDirectory: Path?, timeout: Duration, environment: Map<String, String>): CommandResult {
                commands += command
                return CommandResult(0, """{"projects":[{"name":"RTA","project_key":"pk-rta","simple_name":"rta"}]}""", "")
            }
        }

        val projects = CliMeegleProjectCatalog(runner, isWindows = false).list()

        assertEquals(listOf(MeegleProjectSummary("RTA", "pk-rta", "rta")), projects)
        assertEquals(
            listOf("meegle", "project", "search", "--auto-paginate", "--format", "json"),
            commands.single(),
        )
    }

    @Test
    fun `catalog surfaces command output instead of accepting stale projects`() {
        val runner = object : CommandRunner {
            override fun run(command: List<String>, workingDirectory: Path?, timeout: Duration, environment: Map<String, String>) =
                CommandResult(1, "", "not logged in")
        }

        val error = assertFailsWith<IllegalStateException> { CliMeegleProjectCatalog(runner, isWindows = true).list() }

        kotlin.test.assertTrue(error.message.orEmpty().contains("not logged in"))
    }
}

class MeegleCliServiceTest {
    @Test
    fun `status reports version authentication host and expiry`() {
        val commands = mutableListOf<List<String>>()
        val runner = object : CommandRunner {
            override fun run(command: List<String>, workingDirectory: Path?, timeout: Duration, environment: Map<String, String>): CommandResult {
                commands += command
                return when (commands.size) {
                    1 -> CommandResult(0, "1.0.19\n", "")
                    else -> CommandResult(0, """{"authenticated":true,"host":"project.feishu.cn","expires_in_minutes":42}""", "")
                }
            }
        }

        val status = ProcessMeegleCliService(runner, isWindows = true).status()

        assertTrue(status.installed)
        assertTrue(status.authenticated)
        assertEquals("1.0.19", status.version)
        assertEquals("project.feishu.cn", status.host)
        assertEquals(42, status.expiresInMinutes)
        assertEquals(listOf("meegle.cmd", "--version"), commands[0])
        assertEquals(listOf("meegle.cmd", "auth", "status", "--format", "json"), commands[1])
    }

    @Test
    fun `missing executable is represented as not installed`() {
        val runner = object : CommandRunner {
            override fun run(command: List<String>, workingDirectory: Path?, timeout: Duration, environment: Map<String, String>): CommandResult =
                throw java.io.IOException("not found")
        }

        val status = ProcessMeegleCliService(runner, isWindows = false).status()

        assertFalse(status.installed)
        assertFalse(status.authenticated)
    }

    @Test
    fun `login uses browser oauth command and ten minute timeout`() {
        var capturedCommand = emptyList<String>()
        var capturedTimeout = Duration.ZERO
        val runner = object : CommandRunner {
            override fun run(command: List<String>, workingDirectory: Path?, timeout: Duration, environment: Map<String, String>): CommandResult {
                capturedCommand = command
                capturedTimeout = timeout
                return CommandResult(0, "{}", "")
            }
        }

        ProcessMeegleCliService(runner, isWindows = false).login()

        assertEquals(listOf("meegle", "auth", "login", "--host", "project.feishu.cn", "--format", "json"), capturedCommand)
        assertEquals(Duration.ofMinutes(10), capturedTimeout)
    }
}
