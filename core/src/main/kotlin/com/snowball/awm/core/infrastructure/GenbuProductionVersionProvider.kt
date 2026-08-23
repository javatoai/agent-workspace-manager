package com.snowball.awm.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration

@kotlinx.serialization.Serializable
data class ProductionPodSnapshot(
    val name: String,
    val version: String,
    val phase: String,
    val ready: Boolean,
    val restartCount: Int,
)

@kotlinx.serialization.Serializable
data class ProductionRuntimeSnapshot(
    val service: String,
    val environment: String,
    val version: String,
    val pods: List<ProductionPodSnapshot>,
    val queryCommand: String = "",
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
        val service = root["service"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank) ?: error("Genbu 缺少 service")
        val environment = root["environment"]?.jsonPrimitive?.contentOrNull
            ?.takeIf(String::isNotBlank) ?: error("Genbu 缺少 environment")
        val pods = root["pods"]?.jsonArray?.map { element ->
            val pod = element.jsonObject
            ProductionPodSnapshot(
                name = pod["pod_name"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf(String::isNotBlank) ?: error("Genbu Pod 缺少 pod_name"),
                version = pod["app_version"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf(String::isNotBlank) ?: error("Genbu Pod 缺少 app_version"),
                phase = pod["phase"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf(String::isNotBlank) ?: error("Genbu Pod 缺少 phase"),
                ready = pod["ready"]?.jsonPrimitive?.booleanOrNull
                    ?: error("Genbu Pod 缺少合法的 ready"),
                restartCount = pod["restart_count"]?.jsonPrimitive?.intOrNull
                    ?: error("Genbu Pod 缺少合法的 restart_count"),
            )
        } ?: error("Genbu 缺少 pods")
        check(environment.equals("PRD", ignoreCase = true)) { "Genbu 返回的不是 PRD 环境：$environment" }
        check(pods.isNotEmpty()) { "Genbu 未返回生产 Pod" }
        check(pods.all { it.ready && it.phase == "Running" }) { "生产 Pod 未全部处于 Running/Ready" }
        val restarted = pods.filter { it.restartCount != 0 }
        check(restarted.isEmpty()) {
            "生产 Pod restart_count 必须为 0：${restarted.joinToString { "${it.name}=${it.restartCount}" }}"
        }
        val versions = pods.map(ProductionPodSnapshot::version).distinct()
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
        val command = executable.resolve()
        val result = try {
            val result = runner.run(
                listOf(command, "pod", "-j", "prod", repositoryName),
                timeout = Duration.ofMinutes(2),
            )
            if (!result.succeeded) throw ProductionVersionUnavailableException(
                IllegalStateException(result.stderr.ifBlank { result.stdout }.ifBlank { "Genbu 查询失败" }),
            )
            result
        } catch (error: ProductionVersionUnavailableException) {
            throw error
        } catch (error: Throwable) {
            throw ProductionVersionUnavailableException(error)
        }
        // A successful command with unhealthy or inconsistent production data is
        // an actionable health/configuration error, not a misleading network error.
        return GenbuProductionSnapshotParser.parse(result.stdout).copy(queryCommand = command)
    }
}
