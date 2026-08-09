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
                    mql.contains(".`Bug`") -> typedIdResponse("22")
                    mql.contains(".`Sprint`") -> typedIdResponse("11")
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

    @Test
    fun `passes the configured project key when reading custom-space titles`() {
        var observedProjectKey: String? = null
        val provider = object : ProjectScopedRequirementMetadataProvider {
            override fun fetch(requirementLink: String): RequirementMetadata? = null
            override fun fetch(requirementLink: String, projectKey: String): RequirementMetadata? {
                observedProjectKey = projectKey
                return RequirementMetadata("自定义标题", null)
            }
        }
        val runner = object : CommandRunner {
            override fun run(command: List<String>, workingDirectory: java.nio.file.Path?, timeout: Duration, environment: Map<String, String>): CommandResult {
                val mql = command[command.indexOf("--mql") + 1]
                return CommandResult(0, if (mql.contains(".`Bug`")) typedIdResponse("22") else if (mql.contains(".`Sprint`")) typedIdResponse("11") else "{}", "")
            }
        }

        val result = MeegleRequirementLinkSource(runner, provider, false)
            .load(listOf(MeegleProjectConfig("project-payment", "payment")))

        assertEquals("project-payment", observedProjectKey)
        assertEquals("自定义标题", result.candidates.single().title)
    }

    private fun typedIdResponse(id: String) = """
        {"data":{"1":[{"moql_field_list":[{"key":"work_item_id","name":"Item Id","value":{"long_value":$id}}]}]}}
    """.trimIndent()
}
