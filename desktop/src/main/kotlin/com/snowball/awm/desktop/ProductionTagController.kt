package com.snowball.awm.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.snowball.awm.core.AppConfig
import com.snowball.awm.core.ProductionFeatureBatchState
import com.snowball.awm.core.ProductionBaselineChangedException
import com.snowball.awm.core.ProductionTagExpectation
import com.snowball.awm.core.ProductionTagPipeline
import com.snowball.awm.core.ProductionTagService
import com.snowball.awm.core.ProductionVersionUnavailableException

data class ProductionTagUiState(
    val pipelines: List<ProductionTagPipeline> = emptyList(),
    val selectedRepositoryId: String? = null,
    val selectedPipelineId: String? = null,
    val featureBranches: List<String> = listOf(""),
    val expectedTag: String? = null,
    val expectedTagAlreadyBuilt: Boolean = false,
    val inlineError: String? = null,
) {
    val pipeline: ProductionTagPipeline?
        get() = pipelines.firstOrNull { it.id == selectedPipelineId }
}

class ProductionTagController internal constructor(
    private val config: () -> AppConfig,
    private val service: ProductionTagService,
    private val operations: OperationRunner,
) {
    var state by mutableStateOf(run {
        val pipelines = service.all()
        val repositoryId = managedRepositories(config()).firstOrNull()?.id
        val pipeline = pipelines.firstOrNull { it.repositoryId == repositoryId && !it.closed }
        ProductionTagUiState(
            pipelines = pipelines,
            selectedRepositoryId = repositoryId,
            selectedPipelineId = pipeline?.id,
            featureBranches = pipeline?.selectedFeatures?.map { it.branch }.orEmpty().ifEmpty { listOf("") },
        )
    })
        private set

    fun selectRepository(repositoryId: String) {
        val active = state.pipelines.firstOrNull { it.repositoryId == repositoryId && !it.closed }
        state = state.copy(
            selectedRepositoryId = repositoryId,
            selectedPipelineId = active?.id,
            featureBranches = listOf(""),
            expectedTag = null,
            expectedTagAlreadyBuilt = false,
            inlineError = null,
        )
    }

    fun selectPipeline(pipelineId: String) {
        val pipeline = state.pipelines.firstOrNull { it.id == pipelineId } ?: return
        state = state.copy(
            selectedRepositoryId = pipeline.repositoryId,
            selectedPipelineId = pipelineId,
            featureBranches = pipeline.selectedFeatures.map { it.branch }.ifEmpty { listOf("") },
            expectedTag = null,
            expectedTagAlreadyBuilt = false,
            inlineError = null,
        )
        refreshExpectedTagIfReady()
    }

    fun addFeatureInput() {
        state = state.copy(featureBranches = state.featureBranches + "")
    }

    fun updateFeatureInput(index: Int, value: String) {
        state = state.copy(featureBranches = state.featureBranches.toMutableList().also { it[index] = value })
    }

    fun removeFeatureInput(index: Int) {
        val updated = state.featureBranches.toMutableList().also { it.removeAt(index) }
        state = state.copy(featureBranches = updated.ifEmpty { listOf("") })
    }

    fun moveFeatureInput(index: Int, offset: Int) {
        val target = index + offset
        if (index !in state.featureBranches.indices || target !in state.featureBranches.indices) return
        val updated = state.featureBranches.toMutableList()
        val value = updated.removeAt(index)
        updated.add(target, value)
        state = state.copy(featureBranches = updated)
    }

    fun createPipeline(): Boolean {
        val repositoryId = state.selectedRepositoryId ?: return false
        return runPipeline(
            "正在读取生产环境和 master…",
            "生产 Tag 流水线已创建",
            block = { service.create(config(), repositoryId) },
            networkFailureLabel = true,
        )
    }

    fun refreshBaseline(): Boolean = withPipeline { id ->
        runPipeline("正在刷新生产基线…", "生产基线已刷新") { service.refresh(config(), id) }
    }

    fun mergeProduction(): Boolean = withPipeline { id ->
        runPipeline("正在合并生产 Tag 到 master…", "生产基线处理完成") { service.mergeProduction(config(), id) }
    }

    fun createRelease(): Boolean = withPipeline { id ->
        runPipeline("正在创建 Release 分支…", "Release 分支已创建") { service.createRelease(config(), id) }
    }

    /** One explicit action resolves the exact remote SHAs, checks conflicts and merges when safe. */
    fun detectAndMergeFeatures(): Boolean = withPipeline { id ->
        val branches = state.featureBranches
        runPipeline("正在检测并合并 Feature…", "Feature 检测与合并已完成", resolveExpectedTag = true) {
            service.selectFeatures(config(), id, branches)
            service.mergeFeatures(config(), id)
        }
    }

    fun refreshMergeRequest(): Boolean = withPipeline { id ->
        runPipeline("正在刷新合并请求状态…", "合并请求状态已刷新", resolveExpectedTag = true) {
            service.refreshMergeRequest(config(), id)
        }
    }

    fun buildTag(): Boolean = withPipeline { id ->
        runPipeline("正在构建并推送生产 Tag…", "生产 Tag 操作完成", resolveExpectedTag = true) {
            service.buildTag(config(), id)
        }
    }

    fun closePipeline(): Boolean = withPipeline { id ->
        runPipeline("正在关闭生产 Tag 流水线…", "生产 Tag 流水线已关闭") { service.close(id) }
    }

    fun reload(): Boolean = operations.run(
        "正在刷新生产 Tag 流水线…",
        "生产 Tag 流水线已刷新",
        block = {
            val pipelines = service.all()
            val pipeline = pipelines.firstOrNull { it.id == state.selectedPipelineId }
            val expectation = if (pipeline?.releaseSha != null &&
                pipeline.featureState == ProductionFeatureBatchState.MERGED
            ) service.expectedTag(config(), pipeline.id) else null
            pipelines to expectation
        },
        onSuccess = { (pipelines, expectation) ->
            state = state.copy(
                pipelines = pipelines,
                expectedTag = expectation?.tag,
                expectedTagAlreadyBuilt = expectation is ProductionTagExpectation.AlreadyBuilt,
                inlineError = null,
            )
        },
    )

    fun refreshExpectedTag() = refreshExpectedTagIfReady()

    private fun runPipeline(
        active: String,
        success: String,
        resolveExpectedTag: Boolean = false,
        networkFailureLabel: Boolean = false,
        block: () -> ProductionTagPipeline,
    ): Boolean = operations.run(
        active,
        success,
        block = {
            val pipeline = block()
            val expectation = if (resolveExpectedTag && pipeline.releaseSha != null &&
                pipeline.featureState == ProductionFeatureBatchState.MERGED
            ) {
                service.expectedTag(config(), pipeline.id)
            } else null
            pipeline to expectation
        },
        onSuccess = { (pipeline, expectation) ->
            applyPipeline(pipeline, expectation)
        },
        onFailure = { error ->
            if (error is ProductionBaselineChangedException) applyPipeline(error.refreshed, null)
            state = state.copy(
                inlineError = if (networkFailureLabel && error is ProductionVersionUnavailableException) {
                    "网络异常"
                } else {
                    error.message ?: "操作失败"
                },
            )
        },
    )

    private fun applyPipeline(
        pipeline: ProductionTagPipeline,
        expectation: ProductionTagExpectation?,
    ) {
        val pipelines = state.pipelines.filterNot { it.id == pipeline.id } + pipeline
        state = state.copy(
            pipelines = pipelines.sortedByDescending { it.updatedAt },
            selectedRepositoryId = pipeline.repositoryId,
            selectedPipelineId = pipeline.id,
            featureBranches = pipeline.selectedFeatures.map { it.branch }.ifEmpty { state.featureBranches },
            expectedTag = expectation?.tag,
            expectedTagAlreadyBuilt = expectation is ProductionTagExpectation.AlreadyBuilt,
            inlineError = null,
        )
    }

    private fun refreshExpectedTagIfReady() {
        val pipeline = state.pipeline ?: return
        if (pipeline.releaseSha == null || pipeline.featureState != ProductionFeatureBatchState.MERGED) return
        operations.run(
            "正在计算预期生产 Tag…",
            "预期生产 Tag 已更新",
            block = { service.expectedTag(config(), pipeline.id) },
            onSuccess = {
                state = state.copy(
                    expectedTag = it.tag,
                    expectedTagAlreadyBuilt = it is ProductionTagExpectation.AlreadyBuilt,
                    inlineError = null,
                )
            },
            onFailure = { state = state.copy(inlineError = it.message ?: "计算预期 Tag 失败") },
        )
    }

    private inline fun withPipeline(block: (String) -> Boolean): Boolean =
        state.selectedPipelineId?.let(block) ?: false

    private fun managedRepositories(value: AppConfig) = value.groups
        .flatMap { it.services }
        .filter { it.enabled }
        .map { it.repositoryId }
        .distinct()
        .mapNotNull { id -> value.repositories.firstOrNull { it.id == id } }
}
