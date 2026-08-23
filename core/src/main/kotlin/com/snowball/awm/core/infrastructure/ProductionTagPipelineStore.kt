package com.snowball.awm.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.createDirectories

@Serializable
private data class ProductionTagPipelineDocument(
    val pipelines: List<ProductionTagPipeline> = emptyList(),
)

class ProductionTagPipelineStore(
    private val paths: ApplicationPaths = ApplicationPaths.systemDefault(),
    private val json: Json = Json { prettyPrint = true; encodeDefaults = true },
) {
    private val lock = ReentrantLock()
    private val fileLock = paths.locks.resolve("production-tag-pipelines.lock")

    fun all(): List<ProductionTagPipeline> = lock.withLock { read().pipelines }

    fun get(id: String): ProductionTagPipeline? = all().firstOrNull { it.id == id }

    fun activeFor(repositoryId: String): List<ProductionTagPipeline> = all().filter {
        it.repositoryId == repositoryId && !it.closed
    }

    fun create(pipeline: ProductionTagPipeline): ProductionTagPipeline = mutate { current ->
        check(current.none { it.repositoryId == pipeline.repositoryId && !it.closed }) {
            "该服务已有进行中的生产 Tag 流水线"
        }
        check(current.none { it.id == pipeline.id }) { "生产 Tag 流水线 ID 已存在：${pipeline.id}" }
        val created = pipeline.copy(revision = 1)
        (current + created) to created
    }

    fun save(pipeline: ProductionTagPipeline): ProductionTagPipeline = mutate { current ->
        val persisted = current.firstOrNull { it.id == pipeline.id }
            ?: error("找不到生产 Tag 流水线：${pipeline.id}")
        check(persisted.revision == pipeline.revision) {
            "生产 Tag 流水线已被另一个 AWM 实例更新，请刷新后重试"
        }
        val updated = pipeline.copy(revision = pipeline.revision + 1)
        (current.filterNot { it.id == pipeline.id } + updated) to updated
    }

    /** Held for the full remote-write/recovery window; the OS releases it after a process crash. */
    fun <T> withOperationLock(pipelineId: String, block: () -> T): T = FileLocking.withExclusiveLock(
        paths.locks.resolve("production-tag-operation-${FileLocking.stableTextHash(pipelineId)}.lock"),
        "该生产 Tag 操作仍在另一个 AWM 实例执行，请稍后重试",
        block,
    )

    private fun <T> mutate(transform: (List<ProductionTagPipeline>) -> Pair<List<ProductionTagPipeline>, T>): T = lock.withLock {
        FileLocking.withExclusiveLock(fileLock, "生产 Tag 流水线正在被另一个 AWM 实例更新") {
            val (pipelines, result) = transform(read().pipelines)
            write(ProductionTagPipelineDocument(pipelines))
            result
        }
    }

    private fun read(): ProductionTagPipelineDocument {
        if (!Files.exists(paths.productionTagPipelines)) return ProductionTagPipelineDocument()
        return json.decodeFromString(Files.readString(paths.productionTagPipelines))
    }

    private fun write(document: ProductionTagPipelineDocument) {
        paths.home.createDirectories()
        val temporary = Files.createTempFile(paths.home, ".production-tag-", ".json.tmp")
        Files.writeString(temporary, json.encodeToString(document))
        try {
            Files.move(
                temporary,
                paths.productionTagPipelines,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, paths.productionTagPipelines, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
