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

    fun all(): List<ProductionTagPipeline> = lock.withLock { read().pipelines }

    fun get(id: String): ProductionTagPipeline? = all().firstOrNull { it.id == id }

    fun activeFor(repositoryId: String): List<ProductionTagPipeline> = all().filter {
        it.repositoryId == repositoryId && !it.closed
    }

    fun save(pipeline: ProductionTagPipeline): ProductionTagPipeline = lock.withLock {
        val current = read().pipelines
        val updated = current.filterNot { it.id == pipeline.id } + pipeline
        write(ProductionTagPipelineDocument(updated))
        pipeline
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
