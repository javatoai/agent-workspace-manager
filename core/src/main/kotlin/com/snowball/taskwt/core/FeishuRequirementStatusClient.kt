package com.snowball.taskwt.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.util.Locale

data class RequirementPerson(
    val name: String,
    val email: String? = null,
)

data class RequirementParticipants(
    val qcOwners: List<RequirementPerson> = emptyList(),
    val productManagers: List<RequirementPerson> = emptyList(),
) {
    val isEmpty: Boolean get() = qcOwners.isEmpty() && productManagers.isEmpty()
}

data class RequirementInfo(
    val status: String?,
    val participants: RequirementParticipants = RequirementParticipants(),
)

fun interface RequirementInfoClient {
    fun fetch(requirementLink: String): RequirementInfo?
}

class FeishuRequirementInfoClient(
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val isWindows: Boolean = System.getProperty("os.name")
        .lowercase(Locale.ROOT)
        .contains("win"),
) : RequirementInfoClient {
    override fun fetch(requirementLink: String): RequirementInfo? {
        val workItem = FeishuWorkItemLink.parse(requirementLink) ?: return null
        val result = runCatching {
            runner.run(
                command = listOf(
                    if (isWindows) "meegle.cmd" else "meegle",
                    "workitem",
                    "get",
                    "--project-key",
                    workItem.projectKey,
                    "--work-item-id",
                    workItem.workItemId,
                    "--format",
                    "json",
                ),
                timeout = Duration.ofSeconds(20),
            )
        }.getOrNull() ?: return null
        if (!result.succeeded) return null
        return runCatching {
            val attributes = json.parseToJsonElement(result.stdout)
                .jsonObject["work_item_attribute"]
                ?.jsonObject
                ?: return null
            RequirementInfo(
                status = attributes["work_item_status"]
                    ?.jsonObject
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.content
                    ?.trim()
                    ?.ifBlank { null },
                participants = participantsFor(workItem.kind, attributes["role_members"]?.jsonArray),
            )
        }.getOrNull()
    }

    private fun participantsFor(kind: String, roles: JsonArray?): RequirementParticipants {
        val qcOwners = membersFor(roles, "QC Owner")
        return when (kind) {
            "userstory" -> RequirementParticipants(
                qcOwners = qcOwners,
                productManagers = membersFor(roles, "产品经理"),
            )
            "technical", "bug" -> RequirementParticipants(qcOwners = qcOwners)
            else -> RequirementParticipants()
        }
    }

    private fun membersFor(roles: JsonArray?, roleName: String): List<RequirementPerson> = roles
        .orEmpty()
        .filter { role -> role.jsonObject["name"]?.jsonPrimitive?.content == roleName }
        .flatMap { role -> role.jsonObject["members"]?.jsonArray.orEmpty() }
        .mapNotNull { member ->
            runCatching {
                val value = member.jsonObject
                value["name"]?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotBlank)?.let { name ->
                    RequirementPerson(name, value["email"]?.jsonPrimitive?.content?.trim()?.ifBlank { null })
                }
            }.getOrNull()
        }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

data class FeishuWorkItemLink(
    val space: String,
    val kind: String,
    val workItemId: String,
    val projectKey: String,
) {
    companion object {
        private val linkPattern = Regex(
            """^https?://project\.feishu\.cn/(obt|rta)/(userstory|othertask|bug|technical)/detail/(\d+)(?:[/?#].*)?$""",
            RegexOption.IGNORE_CASE,
        )
        private val projectKeys = mapOf(
            "obt" to "67c17e40bf0d47db9549cb08",
            "rta" to "680b2de0f1ddf8d50a24dff8",
        )

        fun parse(value: String): FeishuWorkItemLink? {
            val normalized = value.trim().trimEnd(',', '，', '。', ';', '；')
            val match = linkPattern.matchEntire(normalized) ?: return null
            val space = match.groupValues[1].lowercase(Locale.ROOT)
            return FeishuWorkItemLink(
                space = space,
                kind = match.groupValues[2].lowercase(Locale.ROOT),
                workItemId = match.groupValues[3],
                projectKey = projectKeys.getValue(space),
            )
        }
    }
}
