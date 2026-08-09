package com.snowball.awm.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.Duration
import java.util.Locale

/** Local Meegle CLI implementation. It deliberately receives configured spaces instead of discovering project spaces. */
class MeegleRequirementLinkSource(
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val metadata: RequirementMetadataProvider = MeegleRequirementMetadataProvider(runner),
    private val isWindows: Boolean = System.getProperty("os.name").lowercase(Locale.ROOT).contains("win"),
) : RequirementLinkSource() {
    override val sourceId: String = "meegle"
    private fun command() = if (isWindows) "meegle.cmd" else "meegle"
    override fun isInstalled(): Boolean = runCatching { runner.run(listOf(command(), "version"), timeout = Duration.ofSeconds(4)).succeeded }.getOrDefault(false)
    override fun load(projects: List<MeegleProjectConfig>): RequirementLinkLoadResult {
        val links = linkedSetOf<String>(); val failures = mutableListOf<RequirementLinkFailure>()
        projects.forEach { project ->
            val sprints = query(project, "Sprint", "`Status` = '进行中'", failures, null)
            sprints.forEach { sprint -> types.forEach { (type, path) ->
                query(project, type, "array_contains(`Sprint`, '<id:$sprint>') AND array_contains(all_participate_persons(), current_login_user())", failures, sprint)
                    .forEach { id -> links += "https://project.feishu.cn/${project.simpleName}/$path/detail/$id" }
            } }
        }
        return RequirementLinkLoadResult(links.map { url -> RequirementLinkCandidate(metadata.fetch(url)?.title ?: "未读取到需求标题", url, sourceId) }, failures)
    }
    private fun query(project: MeegleProjectConfig, type: String, where: String, failures: MutableList<RequirementLinkFailure>, sprint: String?): List<String> {
        val mql = "SELECT `Item Id` FROM `${project.projectKey}`.`$type` WHERE $where LIMIT 100"
        val result = runCatching { runner.run(listOf(command(), "workitem", "query", "--project-key", project.projectKey, "--mql", mql, "--auto-paginate", "--format", "json"), timeout = Duration.ofSeconds(20)) }.getOrElse {
            failures += RequirementLinkFailure("query", project.projectKey, sprint, type, it.message.orEmpty()); return emptyList()
        }
        if (!result.succeeded) { failures += RequirementLinkFailure("query", project.projectKey, sprint, type, result.stderr.take(300)); return emptyList() }
        return runCatching { itemIds(json.parseToJsonElement(result.stdout)) }.getOrElse { failures += RequirementLinkFailure("parse", project.projectKey, sprint, type, it.message.orEmpty()); emptyList() }
    }
    private fun itemIds(element: JsonElement): List<String> = mutableListOf<String>().also { collectItemIds(element, it) }
    private fun collectItemIds(element: JsonElement, results: MutableList<String>) { when (element) {
        is JsonObject -> {
            // Actual `workitem query --format json` responses encode each selected
            // column as { key: "work_item_id", value: { long_value: ... } }.
            // Read that pair before recursively handling simpler fixture shapes.
            val typedFieldKey = (element["key"] as? JsonPrimitive)?.content.orEmpty()
            if (typedFieldKey.normalizedFieldName() in itemIdFieldNames) {
                itemIdValue(element["value"] ?: return)?.let(results::add)
                return
            }
            element.forEach { (key, value) ->
                if (key.normalizedFieldName() in itemIdFieldNames) {
                    itemIdValue(value)?.let(results::add)
                } else collectItemIds(value, results)
            }
        }
        is kotlinx.serialization.json.JsonArray -> element.forEach { collectItemIds(it, results) }
        else -> Unit
    } }

    private fun String.normalizedFieldName(): String = replace("_", "").replace(" ", "").lowercase()

    /**
     * The Meegle CLI represents selected fields as typed values (for example
     * `value.long_value`), while lightweight fixtures may use a direct JSON
     * primitive.  Restricting this unwrapping to the known typed-value keys
     * keeps unrelated numeric response fields out of the result set.
     */
    private fun itemIdValue(value: JsonElement): String? = when (value) {
        is JsonPrimitive -> value.content.takeIf(String::isNotBlank)
        is JsonObject -> listOf("long_value", "string_value", "text_value")
            .firstNotNullOfOrNull { key -> value[key]?.let(::itemIdValue) }
        else -> null
    }
    private companion object {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val itemIdFieldNames = setOf("itemid", "workitemid")
        val types = linkedMapOf("User Story" to "userstory", "Tech Improvement" to "technical", "Bug" to "bug", "Task" to "othertask")
    }
}
