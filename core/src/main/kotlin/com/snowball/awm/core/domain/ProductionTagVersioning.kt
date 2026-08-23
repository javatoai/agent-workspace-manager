package com.snowball.awm.core

data class ProductionRemoteTag(
    val name: String,
    val commitSha: String,
)

sealed interface ProductionTagExpectation {
    val tag: String

    data class Create(override val tag: String) : ProductionTagExpectation
    data class AlreadyBuilt(override val tag: String) : ProductionTagExpectation
}

/** Version rules for immutable production tags. Beta tags never participate. */
object ProductionTagVersioning {
    private val basePattern = Regex("""^(\d+)\.(\d+)\.(\d+)$""")

    fun nextBase(tags: Collection<String>): String {
        val latest = tags.mapNotNull { tag ->
            basePattern.matchEntire(tag)?.destructured?.let { (major, minor, patch) ->
                SemanticBase(major.toLong(), minor.toLong(), patch.toLong())
            }
        }.maxOrNull() ?: error("远端不存在 major.minor.patch 格式的正式 Tag")
        return "${latest.major}.${latest.minor}.${latest.patch + 1}"
    }

    fun expectation(
        baseVersion: String,
        releaseSha: String,
        tags: Collection<ProductionRemoteTag>,
    ): ProductionTagExpectation {
        require(basePattern.matches(baseVersion)) { "基础版本必须使用 major.minor.patch：$baseVersion" }
        require(releaseSha.isNotBlank()) { "Release SHA 不能为空" }
        val matching = tags.mapNotNull { remoteTag ->
            suffix(baseVersion, remoteTag.name)?.let { it to remoteTag }
        }
        matching.firstOrNull { (_, remoteTag) -> remoteTag.commitSha == releaseSha }?.let { (_, remoteTag) ->
            return ProductionTagExpectation.AlreadyBuilt(remoteTag.name)
        }
        val next = matching.maxOfOrNull(Pair<Int, ProductionRemoteTag>::first)?.plus(1)
            ?: return ProductionTagExpectation.Create(baseVersion)
        return ProductionTagExpectation.Create("$baseVersion.$next")
    }

    private fun suffix(baseVersion: String, tag: String): Int? = when {
        tag == baseVersion -> 0
        else -> Regex("^${Regex.escape(baseVersion)}\\.([1-9]\\d*)$")
            .matchEntire(tag)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    }

    private data class SemanticBase(
        val major: Long,
        val minor: Long,
        val patch: Long,
    ) : Comparable<SemanticBase> {
        override fun compareTo(other: SemanticBase): Int = compareValuesBy(
            this,
            other,
            SemanticBase::major,
            SemanticBase::minor,
            SemanticBase::patch,
        )
    }
}
