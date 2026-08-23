package com.snowball.awm.core

import java.time.Instant
import java.util.UUID

data class ProductionBaselineEvidence(
    val productionTag: String,
    val productionTagSha: String,
    val masterSha: String,
    val state: ProductionBaselineState,
)

sealed interface ProductionBranchWrite {
    data class Direct(val targetSha: String) : ProductionBranchWrite
    data class AwaitingRequest(val request: ProductionMergeRequest) : ProductionBranchWrite
}

sealed interface ProductionFeatureWrite {
    data class Direct(
        val releaseSha: String,
        val merges: List<ProductionFeatureMergeRecord>,
    ) : ProductionFeatureWrite

    data class AwaitingRequest(
        val releaseSha: String,
        val merges: List<ProductionFeatureMergeRecord>,
        val request: ProductionMergeRequest,
    ) : ProductionFeatureWrite

    data class Conflict(val conflicts: List<ProductionConflict>) : ProductionFeatureWrite
}

sealed interface ProductionTagPush {
    data class Pushed(val tagSha: String) : ProductionTagPush
    data class NoPermission(val reason: String) : ProductionTagPush
    data class Failed(val reason: String) : ProductionTagPush
    data class AlreadyExists(val tagSha: String?) : ProductionTagPush
}

interface ProductionGitGateway {
    fun inspectBaseline(repository: RepositoryConfig, productionTag: String): ProductionBaselineEvidence
    fun formalTags(repository: RepositoryConfig): List<String>
    fun mergeProduction(repository: RepositoryConfig, pipeline: ProductionTagPipeline): ProductionBranchWrite
    fun createRelease(repository: RepositoryConfig, branch: String, masterSha: String): String
    fun resolveFeatures(repository: RepositoryConfig, branches: List<String>): List<ProductionFeatureSelection>
    fun mergeFeatures(
        repository: RepositoryConfig,
        pipeline: ProductionTagPipeline,
        features: List<ProductionFeatureSelection>,
    ): ProductionFeatureWrite
    fun tagsForBase(repository: RepositoryConfig, baseVersion: String): List<ProductionRemoteTag>
    fun pushTag(
        repository: RepositoryConfig,
        releaseBranch: String,
        tag: String,
        releaseSha: String,
    ): ProductionTagPush
    /** Returns the current remote target SHA only when it contains the expected commit. */
    fun mergedTargetSha(repository: RepositoryConfig, targetBranch: String, expectedCommit: String): String?
}

class ProductionBaselineChangedException(
    val refreshed: ProductionTagPipeline,
) : IllegalStateException("生产环境或 master 已发生变化，旧操作已取消，请核对最新基线后重试")

