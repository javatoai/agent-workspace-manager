package com.snowball.awm.core

import java.time.Duration

data class GenbuTagQueryResult(
    val built: Boolean,
    val uatReleased: Boolean,
    val productionReleased: Boolean,
    val notFound: Boolean = false,
    val builtCompletedAt: String? = null,
    val uatReleasedCompletedAt: String? = null,
    val productionReleasedCompletedAt: String? = null,
) {
    init {
        require(!notFound || (!built && !uatReleased && !productionReleased)) { "未在 Genbu 中找到的 Tag 不应包含发布状态" }
        require(!uatReleased || built) { "已在 UAT 发布的 Tag 必须已构建" }
        require(!productionReleased || built) { "已在生产发布的 Tag 必须已构建" }
    }
}

fun interface GenbuTagStatusProvider {
    fun query(serviceName: String, tag: String): GenbuTagQueryResult
}

/** Executes the single supported read-only Genbu command for a Tag. */
class ProcessGenbuTagStatusService(
    private val executable: GenbuExecutable = ConfiguredGenbuExecutable(),
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val timeout: Duration = Duration.ofSeconds(20),
) : GenbuTagStatusProvider {
    override fun query(serviceName: String, tag: String): GenbuTagQueryResult {
        require(serviceName.isNotBlank()) { "Genbu 服务名不能为空" }
        require(tag.isNotBlank()) { "Tag 不能为空" }
        val result = runner.run(listOf(executable.resolve(), "query-tag", serviceName, tag), timeout = timeout)
        if (!result.succeeded) {
            val detail = listOf(result.stderr, result.stdout).firstOrNull(String::isNotBlank)?.trim()
            if (detail != null && isGenbuTagNotFound(detail)) {
                return GenbuTagQueryResult(built = false, uatReleased = false, productionReleased = false, notFound = true)
            }
            error(detail ?: "genbu query-tag 执行失败（退出码 ${result.exitCode}）")
        }
        return parseGenbuTagQueryOutput(result.stdout)
    }
}

private fun isGenbuTagNotFound(detail: String): Boolean =
    detail.contains("未在产线", ignoreCase = true) ||
        Regex("""未找到.*(?:Tag|tag)""").containsMatchIn(detail)

fun parseGenbuTagQueryOutput(output: String): GenbuTagQueryResult {
    val built = parseGenbuBoolean(output, "Tag 构建完成", "构建完成")
        ?: error("无法识别 Genbu Tag 构建状态")
    val uatReleased = parseGenbuBoolean(output, "UAT 发版完成", "测试环境发版完成")
        ?: error("无法识别 Genbu UAT 发版状态")
    val productionReleased = parseGenbuBoolean(output, "生产发版完成", "生产环境发版完成", "PRD 发版完成")
        ?: error("无法识别 Genbu 生产发版状态")
    return GenbuTagQueryResult(
        built = built || uatReleased || productionReleased,
        uatReleased = uatReleased,
        productionReleased = productionReleased,
        builtCompletedAt = parseGenbuCompletionTime(output, "Tag 构建完成时间", "构建完成时间", "Tag 构建完成"),
        uatReleasedCompletedAt = parseGenbuCompletionTime(output, "UAT 发版完成时间", "测试环境发版完成时间", "UAT 发版完成"),
        productionReleasedCompletedAt = parseGenbuCompletionTime(output, "生产发版完成时间", "生产环境发版完成时间", "PRD 发版完成时间", "生产发版完成"),
    )
}

private fun parseGenbuBoolean(output: String, vararg labels: String): Boolean? = output.lineSequence()
    .map(String::trim)
    .firstNotNullOfOrNull { line ->
        val label = labels.firstOrNull { line.contains(it, ignoreCase = true) } ?: return@firstNotNullOfOrNull null
        val value = line.substringAfter(label).trim().trimStart(':', '：').trim().lowercase()
        when (value) {
            "true", "yes", "是", "成功", "已完成", "完成" -> true
            "false", "no", "否", "未完成", "未构建", "未发布" -> false
            else -> when {
                value.startsWith("true") || value.startsWith("yes") || value.startsWith("是") || value.startsWith("成功") || value.startsWith("已完成") || value.startsWith("完成") -> true
                value.startsWith("false") || value.startsWith("no") || value.startsWith("否") || value.startsWith("未完成") || value.startsWith("未构建") || value.startsWith("未发布") -> false
                else -> null
            }
        }
    }

private fun parseGenbuCompletionTime(output: String, vararg labels: String): String? = output.lineSequence()
    .map(String::trim)
    .firstNotNullOfOrNull { line ->
        val label = labels.firstOrNull { line.contains(it, ignoreCase = true) } ?: return@firstNotNullOfOrNull null
        val value = line.substringAfter(label).trim().trimStart(':', '：').trim()
        Regex("""[（(]([^()（）]+)[）)]""").find(value)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotBlank)
            ?: value.takeIf { it.isNotBlank() && !isGenbuBooleanState(it) }
    }

private fun isGenbuBooleanState(value: String): Boolean {
    val normalized = value.lowercase()
    return listOf("true", "yes", "是", "成功", "已完成", "完成", "false", "no", "否", "未完成", "未构建", "未发布")
        .any { state -> normalized == state || normalized.startsWith("$state(") || normalized.startsWith("$state（") }
}
