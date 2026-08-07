package com.snowball.taskwt.core

import kotlinx.serialization.Serializable

const val CURRENT_APP_CONFIG_SCHEMA_VERSION = 2
const val CURRENT_TASK_MANIFEST_SCHEMA_VERSION = 2

@Serializable
enum class IdeType {
    IDEA,
    WEBSTORM
}

@Serializable
enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK
}

@Serializable
data class BootstrapCopyRule(
    val source: String,
    val target: String = source,
    val overwrite: Boolean = true,
)

@Serializable
data class BootstrapCommand(
    val name: String,
    val executable: String,
    val arguments: List<String> = emptyList(),
    val workingDirectory: String = ".",
    val timeoutSeconds: Long = 600,
    val platforms: Set<String> = emptySet(),
    val enabled: Boolean = true,
)

@Serializable
data class BootstrapConfig(
    val copyRules: List<BootstrapCopyRule> = emptyList(),
    val commands: List<BootstrapCommand> = emptyList(),
)

@Serializable
data class ServiceConfig(
    val repositoryId: String,
    val enabled: Boolean = true,
    val displayName: String,
    val ideType: IdeType = IdeType.IDEA,
    val defaultBaseRef: String = "origin/master",
    val uatRemote: String = "origin",
    val uatBranch: String = "release/test",
    val initialUatTag: String? = null,
    val tagMessagePrefix: String = "UAT",
    val bootstrap: BootstrapConfig = BootstrapConfig(),
)

@Serializable
data class AppConfig(
    val schemaVersion: Int = CURRENT_APP_CONFIG_SCHEMA_VERSION,
    val scanRoots: List<String> = emptyList(),
    val taskRoot: String? = null,
    val services: Map<String, ServiceConfig> = emptyMap(),
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val ideaExecutable: String? = null,
    val webStormExecutable: String? = null,
    val terminalExecutable: String? = null,
    /** Appended to each generated task AGENTS.md under「自定义说明」. */
    val agentsMdAppendix: String = "",
)

@Serializable
data class RepositoryInfo(
    val id: String,
    val name: String,
    val rootPath: String,
    val gitCommonDirectory: String,
    val remoteUrl: String? = null,
    val currentBranch: String? = null,
    val isBare: Boolean = false,
)

@Serializable
enum class WorkspaceStatus {
    CREATING,
    READY,
    READY_WITH_WARNINGS,
    FAILED,
    ARCHIVED
}

@Serializable
data class ServiceWorkspace(
    val repositoryId: String,
    val serviceName: String,
    val repositoryPath: String,
    val worktreePath: String,
    val ideType: IdeType,
    val branch: String,
    val status: WorkspaceStatus = WorkspaceStatus.CREATING,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class TaskManifest(
    val schemaVersion: Int = CURRENT_TASK_MANIFEST_SCHEMA_VERSION,
    val folderName: String,
    val taskDirectoryName: String,
    val featureBranch: String,
    val requirementLink: String = "",
    val createdAt: String,
    val updatedAt: String,
    val status: WorkspaceStatus,
    val services: List<ServiceWorkspace>,
)

@Serializable
enum class TagOperationState {
    CREATED,
    PREFLIGHT_PASSED,
    FEATURE_PUSHED,
    TEST_BRANCH_PUSHED,
    LOCAL_TAG_CREATED,
    TAG_PUSHED,
    SUCCESS,
    CONFLICT,
    FAILED,
    PARTIAL
}

@Serializable
data class TagOperation(
    val operationId: String,
    val folderName: String,
    val serviceName: String,
    val repositoryId: String,
    val featureBranch: String,
    val testBranch: String,
    val remote: String,
    val state: TagOperationState,
    val createdAt: String,
    val updatedAt: String,
    val featureSha: String? = null,
    val testSha: String? = null,
    val tag: String? = null,
    val message: String? = null,
    val conflictFiles: List<String> = emptyList(),
)

@Serializable
data class TagBuildHistoryEntry(
    val operationId: String,
    val timestamp: String,
    val folderName: String,
    val serviceName: String,
    val featureBranch: String,
    val testBranch: String,
    val tag: String? = null,
    val state: TagOperationState,
    val message: String? = null,
)

@Serializable
data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    val succeeded: Boolean get() = exitCode == 0
}
