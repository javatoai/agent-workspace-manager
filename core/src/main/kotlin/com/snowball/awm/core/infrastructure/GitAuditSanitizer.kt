package com.snowball.awm.core

/** Credential-safe values for persisted production release audit records. */
object GitAuditSanitizer {
    private val schemeUserInfo = Regex("(?i)([a-z][a-z0-9+.-]*://)[^\\s/@]+@")
    private val authorization = Regex("(?i)(authorization\\s*[:=]\\s*)(?:bearer|basic)?\\s*[^\\s]+")
    private val bearer = Regex("(?i)\\bbearer\\s+[a-z0-9._~+/=-]+")
    private val keyedSecret = Regex(
        "(?i)\\b(token|password|passwd|access[_-]?token|private[_-]?token|oauth[_-]?token)\\s*[:=]\\s*[^\\s&,;]+",
    )

    fun remoteDisplay(raw: String?): String? = raw?.trim()?.takeIf(String::isNotBlank)?.let { value ->
        schemeUserInfo.replace(value.substringBefore('#').substringBefore('?'), "${'$'}1")
    }

    fun text(raw: String): String = keyedSecret.replace(
        bearer.replace(
            authorization.replace(schemeUserInfo.replace(raw, "${'$'}1"), "${'$'}1<redacted>"),
            "Bearer <redacted>",
        ),
    ) { match -> "${match.groupValues[1]}=<redacted>" }

    fun summary(result: CommandResult): String = text("${result.stderr}\n${result.stdout}")
        .lineSequence()
        .map(String::trim)
        .firstOrNull(String::isNotBlank)
        ?.take(300)
        ?: "远端拒绝推送"
}
