package com.snowball.awm.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration

data class ProductionPodSnapshot(
    val name: String,
    val version: String,
    val phase: String,
    val ready: Boolean,
    val restartCount: Int,
)

data class ProductionRuntimeSnapshot(
    val service: String,
    val environment: String,
    val version: String,
    val pods: List<ProductionPodSnapshot>,
)

fun interface ProductionVersionProvider {
    fun current(repositoryName: String): ProductionRuntimeSnapshot
}

class ProductionVersionUnavailableException(cause: Throwable? = null) :
    IllegalStateException("网络异常", cause)

object GenbuProductionSnapshotParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(output: String): ProductionRuntimeSnapshot {
        val root = json.parseToJsonElement(output).jsonObject
        val service = root["service"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val environment = root["environment"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val pods = root["pods"]?.jsonArray?.map { element ->
            val pod = element.jsonObject
            ProductionPodSnapshot(
                name = pod["pod_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                version = pod["app_version"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                phase = pod["phase"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                ready = pod["ready"]?.jsonPrimitive?.booleanOrNull ?: false,
                restartCount = pod["restart_count"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }.orEmpty()
        check(pods.isNotEmpty()) { "Genbu 未返回生产 Pod" }
        check(pods.all { it.ready && it.phase == "Running" }) { "生产 Pod 未全部处于 Running/Ready" }
        val versions = pods.map(ProductionPodSnapshot::version).filter(String::isNotBlank).distinct()
        check(versions.size == 1) { "生产 Pod 版本不一致：${versions.joinToString()}" }
        return ProductionRuntimeSnapshot(service, environment, versions.single(), pods)
    }
}

class GenbuProductionVersionProvider(
    private val executable: GenbuExecutable = GenbuExecutable.pathFallback(),
    private val runner: CommandRunner = ProcessCommandRunner(),
) : ProductionVersionProvider {
    override fun current(repositoryName: String): ProductionRuntimeSnapshot {
        require(repositoryName.isNotBlank()) { "仓库名称不能为空" }
        return try {
            val result = runner.run(
                listOf(executable.resolve(), "pod", "-j", "prod", repositoryName),
                timeout = Duration.ofMinutes(2),
            )
            if (!result.succeeded) throw IllegalStateException(
                result.stderr.ifBlank { result.stdout }.ifBlank { "Genbu 查询失败" },
            )
            GenbuProductionSnapshotParser.parse(result.stdout)
        } catch (error: ProductionVersionUnavailableException) {
            throw error
        } catch (error: Throwable) {
            throw ProductionVersionUnavailableException(error)
        }
    }
}