class ProductionTagService(
    private val store: ProductionTagPipelineStore = ProductionTagPipelineStore(),
    private val versions: ProductionVersionProvider,
    private val git: ProductionGitGateway,
    private val now: () -> String = { AwmTime.format(Instant.now()) },
    private val id: () -> String = { UUID.randomUUID().toString() },
) {
    fun all(): List<ProductionTagPipeline> = store.all()

    fun activeFor(repositoryId: String): List<ProductionTagPipeline> = store.activeFor(repositoryId)

    fun get(id: String): ProductionTagPipeline = store.get(id) ?: error("找不到生产 Tag 流水线：$id")

    fun create(config: AppConfig, repositoryId: String): ProductionTagPipeline {
        check(config.productionTagBuildEnabled) { "生产 Tag 构建功能已关闭" }
        val repository = repository(config, repositoryId)
        val runtime = versions.current(repository.name)
        val evidence = git.inspectBaseline(repository, runtime.version)
        val baseVersion = ProductionTagVersioning.nextBase(git.formalTags(repository))
        val timestamp = now()
        return store.create(
            ProductionTagPipeline(
                id = id(),
                repositoryId = repository.id,
                serviceName = repository.name,
                productionTag = evidence.productionTag,
                productionTagSha = evidence.productionTagSha,
                masterSha = evidence.masterSha,
                baselineState = evidence.state,
                baseVersion = baseVersion,
                releaseBranch = "release/$baseVersion",
                createdAt = timestamp,
                updatedAt = timestamp,
            ),
        )
    }

    fun refresh(config: AppConfig, pipelineId: String): ProductionTagPipeline {
        val current = get(pipelineId)
        requireOpen(current)
        check(current.releaseSha == null) { "Release 已创建，生产基线不可再刷新" }
        check(current.mergeRequest == null) { "当前有待处理的合并请求，请使用“刷新合并状态”" }
        val repository = repository(config, current.repositoryId)
        val runtime = versions.current(repository.name)
        val evidence = git.inspectBaseline(repository, runtime.version)
        return save(current.copy(
            productionTag = evidence.productionTag,
            productionTagSha = evidence.productionTagSha,
            masterSha = evidence.masterSha,
            baselineState = evidence.state,
            mergeRequest = null,
        ))
    }

    fun mergeProduction(config: AppConfig, pipelineId: String): ProductionTagPipeline {
        val current = revalidateBaseline(config, get(pipelineId))
        requireOpen(current)
        check(current.baselineState == ProductionBaselineState.MERGE_REQUIRED) { "当前生产 Tag 无需合并" }
        return when (val outcome = git.mergeProduction(repository(config, current.repositoryId), current)) {
            is ProductionBranchWrite.Direct -> save(current.copy(
                masterSha = outcome.targetSha,
                baselineState = ProductionBaselineState.ALREADY_CONTAINED,
                mergeRequest = null,
            ))
            is ProductionBranchWrite.AwaitingRequest -> save(current.copy(
                baselineState = ProductionBaselineState.AWAITING_MERGE_REQUEST,
                mergeRequest = outcome.request,
            ))
        }
    }

    fun createRelease(config: AppConfig, pipelineId: String): ProductionTagPipeline {
        val current = revalidateBaseline(config, get(pipelineId))
        requireOpen(current)
        check(current.baselineState == ProductionBaselineState.ALREADY_CONTAINED) { "生产基线尚未进入 master" }
        check(current.releaseSha == null) { "Release 已创建" }
        val sha = git.createRelease(repository(config, current.repositoryId), current.releaseBranch, current.masterSha)
        return save(current.copy(releaseSha = sha))
    }

    fun selectFeatures(config: AppConfig, pipelineId: String, branches: List<String>): ProductionTagPipeline {
        val current = get(pipelineId)
        requireOpen(current)
        check(current.mergeRequest == null) { "当前有待处理的合并请求，请先刷新合并状态" }
        check(current.releaseSha != null) { "请先创建 Release" }
        val normalized = branches.map(String::trim).filter(String::isNotBlank)
        check(normalized.isNotEmpty()) { "至少选择一个 Feature 分支" }
        check(normalized.distinct().size == normalized.size) { "Feature 分支不能重复" }
        val resolved = git.resolveFeatures(repository(config, current.repositoryId), normalized)
        check(resolved.map { it.branch } == normalized) { "Feature 分支解析结果与用户顺序不一致" }
        return save(current.copy(
            selectedFeatures = resolved,
            featureState = ProductionFeatureBatchState.IDLE,
            conflicts = emptyList(),
            mergeRequest = null,
        ))
    }

    fun mergeFeatures(config: AppConfig, pipelineId: String): ProductionTagPipeline {
        val current = get(pipelineId)
        requireOpen(current)
        check(current.releaseSha != null) { "请先创建 Release" }
        check(current.selectedFeatures.isNotEmpty()) { "至少选择一个 Feature 分支" }
        return when (val outcome = git.mergeFeatures(repository(config, current.repositoryId), current, current.selectedFeatures)) {
            is ProductionFeatureWrite.Conflict -> save(current.copy(
                featureState = ProductionFeatureBatchState.CONFLICT,
                conflicts = outcome.conflicts,
                mergeRequest = null,
            ))
            is ProductionFeatureWrite.Direct -> save(current.copy(
                releaseSha = outcome.releaseSha,
                mergedFeatures = current.mergedFeatures + outcome.merges,
                featureState = ProductionFeatureBatchState.MERGED,
                conflicts = emptyList(),
                mergeRequest = null,
            ))
            is ProductionFeatureWrite.AwaitingRequest -> save(current.copy(
                mergedFeatures = current.mergedFeatures + outcome.merges,
                featureState = ProductionFeatureBatchState.AWAITING_MERGE_REQUEST,
                conflicts = emptyList(),
                mergeRequest = outcome.request,
            ))
        }
    }

    fun refreshMergeRequest(config: AppConfig, pipelineId: String): ProductionTagPipeline {
        val current = get(pipelineId)
        requireOpen(current)
        val request = current.mergeRequest ?: error("当前没有等待中的合并请求")
        val repository = repository(config, current.repositoryId)
        val targetSha = git.mergedTargetSha(repository, request.targetBranch, request.expectedCommit) ?: return current
        return if (request.targetBranch == "master") {
            save(current.copy(
                masterSha = targetSha,
                baselineState = ProductionBaselineState.ALREADY_CONTAINED,
                mergeRequest = null,
            ))
        } else {
            save(current.copy(
                releaseSha = targetSha,
                featureState = ProductionFeatureBatchState.MERGED,
                mergeRequest = null,
            ))
        }
    }

    fun buildTag(config: AppConfig, pipelineId: String): ProductionTagPipeline {
        var current = get(pipelineId)
        requireOpen(current)
        val releaseSha = current.releaseSha ?: error("Release 尚未创建")
        check(current.featureState == ProductionFeatureBatchState.MERGED) { "Feature 尚未成功合入 Release" }
        val repository = repository(config, current.repositoryId)
        val expectation = ProductionTagVersioning.expectation(
            current.baseVersion,
            releaseSha,
            git.tagsForBase(repository, current.baseVersion),
        )
        if (expectation is ProductionTagExpectation.AlreadyBuilt) {
            val pendingIndex = current.buildRecords.indexOfLast {
                it.expectedTag == expectation.tag && it.releaseSha == releaseSha &&
                    it.state in setOf(ProductionTagBuildState.PREPARING, ProductionTagBuildState.PUSHING)
            }
            val record = ProductionTagBuildRecord(
                expectation.tag,
                releaseSha,
                ProductionTagBuildState.ALREADY_EXISTS,
                remoteTagSha = releaseSha,
                startedAt = current.buildRecords.getOrNull(pendingIndex)?.startedAt ?: now(),
                completedAt = now(),
            )
            return if (pendingIndex >= 0) save(current.copy(
                buildRecords = current.buildRecords.toMutableList().also { it[pendingIndex] = record },
            )) else save(current.copy(buildRecords = current.buildRecords + record))
        }
        val tag = expectation.tag
        val started = now()
        current = save(current.copy(buildRecords = current.buildRecords + ProductionTagBuildRecord(
            tag,
            releaseSha,
            ProductionTagBuildState.PREPARING,
            startedAt = started,
        )))
        current = save(current.copy(buildRecords = current.buildRecords.dropLast(1) +
            current.buildRecords.last().copy(state = ProductionTagBuildState.PUSHING)))
        val record = when (val push = git.pushTag(repository, current.releaseBranch, tag, releaseSha)) {
            is ProductionTagPush.Pushed -> ProductionTagBuildRecord(
                tag, releaseSha, ProductionTagBuildState.PUSHED, push.tagSha, started, now(),
            )
            is ProductionTagPush.NoPermission -> ProductionTagBuildRecord(
                tag, releaseSha, ProductionTagBuildState.NO_PERMISSION, startedAt = started, completedAt = now(), failureReason = push.reason,
            )
            is ProductionTagPush.AlreadyExists -> ProductionTagBuildRecord(
                tag, releaseSha, ProductionTagBuildState.ALREADY_EXISTS, push.tagSha, started, now(),
            )
            is ProductionTagPush.Failed -> ProductionTagBuildRecord(
                tag, releaseSha, ProductionTagBuildState.FAILED, startedAt = started, completedAt = now(), failureReason = push.reason,
            )
        }
        return save(current.copy(buildRecords = current.buildRecords.dropLast(1) + record))
    }

    fun expectedTag(config: AppConfig, pipelineId: String): ProductionTagExpectation {
        val current = get(pipelineId)
        val releaseSha = current.releaseSha ?: error("Release 尚未创建")
        return ProductionTagVersioning.expectation(
            current.baseVersion,
            releaseSha,
            git.tagsForBase(repository(config, current.repositoryId), current.baseVersion),
        )
    }

    fun close(pipelineId: String): ProductionTagPipeline {
        val current = get(pipelineId)
        return save(current.copy(closed = true))
    }

    private fun save(pipeline: ProductionTagPipeline): ProductionTagPipeline =
        store.save(pipeline.copy(updatedAt = now()))

    private fun repository(config: AppConfig, id: String): RepositoryConfig {
        val managedRepositoryIds = config.groups
            .flatMap { it.services }
            .filter { it.enabled }
            .map { it.repositoryId }
            .toSet()
        check(id in managedRepositoryIds) { "该仓库不是 AWM 已启用的服务：$id" }
        return config.repositories.firstOrNull { it.id == id } ?: error("找不到仓库配置：$id")
    }

    private fun revalidateBaseline(config: AppConfig, pipeline: ProductionTagPipeline): ProductionTagPipeline {
        requireOpen(pipeline)
        check(pipeline.mergeRequest == null) { "当前有待处理的合并请求，请使用“刷新合并状态”" }
        val repository = repository(config, pipeline.repositoryId)
        val runtime = versions.current(repository.name)
        val evidence = git.inspectBaseline(repository, runtime.version)
        val changed = evidence.productionTag != pipeline.productionTag ||
            evidence.productionTagSha != pipeline.productionTagSha ||
            evidence.masterSha != pipeline.masterSha ||
            evidence.state != pipeline.baselineState
        if (!changed) return pipeline
        val refreshed = save(pipeline.copy(
            productionTag = evidence.productionTag,
            productionTagSha = evidence.productionTagSha,
            masterSha = evidence.masterSha,
            baselineState = evidence.state,
            mergeRequest = null,
        ))
        throw ProductionBaselineChangedException(refreshed)
    }

    private fun requireOpen(pipeline: ProductionTagPipeline) {
        check(!pipeline.closed) { "生产 Tag 流水线已关闭" }
    }
}
