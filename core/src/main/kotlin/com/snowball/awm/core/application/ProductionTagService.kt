package com.snowball.awm.core

import java.time.Instant
import java.util.UUID

data class ProductionBaselineEvidence(
    val productionTag: String,
    val productionTagSha: String,
    val masterSha: String,
    val state: ProductionBaselineState,
)

data class ProductionTagConfirmation(
    val expectation: ProductionTagExpectation,
    val releaseSha: String,
    val pipelineRevision: Long,
) {
    val tag: String get() = expectation.tag
}

/** A pipeline and its optional Tag confirmation read from one optimistic snapshot. */
data class ProductionTagView(
    val pipeline: ProductionTagPipeline,
    val confirmation: ProductionTagConfirmation?,
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
    fun validateRepository(repository: RepositoryConfig)
    fun operator(repository: RepositoryConfig): String?
    fun inspectBaseline(repository: RepositoryConfig, productionTag: String): ProductionBaselineEvidence
    fun formalTags(repository: RepositoryConfig): List<String>
    fun releaseHead(repository: RepositoryConfig, branch: String): String?
    fun mergeProduction(repository: RepositoryConfig, pipeline: ProductionTagPipeline): ProductionBranchWrite
    fun recoverProductionWrite(
        repository: RepositoryConfig,
        targetBranch: String,
        beforeSha: String,
        productionSha: String,
        sourceBranch: String?,
    ): ProductionBranchWrite?
    fun createRelease(repository: RepositoryConfig, pipeline: ProductionTagPipeline): String
    fun resolveFeatures(repository: RepositoryConfig, branches: List<String>): List<ProductionFeatureSelection>
    fun mergeFeatures(
        repository: RepositoryConfig,
        pipeline: ProductionTagPipeline,
        features: List<ProductionFeatureSelection>,
    ): ProductionFeatureWrite
    fun tagsForBase(repository: RepositoryConfig, baseVersion: String): List<ProductionRemoteTag>
    fun recoverFeatureWrite(
        repository: RepositoryConfig,
        releaseBranch: String,
        sourceBranch: String?,
        beforeSha: String,
        features: List<ProductionFeatureSelection>,
    ): ProductionFeatureWrite?
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

class ProductionNoPushPermissionException : IllegalStateException("无推送权限")

class ProductionFeatureTopologyException(message: String) : IllegalStateException(message)

class ProductionMergeRequestUnavailableException :
    IllegalStateException("无合并权限，无法生成合并请求链接")

class ProductionTagConfirmationChangedException :
    IllegalStateException("预期 Tag 或 Release SHA 已变化，旧确认已取消，请核对页面最新值后重新点击")

class ProductionTagService(
    private val store: ProductionTagPipelineStore = ProductionTagPipelineStore(),
    private val versions: ProductionVersionProvider,
    private val git: ProductionGitGateway,
    private val now: () -> String = { AwmTime.format(Instant.now()) },
    private val id: () -> String = { UUID.randomUUID().toString() },
    /** Test/recovery seam invoked after a remote write returns and before its final local commit. */
    private val afterRemoteWrite: (ProductionOperationAction) -> Unit = {},
) {
    fun all(): List<ProductionTagPipeline> = store.all()

    fun activeFor(repositoryId: String): List<ProductionTagPipeline> = store.activeFor(repositoryId)

    fun get(id: String): ProductionTagPipeline = store.get(id) ?: error("找不到生产 Tag 流水线：$id")

    fun create(config: AppConfig, repositoryId: String): ProductionTagPipeline {
        check(config.productionTagBuildEnabled) { "生产 Tag 构建功能已关闭" }
        val repository = repository(config, repositoryId)
        check(store.activeFor(repositoryId).isEmpty()) { "该服务已有进行中的生产 Tag 流水线，请点击继续" }
        git.validateRepository(repository)
        val runtime = versions.current(repository.name)
        val evidence = git.inspectBaseline(repository, runtime.version)
        val baseVersion = ProductionTagVersioning.nextBase(git.formalTags(repository))
        val releaseBranch = "release/$baseVersion"
        val unmanagedReleaseSha = git.releaseHead(repository, releaseBranch)
        val timestamp = now()
        val draft = ProductionTagPipeline(
                id = id(),
                repositoryId = repository.id,
                serviceName = repository.name,
                productionTag = evidence.productionTag,
                productionTagSha = evidence.productionTagSha,
                masterSha = evidence.masterSha,
                baselineState = evidence.state,
                baseVersion = baseVersion,
                releaseBranch = releaseBranch,
                createdAt = timestamp,
                updatedAt = timestamp,
                productionService = runtime.service,
                productionEnvironment = runtime.environment,
                productionPods = runtime.pods,
                baselineCheckedAt = timestamp,
                unmanagedReleaseSha = unmanagedReleaseSha,
                genbuCommand = runtime.queryCommand,
                operator = git.operator(repository).orEmpty(),
            )
        return store.create(draft.copy(
            auditEvents = listOf(auditEvent(
                pipeline = draft,
                operationId = id(),
                action = "BASELINE_CHECK",
                state = if (unmanagedReleaseSha == null) ProductionAuditState.SUCCEEDED else ProductionAuditState.FAILED,
                startedAt = timestamp,
                completedAt = timestamp,
                reason = unmanagedReleaseSha?.let { "存在未接管的 Release：$releaseBranch@$it" },
                repository = repository,
            )),
        ))
    }

    fun refresh(config: AppConfig, pipelineId: String): ProductionTagPipeline {
        val current = get(pipelineId)
        requireOpen(current)
        requireIdle(current)
        check(current.releaseSha == null) { "Release 已创建，生产基线不可再刷新" }
        check(current.mergeRequest == null) { "当前有待处理的合并请求，请使用“刷新合并状态”" }
        return refreshBaseline(config, current)
    }

    fun resume(config: AppConfig, pipelineId: String): ProductionTagPipeline = store.withOperationLock(pipelineId) {
        val current = get(pipelineId)
        requireOpen(current)
        if (current.activeOperation != null) return@withOperationLock reconcileOperation(config, current)
        if (current.releaseSha == null) return@withOperationLock refresh(config, pipelineId)
        val repository = repository(config, current.repositoryId)
        git.validateRepository(repository)
        val remoteRelease = git.releaseHead(repository, current.releaseBranch)
            ?: error("远端 Release 分支不存在：${current.releaseBranch}")
        check(remoteRelease == current.releaseSha) {
            "远端 Release 已在 AWM 外发生变化，请刷新相关合并状态或重新核对后再继续"
        }
        val expectation = ProductionTagVersioning.expectation(
            current.baseVersion,
            remoteRelease,
            git.tagsForBase(repository, current.baseVersion),
        )
        val pendingIndex = current.buildRecords.indexOfLast {
            it.releaseSha == remoteRelease && it.state in
                setOf(ProductionTagBuildState.PREPARING, ProductionTagBuildState.PUSHING)
        }
        if (pendingIndex < 0) return@withOperationLock current
        val pending = current.buildRecords[pendingIndex]
        val recovered = if (expectation is ProductionTagExpectation.AlreadyBuilt && expectation.tag == pending.expectedTag) {
            pending.copy(
                state = ProductionTagBuildState.ALREADY_EXISTS,
                remoteTagSha = remoteRelease,
                completedAt = now(),
                remoteUrl = auditRemote(repository),
                actualTag = expectation.tag,
            )
        } else {
            pending.copy(
                state = ProductionTagBuildState.FAILED,
                completedAt = now(),
                failureReason = "AWM 上次操作中断；远端未出现该 Tag，已安全结束旧记录",
                remoteUrl = auditRemote(repository),
            )
        }
        save(current.copy(
            buildRecords = current.buildRecords.toMutableList().also { it[pendingIndex] = recovered },
        ))
    }

    fun mergeProduction(config: AppConfig, pipelineId: String): ProductionTagPipeline = store.withOperationLock(pipelineId) {
        val current = revalidateBaseline(config, get(pipelineId))
        requireOpen(current)
        check(current.unmanagedReleaseSha == null) { "存在未接管的 Release，不能继续写入" }
        check(current.baselineState == ProductionBaselineState.MERGE_REQUIRED) { "当前生产 Tag 无需合并" }
        val repository = repository(config, current.repositoryId)
        val leased = beginOperation(current, repository, ProductionOperationAction.MERGE_PRODUCTION)
        try {
            val outcome = git.mergeProduction(repository, leased)
            afterRemoteWrite(ProductionOperationAction.MERGE_PRODUCTION)
            when (outcome) {
                is ProductionBranchWrite.Direct -> finishOperation(leased.copy(
                    masterSha = outcome.targetSha,
                    baselineState = ProductionBaselineState.ALREADY_CONTAINED,
                    mergeRequest = null,
                ), ProductionAuditState.SUCCEEDED)
                is ProductionBranchWrite.AwaitingRequest -> finishOperation(leased.copy(
                    baselineState = ProductionBaselineState.AWAITING_MERGE_REQUEST,
                    mergeRequest = outcome.request,
                ), ProductionAuditState.AWAITING_MERGE_REQUEST, mergeRequestUrl = outcome.request.url)
            }
        } catch (error: Throwable) {
            finishKnownFailure(leased, error)
            throw error
        }
    }

    fun createRelease(config: AppConfig, pipelineId: String): ProductionTagPipeline = store.withOperationLock(pipelineId) {
        val current = revalidateBaseline(config, get(pipelineId))
        requireOpen(current)
        check(current.unmanagedReleaseSha == null) { "存在未接管的 Release，不能创建或复用该分支" }
        check(current.baselineState == ProductionBaselineState.ALREADY_CONTAINED) { "生产基线尚未进入 master" }
        check(current.releaseSha == null) { "Release 已创建" }
        val repository = repository(config, current.repositoryId)
        val leased = beginOperation(
            current,
            repository,
            ProductionOperationAction.CREATE_RELEASE,
            expectedTargetSha = current.masterSha,
        )
        try {
            val sha = git.createRelease(repository, leased)
            afterRemoteWrite(ProductionOperationAction.CREATE_RELEASE)
            finishOperation(leased.copy(releaseSha = sha), ProductionAuditState.SUCCEEDED)
        } catch (error: Throwable) {
            finishKnownFailure(leased, error)
            throw error
        }
    }

    fun selectFeatures(config: AppConfig, pipelineId: String, branches: List<String>): ProductionTagPipeline {
        val current = get(pipelineId)
        requireOpen(current)
        requireIdle(current)
        check(current.mergeRequest == null) { "当前有待处理的合并请求，请先刷新合并状态" }
        check(current.releaseSha != null) { "请先创建 Release" }
        val normalized = branches.map(String::trim).filter(String::isNotBlank)
        check(normalized.isNotEmpty()) { "至少选择一个 Feature 分支" }
        check(normalized.distinct().size == normalized.size) { "Feature 分支不能重复" }
        val resolved = git.resolveFeatures(repository(config, current.repositoryId), normalized)
        check(resolved.map { it.branch } == normalized) { "Feature 分支解析结果与用户顺序不一致" }
        val mergedByBranch = current.mergedFeatures.associate { it.branch to it.sourceSha }
        val pending = resolved.filter { mergedByBranch[it.branch] != it.sha }
        check(pending.isNotEmpty()) { "所选 Feature SHA 均已合入，请选择新增或已更新的 Feature" }
        return save(current.copy(
            selectedFeatures = pending,
            featureState = ProductionFeatureBatchState.IDLE,
            conflicts = emptyList(),
            mergeRequest = null,
        ))
    }

    fun mergeFeatures(config: AppConfig, pipelineId: String): ProductionTagPipeline = store.withOperationLock(pipelineId) {
        val current = get(pipelineId)
        requireOpen(current)
        requireIdle(current)
        check(current.releaseSha != null) { "请先创建 Release" }
        check(current.selectedFeatures.isNotEmpty()) { "至少选择一个 Feature 分支" }
        val repository = repository(config, current.repositoryId)
        val leased = beginOperation(
            current,
            repository,
            ProductionOperationAction.MERGE_FEATURES,
            expectedTargetSha = current.releaseSha,
            features = current.selectedFeatures,
        )
        try {
            val outcome = git.mergeFeatures(repository, leased, leased.selectedFeatures)
            if (outcome !is ProductionFeatureWrite.Conflict) afterRemoteWrite(ProductionOperationAction.MERGE_FEATURES)
            when (outcome) {
                is ProductionFeatureWrite.Conflict -> finishOperation(leased.copy(
                    featureState = ProductionFeatureBatchState.CONFLICT,
                    conflicts = outcome.conflicts,
                    mergeRequest = null,
                ), ProductionAuditState.CONFLICT, outcome.conflicts.joinToString { conflict ->
                    "${conflict.branch}: ${conflict.files.ifEmpty { listOf("未读取到冲突文件") }.joinToString()}"
                })
                is ProductionFeatureWrite.Direct -> finishOperation(leased.copy(
                    releaseSha = outcome.releaseSha,
                    mergedFeatures = leased.mergedFeatures + outcome.merges,
                    selectedFeatures = emptyList(),
                    featureState = ProductionFeatureBatchState.MERGED,
                    conflicts = emptyList(),
                    mergeRequest = null,
                ), ProductionAuditState.SUCCEEDED)
                is ProductionFeatureWrite.AwaitingRequest -> finishOperation(leased.copy(
                    mergedFeatures = leased.mergedFeatures + outcome.merges,
                    featureState = ProductionFeatureBatchState.AWAITING_MERGE_REQUEST,
                    conflicts = emptyList(),
                    mergeRequest = outcome.request,
                ), ProductionAuditState.AWAITING_MERGE_REQUEST, mergeRequestUrl = outcome.request.url)
            }
        } catch (error: Throwable) {
            finishKnownFailure(leased, error)
            throw error
        }
    }

    fun refreshMergeRequest(config: AppConfig, pipelineId: String): ProductionTagPipeline {
        val current = get(pipelineId)
        requireOpen(current)
        requireIdle(current)
        val request = current.mergeRequest ?: error("当前没有等待中的合并请求")
        val repository = repository(config, current.repositoryId)
        val targetSha = git.mergedTargetSha(repository, request.targetBranch, request.expectedCommit) ?: return current
        val auditIndex = current.auditEvents.indexOfLast { it.state == ProductionAuditState.AWAITING_MERGE_REQUEST }
        val audited = if (auditIndex >= 0) current.auditEvents.toMutableList().also { events ->
            events[auditIndex] = events[auditIndex].copy(
                state = ProductionAuditState.SUCCEEDED,
                completedAt = now(),
                reason = "已确认目标分支包含合并请求预期提交：$targetSha",
                releaseSha = if (request.targetBranch == "master") current.releaseSha else targetSha,
                masterSha = if (request.targetBranch == "master") targetSha else current.masterSha,
            )
        } else current.auditEvents
        return if (request.targetBranch == "master") {
            save(current.copy(
                masterSha = targetSha,
                baselineState = ProductionBaselineState.ALREADY_CONTAINED,
                mergeRequest = null,
                auditEvents = audited,
            ))
        } else {
            save(current.copy(
                releaseSha = targetSha,
                selectedFeatures = emptyList(),
                featureState = ProductionFeatureBatchState.MERGED,
                mergeRequest = null,
                auditEvents = audited,
            ))
        }
    }

    fun buildTag(
        config: AppConfig,
        pipelineId: String,
        confirmedTag: String,
        confirmedReleaseSha: String,
        confirmedPipelineRevision: Long,
    ): ProductionTagPipeline = store.withOperationLock(pipelineId) {
        var current = get(pipelineId)
        val currentReleaseSha = current.releaseSha
        if (current.revision != confirmedPipelineRevision || currentReleaseSha != confirmedReleaseSha) {
            cancelTagBuild(
                current.id,
                confirmedTag,
                confirmedReleaseSha,
            ) { latest ->
                "页面确认已失效：页面 revision=$confirmedPipelineRevision、Release=$confirmedReleaseSha；" +
                    "当前 revision=${latest.revision}、Release=${latest.releaseSha ?: "—"}"
            }
        }
        requireOpen(current)
        requireIdle(current)
        val releaseSha = currentReleaseSha
        check(current.featureState == ProductionFeatureBatchState.MERGED) { "Feature 尚未成功合入 Release" }
        val repository = repository(config, current.repositoryId)
        val expectation = ProductionTagVersioning.expectation(
            current.baseVersion,
            releaseSha,
            git.tagsForBase(repository, current.baseVersion),
        )
        if (expectation !is ProductionTagExpectation.Create || expectation.tag != confirmedTag) {
            cancelTagBuild(
                current.id,
                confirmedTag,
                confirmedReleaseSha,
            ) { "页面预期 Tag 已失效：页面=$confirmedTag；检查结果=${expectation.tag}（${expectation::class.simpleName}）" }
        }
        val tag = expectation.tag
        val started = now()
        current = try {
            beginOperation(current.copy(buildRecords = current.buildRecords + ProductionTagBuildRecord(
                tag,
                releaseSha,
                ProductionTagBuildState.PREPARING,
                startedAt = started,
                remoteUrl = auditRemote(repository),
            )), repository, ProductionOperationAction.BUILD_TAG,
                expectedTag = tag,
                expectedTargetSha = releaseSha,
                features = mergedFeatureSelections(current),
            )
        } catch (_: ProductionTagPipelineChangedException) {
            cancelTagBuild(current.id, confirmedTag, confirmedReleaseSha) { latest ->
                "流水线在 Tag 操作登记前发生变化：页面 revision=$confirmedPipelineRevision；当前 revision=${latest.revision}"
            }
        }
        current = save(current.copy(buildRecords = current.buildRecords.dropLast(1) +
            current.buildRecords.last().copy(state = ProductionTagBuildState.PUSHING)))
        val push = try {
            git.pushTag(repository, current.releaseBranch, tag, releaseSha)
        } catch (error: Throwable) {
            throw error
        }
        val record = when (push) {
            is ProductionTagPush.Pushed -> ProductionTagBuildRecord(
                tag, releaseSha, ProductionTagBuildState.PUSHED, push.tagSha, started, now(), remoteUrl = auditRemote(repository), actualTag = tag,
            )
            is ProductionTagPush.NoPermission -> ProductionTagBuildRecord(
                tag, releaseSha, ProductionTagBuildState.NO_PERMISSION, startedAt = started, completedAt = now(), failureReason = push.reason,
                remoteUrl = auditRemote(repository),
            )
            is ProductionTagPush.AlreadyExists -> ProductionTagBuildRecord(
                tag, releaseSha, ProductionTagBuildState.ALREADY_EXISTS, push.tagSha, started, now(), remoteUrl = auditRemote(repository), actualTag = tag,
            )
            is ProductionTagPush.Failed -> ProductionTagBuildRecord(
                tag, releaseSha, ProductionTagBuildState.FAILED, startedAt = started, completedAt = now(), failureReason = push.reason,
                remoteUrl = auditRemote(repository),
            )
        }
        if (push is ProductionTagPush.Pushed) afterRemoteWrite(ProductionOperationAction.BUILD_TAG)
        val state = when (record.state) {
            ProductionTagBuildState.PUSHED -> ProductionAuditState.SUCCEEDED
            ProductionTagBuildState.ALREADY_EXISTS -> ProductionAuditState.RECOVERED
            else -> ProductionAuditState.FAILED
        }
        finishOperation(
            current.copy(buildRecords = current.buildRecords.dropLast(1) + record),
            state,
            record.failureReason,
        )
    }

    fun tagView(config: AppConfig, pipelineId: String): ProductionTagView {
        repeat(3) {
            val snapshot = get(pipelineId)
            val confirmation = if (
                !snapshot.closed && snapshot.releaseSha != null &&
                snapshot.featureState == ProductionFeatureBatchState.MERGED &&
                snapshot.activeOperation == null
            ) {
                val expectation = ProductionTagVersioning.expectation(
                    snapshot.baseVersion,
                    snapshot.releaseSha,
                    git.tagsForBase(repository(config, snapshot.repositoryId), snapshot.baseVersion),
                )
                ProductionTagConfirmation(expectation, snapshot.releaseSha, snapshot.revision)
            } else null
            if (get(pipelineId).revision == snapshot.revision) {
                return ProductionTagView(snapshot, confirmation)
            }
        }
        throw ProductionTagConfirmationChangedException()
    }

    fun tagConfirmation(config: AppConfig, pipelineId: String): ProductionTagConfirmation =
        tagView(config, pipelineId).confirmation ?: error("当前流水线尚不能构建生产 Tag")

    fun expectedTag(config: AppConfig, pipelineId: String): ProductionTagExpectation =
        tagConfirmation(config, pipelineId).expectation

    fun close(pipelineId: String): ProductionTagPipeline {
        val current = get(pipelineId)
        requireOpen(current)
        requireIdle(current)
        return save(current.copy(closed = true))
    }

    private fun save(pipeline: ProductionTagPipeline): ProductionTagPipeline =
        store.save(pipeline.copy(updatedAt = now()))

    private fun auditRemote(repository: RepositoryConfig): String? =
        GitAuditSanitizer.remoteDisplay(repository.originUrl)

    private fun mergedFeatureSelections(pipeline: ProductionTagPipeline): List<ProductionFeatureSelection> =
        pipeline.mergedFeatures.map { ProductionFeatureSelection(it.branch, it.sourceSha) }

    private fun cancelTagBuild(
        pipelineId: String,
        confirmedTag: String,
        confirmedReleaseSha: String,
        reason: (ProductionTagPipeline) -> String,
    ): Nothing {
        val timestamp = now()
        store.updateLatest(pipelineId) { current ->
            val failureReason = reason(current)
            val record = ProductionTagBuildRecord(
                expectedTag = confirmedTag.take(160),
                releaseSha = confirmedReleaseSha.take(160),
                state = ProductionTagBuildState.FAILED,
                startedAt = timestamp,
                completedAt = timestamp,
                failureReason = failureReason,
                remoteUrl = persistedAuditRemote(current),
            )
            val cancelled = current.copy(buildRecords = current.buildRecords + record)
            cancelled.copy(auditEvents = cancelled.auditEvents + auditEvent(
                pipeline = cancelled,
                operationId = id(),
                action = ProductionOperationAction.BUILD_TAG.name,
                state = ProductionAuditState.FAILED,
                startedAt = timestamp,
                completedAt = timestamp,
                reason = failureReason,
                features = mergedFeatureSelections(cancelled),
                repository = null,
            ))
        }
        throw ProductionTagConfirmationChangedException()
    }

    private fun persistedAuditRemote(pipeline: ProductionTagPipeline): String? =
        pipeline.auditEvents.asReversed().firstNotNullOfOrNull(ProductionAuditEvent::remoteUrl)
            ?: pipeline.buildRecords.asReversed().firstNotNullOfOrNull(ProductionTagBuildRecord::remoteUrl)

    private fun repository(config: AppConfig, id: String): RepositoryConfig {
        val managedRepositoryIds = config.groups
            .flatMap { it.services }
            .filter { it.enabled }
            .map { it.repositoryId }
            .toSet()
        check(id in managedRepositoryIds) { "该仓库不是 AWM 已启用的服务：$id" }
        return config.repositories.firstOrNull { it.id == id } ?: error("找不到仓库配置：$id")
    }

    private fun beginOperation(
        pipeline: ProductionTagPipeline,
        repository: RepositoryConfig,
        action: ProductionOperationAction,
        expectedTag: String? = null,
        expectedTargetSha: String? = null,
        features: List<ProductionFeatureSelection> = emptyList(),
    ): ProductionTagPipeline {
        requireIdle(pipeline)
        val started = now()
        val operationNonce = id()
        val operationId = "${pipeline.id}-${action.name.lowercase()}-${pipeline.revision + 1}-$operationNonce"
        val sourceToken = "${pipeline.id.takeLast(24)}-${pipeline.revision + 1}-${operationNonce.takeLast(36)}"
            .replace(Regex("[^A-Za-z0-9._-]"), "-")
            .ifBlank { "operation-${pipeline.revision + 1}" }
        val lease = ProductionOperationLease(
            id = operationId,
            action = action,
            startedAt = started,
            expectedTag = expectedTag,
            expectedTargetSha = expectedTargetSha,
            features = features,
            sourceBranch = when (action) {
                ProductionOperationAction.MERGE_PRODUCTION,
                ProductionOperationAction.MERGE_FEATURES,
                -> "awm/production-tag/${action.name.lowercase()}/${sourceToken.take(80)}"
                else -> null
            },
        )
        val leased = pipeline.copy(activeOperation = lease)
        return save(leased.copy(auditEvents = leased.auditEvents + auditEvent(
            pipeline = leased,
            operationId = lease.id,
            action = action.name,
            state = ProductionAuditState.RUNNING,
            startedAt = started,
            features = features,
            repository = repository,
        )))
    }

    private fun finishOperation(
        pipeline: ProductionTagPipeline,
        state: ProductionAuditState,
        reason: String? = null,
        mergeRequestUrl: String? = null,
    ): ProductionTagPipeline {
        val lease = pipeline.activeOperation ?: error("生产 Tag 操作租约不存在")
        val completed = now()
        return save(pipeline.copy(
            activeOperation = null,
            auditEvents = pipeline.auditEvents.map { event ->
                if (event.operationId == lease.id) event.copy(
                    state = state,
                    completedAt = completed,
                    reason = reason,
                    releaseSha = pipeline.releaseSha,
                    masterSha = pipeline.masterSha,
                    mergeRequestUrl = mergeRequestUrl,
                    mergeRequestPlatform = pipeline.mergeRequest?.platform,
                ) else event
            },
        ))
    }

    /** Finalizes failures for which the gateway proved that no remote write happened. */
    private fun finishKnownFailure(pipeline: ProductionTagPipeline, error: Throwable) {
        if (error is ProductionNoPushPermissionException ||
            error is ProductionFeatureTopologyException ||
            error is ProductionMergeRequestUnavailableException
        ) {
            finishOperation(pipeline, ProductionAuditState.FAILED, error.message)
        }
    }

    private fun auditEvent(
        pipeline: ProductionTagPipeline,
        operationId: String,
        action: String,
        state: ProductionAuditState,
        startedAt: String,
        completedAt: String? = null,
        reason: String? = null,
        features: List<ProductionFeatureSelection> = emptyList(),
        repository: RepositoryConfig?,
    ) = ProductionAuditEvent(
        operationId = operationId,
        action = action,
        state = state,
        startedAt = startedAt,
        completedAt = completedAt,
        reason = reason,
        productionTag = pipeline.productionTag,
        productionTagSha = pipeline.productionTagSha,
        masterSha = pipeline.masterSha,
        releaseBranch = pipeline.releaseBranch,
        releaseSha = pipeline.releaseSha,
        features = features,
        remoteUrl = repository?.let(::auditRemote) ?: persistedAuditRemote(pipeline),
        sourceBranch = pipeline.activeOperation?.sourceBranch,
        targetRef = when (pipeline.activeOperation?.action) {
            ProductionOperationAction.MERGE_PRODUCTION -> "refs/heads/master"
            ProductionOperationAction.CREATE_RELEASE,
            ProductionOperationAction.MERGE_FEATURES,
            -> "refs/heads/${pipeline.releaseBranch}"
            ProductionOperationAction.BUILD_TAG -> pipeline.activeOperation.expectedTag?.let { "refs/tags/$it" }
            null -> null
        },
        operator = pipeline.operator,
        genbuCommand = pipeline.genbuCommand,
        productionService = pipeline.productionService,
        productionEnvironment = pipeline.productionEnvironment,
        productionPods = pipeline.productionPods,
    )

    private fun reconcileOperation(config: AppConfig, pipeline: ProductionTagPipeline): ProductionTagPipeline {
        val lease = pipeline.activeOperation ?: return pipeline
        val repository = repository(config, pipeline.repositoryId)
        git.validateRepository(repository)
        return when (lease.action) {
            ProductionOperationAction.MERGE_PRODUCTION -> {
                val recovered = git.recoverProductionWrite(
                    repository = repository,
                    targetBranch = "master",
                    beforeSha = pipeline.masterSha,
                    productionSha = pipeline.productionTagSha,
                    sourceBranch = lease.sourceBranch,
                )
                val finished = when (recovered) {
                    is ProductionBranchWrite.Direct -> finishOperation(
                        pipeline.copy(
                            masterSha = recovered.targetSha,
                            baselineState = ProductionBaselineState.ALREADY_CONTAINED,
                            mergeRequest = null,
                        ),
                        ProductionAuditState.RECOVERED,
                        "重启后按合并父提交确认生产 Tag 已进入 master",
                    )
                    is ProductionBranchWrite.AwaitingRequest -> finishOperation(
                        pipeline.copy(
                            baselineState = ProductionBaselineState.AWAITING_MERGE_REQUEST,
                            mergeRequest = recovered.request,
                        ),
                        ProductionAuditState.AWAITING_MERGE_REQUEST,
                        "重启后确认合并请求源分支已推送",
                        recovered.request.url,
                    )
                    null -> finishOperation(
                        pipeline,
                        ProductionAuditState.FAILED,
                        "重启后未确认原生产回灌写入或合并请求源分支，已安全结束旧操作",
                    )
                }
                if (finished.mergeRequest == null) runCatching { refreshBaseline(config, finished) }.getOrElse { finished }
                else finished
            }
            ProductionOperationAction.CREATE_RELEASE -> {
                val remote = git.releaseHead(repository, pipeline.releaseBranch)
                when {
                    remote == lease.expectedTargetSha -> finishOperation(
                        pipeline.copy(releaseSha = remote, unmanagedReleaseSha = null),
                        ProductionAuditState.RECOVERED,
                        "重启后确认 Release 已按预期创建",
                    )
                    remote == null -> finishOperation(
                        pipeline,
                        ProductionAuditState.FAILED,
                        "重启后确认 Release 未创建，已安全结束旧操作",
                    )
                    else -> finishOperation(
                        pipeline.copy(unmanagedReleaseSha = remote),
                        ProductionAuditState.FAILED,
                        "远端 Release 指向非预期提交，AWM 不会接管：$remote",
                    )
                }
            }
            ProductionOperationAction.MERGE_FEATURES -> {
                val recoveredWrite = lease.expectedTargetSha?.let { beforeSha ->
                    git.recoverFeatureWrite(
                        repository,
                        pipeline.releaseBranch,
                        lease.sourceBranch,
                        beforeSha,
                        lease.features,
                    )
                }
                if (recoveredWrite is ProductionFeatureWrite.Direct) {
                    val known = pipeline.mergedFeatures.map { it.branch to it.sourceSha }.toSet()
                    val recovered = recoveredWrite.merges.filterNot { (it.branch to it.sourceSha) in known }
                    finishOperation(
                        pipeline.copy(
                            releaseSha = recoveredWrite.releaseSha,
                            mergedFeatures = pipeline.mergedFeatures + recovered,
                            selectedFeatures = emptyList(),
                            featureState = ProductionFeatureBatchState.MERGED,
                            conflicts = emptyList(),
                        ),
                        ProductionAuditState.RECOVERED,
                        "重启后确认远端 Release 已包含全部固定 Feature SHA，并恢复实际 Merge Commit",
                    )
                } else if (recoveredWrite is ProductionFeatureWrite.AwaitingRequest) {
                    val known = pipeline.mergedFeatures.map { it.branch to it.sourceSha }.toSet()
                    val recovered = recoveredWrite.merges.filterNot { (it.branch to it.sourceSha) in known }
                    finishOperation(
                        pipeline.copy(
                            mergedFeatures = pipeline.mergedFeatures + recovered,
                            featureState = ProductionFeatureBatchState.AWAITING_MERGE_REQUEST,
                            conflicts = emptyList(),
                            mergeRequest = recoveredWrite.request,
                        ),
                        ProductionAuditState.AWAITING_MERGE_REQUEST,
                        "重启后确认 Feature 合并请求源分支已推送",
                        recoveredWrite.request.url,
                    )
                } else finishOperation(
                    pipeline.copy(featureState = ProductionFeatureBatchState.IDLE),
                    ProductionAuditState.FAILED,
                    "重启后未确认 Feature 批次已完整进入 Release 或合并请求源分支，已安全结束旧操作",
                )
            }
            ProductionOperationAction.BUILD_TAG -> {
                val expectedTag = lease.expectedTag ?: error("构建 Tag 操作缺少预期 Tag")
                val releaseSha = lease.expectedTargetSha ?: error("构建 Tag 操作缺少 Release SHA")
                val remote = git.tagsForBase(repository, pipeline.baseVersion)
                    .firstOrNull { it.name == expectedTag }?.commitSha
                val index = pipeline.buildRecords.indexOfLast {
                    it.expectedTag == expectedTag && it.releaseSha == releaseSha &&
                        it.state in setOf(ProductionTagBuildState.PREPARING, ProductionTagBuildState.PUSHING)
                }
                val record = pipeline.buildRecords.getOrNull(index) ?: ProductionTagBuildRecord(
                    expectedTag, releaseSha, ProductionTagBuildState.PREPARING, startedAt = lease.startedAt,
                )
                val reconciled = if (remote == releaseSha) record.copy(
                    state = ProductionTagBuildState.PUSHED,
                    remoteTagSha = remote,
                    completedAt = now(),
                    remoteUrl = auditRemote(repository),
                    actualTag = expectedTag,
                ) else record.copy(
                    state = ProductionTagBuildState.FAILED,
                    completedAt = now(),
                    failureReason = if (remote == null) "重启后确认远端 Tag 不存在" else "远端 Tag 指向非预期提交：$remote",
                    remoteUrl = auditRemote(repository),
                )
                val records = if (index >= 0) pipeline.buildRecords.toMutableList().also { it[index] = reconciled }
                else pipeline.buildRecords + reconciled
                finishOperation(
                    pipeline.copy(buildRecords = records),
                    if (remote == releaseSha) ProductionAuditState.RECOVERED else ProductionAuditState.FAILED,
                    reconciled.failureReason ?: "重启后确认远端 Tag 已按预期创建",
                )
            }
        }
    }

    private fun revalidateBaseline(config: AppConfig, pipeline: ProductionTagPipeline): ProductionTagPipeline {
        requireOpen(pipeline)
        requireIdle(pipeline)
        check(pipeline.mergeRequest == null) { "当前有待处理的合并请求，请使用“刷新合并状态”" }
        val refreshed = refreshBaseline(config, pipeline)
        val changed = refreshed.productionTag != pipeline.productionTag ||
            refreshed.productionTagSha != pipeline.productionTagSha ||
            refreshed.masterSha != pipeline.masterSha ||
            refreshed.baselineState != pipeline.baselineState ||
            refreshed.baseVersion != pipeline.baseVersion ||
            refreshed.releaseBranch != pipeline.releaseBranch ||
            refreshed.unmanagedReleaseSha != pipeline.unmanagedReleaseSha
        if (changed) throw ProductionBaselineChangedException(refreshed)
        return refreshed
    }

    private fun refreshBaseline(config: AppConfig, pipeline: ProductionTagPipeline): ProductionTagPipeline {
        val repository = repository(config, pipeline.repositoryId)
        git.validateRepository(repository)
        val runtime = versions.current(repository.name)
        val evidence = git.inspectBaseline(repository, runtime.version)
        val baseVersion = ProductionTagVersioning.nextBase(git.formalTags(repository))
        val releaseBranch = "release/$baseVersion"
        val unmanagedReleaseSha = git.releaseHead(repository, releaseBranch)
        val timestamp = now()
        val updated = pipeline.copy(
            productionTag = evidence.productionTag,
            productionTagSha = evidence.productionTagSha,
            masterSha = evidence.masterSha,
            baselineState = evidence.state,
            baseVersion = baseVersion,
            releaseBranch = releaseBranch,
            mergeRequest = null,
            productionService = runtime.service,
            productionEnvironment = runtime.environment,
            productionPods = runtime.pods,
            baselineCheckedAt = timestamp,
            unmanagedReleaseSha = unmanagedReleaseSha,
            genbuCommand = runtime.queryCommand,
            operator = git.operator(repository).orEmpty(),
        )
        return save(updated.copy(auditEvents = updated.auditEvents + auditEvent(
            pipeline = updated,
            operationId = id(),
            action = "BASELINE_CHECK",
            state = if (unmanagedReleaseSha == null) ProductionAuditState.SUCCEEDED else ProductionAuditState.FAILED,
            startedAt = timestamp,
            completedAt = timestamp,
            reason = unmanagedReleaseSha?.let { "存在未接管的 Release：$releaseBranch@$it" },
            repository = repository,
        )))
    }

    private fun requireOpen(pipeline: ProductionTagPipeline) {
        check(!pipeline.closed) { "生产 Tag 流水线已关闭" }
    }

    private fun requireIdle(pipeline: ProductionTagPipeline) {
        check(pipeline.activeOperation == null) {
            "生产 Tag 操作 ${pipeline.activeOperation?.id} 尚未完成，请先继续流水线进行对账"
        }
    }
}
