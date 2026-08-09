package com.snowball.awm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.time.Duration

class MeegleRequirementMetadataProviderTest {
    @Test
    fun `reads title using documented compatibility priority`() {
        val topLevel = MeegleRequirementMetadataProvider(
            RecordingRunner(CommandResult(0, """{"name":"顶层标题","work_item_name":"备用","work_item_attribute":{"name":"嵌套"}}""", "")),
            isWindows = false,
        )
        assertEquals("顶层标题", topLevel.fetch("https://project.feishu.cn/obt/userstory/detail/1")?.title)

        val nested = MeegleRequirementMetadataProvider(
            RecordingRunner(CommandResult(0, """{"work_item_attribute":{"work_item_name":"嵌套标题"}}""", "")),
            isWindows = false,
        )
        assertEquals("嵌套标题", nested.fetch("https://project.feishu.cn/obt/userstory/detail/2")?.title)
    }

    @Test
    fun `fetches story status qc owner and product manager`() {
        val runner = RecordingRunner(
            CommandResult(
                0,
                """{"work_item_attribute":{"work_item_status":{"name":"待评审"},"role_members":[{"name":"QC Owner","members":[{"name":"靳保新","email":"derrick.jin@snowballtech.com"}]},{"name":"产品经理","members":[{"name":"黄倩","email":"ian.huang@snowballtech.com"}]}]}}""",
                "",
            ),
        )
        val client = MeegleRequirementMetadataProvider(runner, isWindows = false)

        assertEquals(
            RequirementMetadata(
                status = "待评审",
                participants = RequirementParticipants(
                    qcOwners = listOf(RequirementPerson("靳保新", "derrick.jin@snowballtech.com")),
                    productManagers = listOf(RequirementPerson("黄倩", "ian.huang@snowballtech.com")),
                ),
            ),
            client.fetch("https://project.feishu.cn/obt/userstory/detail/7060612727"),
        )
        assertEquals(
            listOf(
                "meegle",
                "workitem",
                "get",
                "--project-key",
                "67c17e40bf0d47db9549cb08",
                "--work-item-id",
                "7060612727",
                "--format",
                "json",
            ),
            runner.command,
        )
        assertEquals(Duration.ofSeconds(8), runner.timeout)
    }

    @Test
    fun `technical and bug include qc owner only while task includes no roles`() {
        val runner = RecordingRunner(
            CommandResult(
                0,
                """{"work_item_attribute":{"work_item_status":{"name":"进行中"},"role_members":[{"name":"QC Owner","members":[{"name":"测试","email":"qa@example.com"}]},{"name":"产品经理","members":[{"name":"产品","email":"pm@example.com"}]}]}}""",
                "",
            ),
        )
        val client = MeegleRequirementMetadataProvider(runner, isWindows = false)

        assertEquals(
            RequirementParticipants(qcOwners = listOf(RequirementPerson("测试", "qa@example.com"))),
            client.fetch("https://project.feishu.cn/obt/technical/detail/6996636709")!!.participants,
        )
        assertEquals(
            RequirementParticipants(qcOwners = listOf(RequirementPerson("测试", "qa@example.com"))),
            client.fetch("https://project.feishu.cn/obt/bug/detail/7066114548,")!!.participants,
        )
        assertEquals(
            RequirementParticipants(),
            client.fetch("https://project.feishu.cn/obt/othertask/detail/7055846637")!!.participants,
        )
    }

    @Test
    fun `silently omits missing role members`() {
        val client = MeegleRequirementMetadataProvider(
            RecordingRunner(CommandResult(0, """{"work_item_attribute":{"role_members":[]}}""", "")),
            isWindows = false,
        )

        assertEquals(
            RequirementParticipants(),
            client.fetch("https://project.feishu.cn/obt/bug/detail/7066114548")!!.participants,
        )
    }

    @Test
    fun `returns null without invoking cli for non feishu links`() {
        val runner = RecordingRunner(CommandResult(0, "{}", ""))

        assertNull(MeegleRequirementMetadataProvider(runner, isWindows = true).fetch("https://example.com/task"))
        assertNull(runner.command)
    }

    @Test
    fun `returns null when cli fails or status is absent`() {
        assertNull(
            MeegleRequirementMetadataProvider(
                RecordingRunner(CommandResult(1, "", "not authenticated")),
                isWindows = true,
            ).fetch("https://project.feishu.cn/rta/bug/detail/123"),
        )
        assertNull(
            MeegleRequirementMetadataProvider(
                RecordingRunner(CommandResult(0, "{}", "")),
                isWindows = true,
            ).fetch("https://project.feishu.cn/rta/bug/detail/123"),
        )
    }

    private class RecordingRunner(
        private val result: CommandResult,
    ) : CommandRunner {
        var command: List<String>? = null
        var timeout: Duration? = null

        override fun run(
            command: List<String>,
            workingDirectory: Path?,
            timeout: Duration,
            environment: Map<String, String>,
        ): CommandResult {
            this.command = command
            this.timeout = timeout
            return result
        }
    }
}
