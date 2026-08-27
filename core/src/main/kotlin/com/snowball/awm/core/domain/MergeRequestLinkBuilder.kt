package com.snowball.awm.core

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class RepositoryHostingPlatform { GITLAB, GITHUB }

data class MergeRequestLink(
    val platform: RepositoryHostingPlatform,
    val url: String,
)

object MergeRequestLinkBuilder {
    fun build(originUrl: String, sourceBranch: String, targetBranch: String): MergeRequestLink? {
        val remote = parse(originUrl) ?: return null
        val source = encode(sourceBranch)
        val target = encode(targetBranch)
        return when {
            remote.host.contains("gitlab", ignoreCase = true) -> MergeRequestLink(
                RepositoryHostingPlatform.GITLAB,
                "https://${remote.host}/${remote.path}/-/merge_requests/new?" +
                    "merge_request%5Bsource_branch%5D=$source&merge_request%5Btarget_branch%5D=$target",
            )
            remote.host.equals("github.com", ignoreCase = true) || remote.host.endsWith(".github.com", ignoreCase = true) ->
                MergeRequestLink(
                    RepositoryHostingPlatform.GITHUB,
                    "https://${remote.host}/${remote.path}/compare/$target...$source?expand=1",
                )
            else -> null
        }
    }

    private fun parse(value: String): RemoteRepository? {
        val trimmed = value.trim()
        val scp = Regex("""^(?:[^@]+@)?([^:]+):(.+)$""").matchEntire(trimmed)
        val host: String
        val rawPath: String
        if (scp != null && !trimmed.contains("://")) {
            host = scp.groupValues[1]
            rawPath = scp.groupValues[2]
        } else {
            val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
            if (uri.scheme !in setOf("http", "https", "ssh")) return null
            host = uri.host ?: return null
            rawPath = uri.path.removePrefix("/")
        }
        val path = rawPath.removeSuffix(".git").trim('/')
        if (host.isBlank() || path.count { it == '/' } < 1) return null
        return RemoteRepository(host, path)
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private data class RemoteRepository(val host: String, val path: String)
}
