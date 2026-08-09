package com.snowball.awm.core

import java.nio.file.Path

data class DeliveryPipelineDescriptor(
    val id: String,
    val displayName: String,
    val historyDisplayName: String,
)

data class DeliveryTarget(
    val config: AppConfig,
    val taskDirectory: Path,
    val selectionKey: String,
)

data class DeliveryExecution(
    val pipelineId: String,
    val executionId: String,
    val state: String,
    val message: String? = null,
)

data class DeliveryHistoryRecord(
    val pipelineId: String,
    val executionId: String,
    val updatedAt: String,
    val taskName: String,
    val targetName: String,
    val state: String,
    val artifact: String? = null,
    val message: String? = null,
)

/** Extension point for task delivery without leaking adapter-specific configuration into the UI. */
interface DeliveryPipelineAdapter {
    val descriptor: DeliveryPipelineDescriptor
    fun execute(target: DeliveryTarget): DeliveryExecution
    fun history(config: AppConfig, tasks: List<TaskManifest>): List<DeliveryHistoryRecord>
}

class DeliveryPipelineRegistry(adapters: List<DeliveryPipelineAdapter>) {
    private val byId = adapters.associateBy { it.descriptor.id }

    init {
        require(byId.size == adapters.size) { "交付流水线 ID 不能重复" }
    }

    fun descriptors(): List<DeliveryPipelineDescriptor> = byId.values.map(DeliveryPipelineAdapter::descriptor)

    fun adapter(id: String): DeliveryPipelineAdapter? = byId[id]
}

/** Strongly typed UAT Tag implementation of the generic delivery boundary. */
class UatTagDeliveryAdapter(
    private val tags: TagBuildService = TagBuildService(),
    private val historyQuery: TagHistoryQueryService = TagHistoryQueryService(),
) : DeliveryPipelineAdapter {
    override val descriptor = DeliveryPipelineDescriptor(
        id = ID,
        displayName = "UAT Tag",
        historyDisplayName = "UAT 构建历史",
    )

    override fun execute(target: DeliveryTarget): DeliveryExecution {
        val operation = executeTag(target)
        return operation.toExecution()
    }

    fun executeTag(target: DeliveryTarget): TagOperation =
        tags.build(target.config, target.taskDirectory, target.selectionKey)

    fun executeBatch(config: AppConfig, taskDirectory: Path, selectionKeys: List<String>): List<TagOperation> =
        tags.buildBatch(config, taskDirectory, selectionKeys)

    fun historyOperations(config: AppConfig, tasks: List<TaskManifest>): List<TagOperation> =
        historyQuery.list(config, tasks)

    override fun history(config: AppConfig, tasks: List<TaskManifest>): List<DeliveryHistoryRecord> =
        historyQuery.list(config, tasks).map { operation ->
            DeliveryHistoryRecord(
                pipelineId = ID,
                executionId = operation.operationId,
                updatedAt = operation.updatedAt,
                taskName = operation.folderName,
                targetName = operation.serviceName,
                state = operation.state.name,
                artifact = operation.tag,
                message = operation.message,
            )
        }

    private fun TagOperation.toExecution() = DeliveryExecution(
        pipelineId = ID,
        executionId = operationId,
        state = state.name,
        message = message,
    )

    companion object {
        const val ID = "uat-tag"
    }
}
