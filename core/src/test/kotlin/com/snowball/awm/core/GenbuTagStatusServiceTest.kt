package com.snowball.awm.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GenbuTagStatusServiceTest {
    @Test
    fun `query invokes Genbu with the configured service and tag`() {
        val commands = mutableListOf<List<String>>()
        val provider = ProcessGenbuTagStatusService(
            executable = GenbuExecutable { "D:/cli/genbu.exe" },
            runner = object : CommandRunner {
                override fun run(command: List<String>, workingDirectory: java.nio.file.Path?, timeout: java.time.Duration, environment: Map<String, String>): CommandResult {
                    commands += command
                    return CommandResult(0, "服务: payment-center\nTag: 1.2.3.4\nTag 构建完成: 是(2026-08-26 10:00:00)\nUAT 发版完成: 否\n生产发版完成: 否\n", "")
                }
            },
        )

        assertEquals(
            GenbuTagQueryResult(built = true, uatReleased = false, productionReleased = false, builtCompletedAt = "2026-08-26 10:00:00"),
            provider.query("payment-center", "1.2.3.4"),
        )
        assertEquals(listOf("D:/cli/genbu.exe", "query-tag", "payment-center", "1.2.3.4"), commands.single())
    }

    @Test
    fun `parser reads the real Genbu inline completion-time format`() {
        assertEquals(
            GenbuTagQueryResult(
                built = true,
                uatReleased = true,
                productionReleased = false,
                builtCompletedAt = "2026-08-26 10:42:28",
                uatReleasedCompletedAt = "2026-08-26 10:42:28",
            ),
            parseGenbuTagQueryOutput("服务: bp-operation-center\nTag: 1.6.92.beta-2\nTag 构建完成: 是(2026-08-26 10:42:28)\nUAT 发版完成: 是(2026-08-26 10:42:28)\n生产发版完成: 否\n"),
        )
    }

    @Test
    fun `parser rejects incomplete output`() {
        assertFailsWith<IllegalStateException> { parseGenbuTagQueryOutput("Tag 构建完成: true\nUAT 发版完成: false") }
    }

    @Test
    fun `query maps Genbu Tag lookup misses to a stable status`() {
        val provider = ProcessGenbuTagStatusService(
            executable = GenbuExecutable { "genbu.exe" },
            runner = object : CommandRunner {
                override fun run(command: List<String>, workingDirectory: java.nio.file.Path?, timeout: java.time.Duration, environment: Map<String, String>) =
                    CommandResult(1, "", "错误：未找到服务 bp-operation-center 的 Tag 1.6.92.beta-2")
            },
        )

        assertEquals(
            GenbuTagQueryResult(built = false, uatReleased = false, productionReleased = false, notFound = true),
            provider.query("operation-center", "1.6.92.beta-2"),
        )
    }

    @Test
    fun `executable probe follows the platform`() {
        assertEquals(listOf("where.exe", "genbu.exe"), genbuProbeCommand("Windows 11"))
        assertEquals("C:\\tools\\genbu.exe", parseGenbuProbeOutput("C:\\tools\\genbu.exe\n", "Windows 11"))
        assertEquals("genbu", genbuFallbackCommand(isWindows = false))
    }

    @Test
    fun `executable reports a detected absolute command path`() {
        val executable = ConfiguredGenbuExecutable(
            runner = object : CommandRunner {
                override fun run(command: List<String>, workingDirectory: java.nio.file.Path?, timeout: java.time.Duration, environment: Map<String, String>) =
                    CommandResult(0, "C:\\tools\\genbu.exe\n", "")
            },
            osName = "Windows 11",
        )

        assertEquals("C:\\tools\\genbu.exe", executable.probe())
        assertEquals(GenbuCommandSource.PROBED, executable.source())
    }

    @Test
    fun `configured path is used only when automatic probing cannot find Genbu`() {
        val configured = "D:\\tools\\genbu.exe"
        val missingProbe = ConfiguredGenbuExecutable(
            configuredPath = { configured },
            runner = object : CommandRunner {
                override fun run(command: List<String>, workingDirectory: java.nio.file.Path?, timeout: java.time.Duration, environment: Map<String, String>) =
                    CommandResult(1, "", "not found")
            },
            osName = "Windows 11",
        )
        val detectedProbe = ConfiguredGenbuExecutable(
            configuredPath = { configured },
            runner = object : CommandRunner {
                override fun run(command: List<String>, workingDirectory: java.nio.file.Path?, timeout: java.time.Duration, environment: Map<String, String>) =
                    CommandResult(0, "C:\\detected\\genbu.exe\n", "")
            },
            osName = "Windows 11",
        )

        assertEquals(configured, missingProbe.resolve())
        assertEquals(GenbuCommandSource.CONFIGURED_FALLBACK, missingProbe.source())
        assertEquals("C:\\detected\\genbu.exe", detectedProbe.resolve())
        assertEquals(GenbuCommandSource.PROBED, detectedProbe.source())
    }
}
