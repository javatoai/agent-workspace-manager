package com.snowball.awm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

class MeegleRequirementLinkSourceTest {
    @Test fun `maps deduplicated bug links from active sprint`() {
        val runner = object : CommandRunner {
            override fun run(command: List<String>, workingDirectory: java.nio.file.Path?, timeout: Duration, environment: Map<String, String>): CommandResult {
                if (command.contains("version")) return CommandResult(0, "1", "")
                val mql = command[command.indexOf("--mql") + 1]
                val json = when {
                    mql.contains(".`Bug`") -> """{"data":{"Item Id":"22"}}"""
                    mql.contains(".`Sprint`") -> """{"data":{"Item Id":"11"}}"""
                    else -> "{}"
                }
                return CommandResult(0, json, "")
            }
        }
        val source = MeegleRequirementLinkSource(runner, RequirementMetadataProvider { RequirementMetadata("标题", null) }, false)
        val result = source.load(listOf(MeegleProjectConfig("obt", "obt")))
        assertEquals(listOf("https://project.feishu.cn/obt/bug/detail/22"), result.candidates.map { it.url })
        assertEquals("标题", result.candidates.single().title)
    }
}
