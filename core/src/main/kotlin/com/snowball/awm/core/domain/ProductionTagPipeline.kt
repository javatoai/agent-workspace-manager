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
)
