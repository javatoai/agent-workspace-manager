package com.snowball.awm.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.util.Locale

class MeegleRequirementMetadataProvider(
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val isWindows: Boolean = System.getProperty("os.name")
        .lowercase(Locale.ROOT)
        .contains("win"),
) : ProjectScopedRequirementMetadataProvider {
    override fun fetch(requirementLink: String): RequirementMetadata? =
        FeishuWorkItemLink.parse(requirementLink)?.projectKey?.let { projectKey ->
            fetch(requirementLink, projectKey)
        }

    override fun fetch(requirementLink: String, projectKey: String): RequirementMetadata? {
        val workItem = FeishuWorkItemLink.parse(requirementLink) ?: return null
        val result = runCatching {
            runner.run(
                command = listOf(
                    if (isWindows) "meegle.cmd" else "meegle",
                    "workitem",
                    "get",
                    "--project-key",
                    projectKey,
                    "--work-item-id",
                    workItem.workItemId,
                    "--format",
                    "json",
                ),
                // Metadata is optional while creating a task. A short bound
                // prevents an unavailable local CLI from blocking the form.
                timeout = Duration.ofSeconds(8),
            )
        }.getOrNull() ?: return null
        if (!result.succeeded) return null
        return runCatching {
            val root = json.parseToJsonElement(result.stdout).jsonObject
            val attributes = root["work_item_attribute"]?.jsonObject
            RequirementMetadata(
                title = sequenceOf(
                    root["name"],
                    root["work_item_name"],
                    attributes?.get("name"),
                    attributes?.get("work_item_name"),
                ).mapNotNull { element ->
                    runCatching { element?.jsonPrimitive?.content?.trim()?.ifBlank { null } }.getOrNull()
                }.firstOrNull(),
                status = attributes?.get("work_item_status")
                    ?.jsonObject
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.content
                    ?.trim()
                    ?.ifBlank { null },
                participants = participantsFor(workItem.kind, attributes?.get("role_members")?.jsonArray),
            ).takeIf { attributes != null || it.title != null || it.status != null || !it.participants.isEmpty }
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
