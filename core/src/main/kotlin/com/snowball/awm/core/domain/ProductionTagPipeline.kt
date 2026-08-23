package com.snowball.awm.core

import kotlinx.serialization.Serializable

@Serializable
enum class ProductionBaselineState {
    CHECKING,
    ALREADY_CONTAINED,
    MERGE_REQUIRED,
    AWAITING_MERGE_REQUEST,
    NETWORK_ERROR,
    CONFIGURATION_ERROR,
}

@Serializable
enum class ProductionFeatureBatchState {
    IDLE,
    CHECKING,
    CONFLICT,
    MERGED,
    AWAITING_MERGE_REQUEST,
}

@Serializable
enum class ProductionTagBuildState {
    PREPARING,
    PUSHING,
    PUSHED,
    NO_PERMISSION,
    FAILED,
    ALREADY_EXISTS,
}

@Serializable
enum class ProductionOperationAction {
    MERGE_PRODUCTION,
    CREATE_RELEASE,
    MERGE_FEATURES,
    BUILD_TAG,
}

@Serializable
enum class ProductionAuditState { RUNNING, SUCCEEDED, FAILED, CONFLICT, AWAITING_MERGE_REQUEST, RECOVERED }

@Serializable
data class ProductionOperationLease(
    val id: String,
    val action: ProductionOperationAction,
    val startedAt: String,
    val expectedTag: String? = null,
    val expectedTargetSha: String? = null,
    val features: List<ProductionFeatureSelection> = emptyList(),
    /** Stable remote source branch used when a protected target requires an MR. */
    val sourceBranch: String? = null,
)

@Serializable
data class ProductionAuditEvent(
    val operationId: String,
    val action: String,
    val state: ProductionAuditState,
    val startedAt: String,
    val completedAt: String? = null,
    val reason: String? = null,
    val productionTag: String,
    val productionTagSha: String,
    val masterSha: String,
    val releaseBranch: String,
    val releaseSha: String? = null,
    val features: List<ProductionFeatureSelection> = emptyList(),
    val remoteUrl: String? = null,
    val mergeRequestUrl: String? = null,
    val sourceBranch: String? = null,
    val targetRef: String? = null,
    val operator: String = "",
    val genbuCommand: String = "",
    val productionService: String = "",
    val productionEnvironment: String = "",
    val productionPods: List<ProductionPodSnapshot> = emptyList(),
)

@Serializable
data class ProductionFeatureSelection(
    val branch: String,
    val sha: String = "",
)

@Serializable
data class ProductionFeatureMergeRecord(
    val branch: String,
    val sourceSha: String,
    val mergeCommit: String,
    val completedAt: String,
)

@Serializable
data class ProductionConflict(
    val branch: String,
    val files: List<String>,
    val sourceSha: String,
)

@Serializable
data class ProductionMergeRequest(
    val platform: String,
    val url: String,
    val sourceBranch: String,
    val targetBranch: String,
    val expectedCommit: String,
)

@Serializable
data class ProductionTagBuildRecord(
    val expectedTag: String,
    val releaseSha: String,
    val state: ProductionTagBuildState,
    val remoteTagSha: String? = null,
    val startedAt: String = "",
    val completedAt: String? = null,
    val failureReason: String? = null,
    val remoteUrl: String? = null,
    val actualTag: String? = null,
)

@Serializable
data class ProductionTagPipeline(
    val id: String,
    val repositoryId: String,
    val serviceName: String,
    val productionTag: String,
    val productionTagSha: String,
    val masterSha: String,
    val baselineState: ProductionBaselineState,
    val baseVersion: String,
    val releaseBranch: String,
    val releaseSha: String? = null,
    val selectedFeatures: List<ProductionFeatureSelection> = emptyList(),
    val mergedFeatures: List<ProductionFeatureMergeRecord> = emptyList(),
    val featureState: ProductionFeatureBatchState = ProductionFeatureBatchState.IDLE,
    val conflicts: List<ProductionConflict> = emptyList(),
    val mergeRequest: ProductionMergeRequest? = null,
    val buildRecords: List<ProductionTagBuildRecord> = emptyList(),
    val closed: Boolean = false,
    val createdAt: String = "",
    val updatedAt: String = "",
    /** Optimistic concurrency token for cross-process pipeline updates. */
    val revision: Long = 0,
    val productionService: String = "",
    val productionEnvironment: String = "",
    val productionPods: List<ProductionPodSnapshot> = emptyList(),
    val baselineCheckedAt: String = "",
    val baselineSource: String = "Genbu + origin",
    val unmanagedReleaseSha: String? = null,
    val activeOperation: ProductionOperationLease? = null,
    val auditEvents: List<ProductionAuditEvent> = emptyList(),
    val genbuCommand: String = "",
    val operator: String = "",
)
