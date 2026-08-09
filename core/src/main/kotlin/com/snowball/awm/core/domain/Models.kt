package com.snowball.awm.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/** Persisted data follows the product release line and is deliberately strict. */
const val CURRENT_APP_CONFIG_SCHEMA_VERSION = "0.5.0"
const val CURRENT_TASK_MANIFEST_SCHEMA_VERSION = "0.5.0"
const val DEFAULT_GROUP_ID = "default"
const val DEFAULT_GROUP_NAME = "默认组"

@Serializable
enum class IdeType {
    IDEA,
    WEBSTORM,
}

@Serializable
enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK,
}

/** One explicitly configured Feishu Project space used to discover requirement links. */
@Serializable
data class MeegleProjectConfig(
    @SerialName("project_key")
    val projectKey: String,
    @SerialName("simple_name")
    val simpleName: String,
) {
    init {
        require(projectKey.matches(Regex("[A-Za-z0-9_-]+"))) { "Meegle 空间 Key 只能包含字母、数字、下划线和连字符" }
        require(simpleName.matches(Regex("[A-Za-z0-9_-]+"))) { "Meegle 空间短名只能包含字母、数字、下划线和连字符" }
    }
}

@Serializable
enum class WorkspaceStrategy {
    STANDARD_WORKTREE,
    INDEPENDENT_CLONE,
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

/** A persisted physical repository selected explicitly by the user. */
@Serializable
data class RepositoryConfig(
    val id: String,
    val name: String,
    val rootPath: String,
    val gitCommonDirectory: String,
    val originUrl: String? = null,
    val currentBranch: String? = null,
    val defaultRemoteBranch: String? = null,
) {
    init {
        require(id.isNotBlank()) { "仓库 ID 不能为空" }
        require(name.isNotBlank()) { "仓库名称不能为空" }
        require(rootPath.isNotBlank()) { "仓库根目录不能为空" }
        require(gitCommonDirectory.isNotBlank()) { "仓库 git-common-dir 不能为空" }
    }
}

/**
 * A standard service module maps one base ref to one writable worktree.
 * Multiple code modules that share a base ref intentionally share this entry;
 * their finer-grained edit rules belong in the group's AGENTS.md.
 */
@Serializable
data class ServiceModuleConfig(
    val id: String,
    val name: String = "",
    val baseRef: String = "origin/master",
    val baseRemote: String = "origin",
    val uatTagEnabled: Boolean = true,
    val uatRef: String = "origin/release/test",
    val initialUatTag: String? = null,
    val tagMessagePrefix: String = "UAT",
) {
    init {
        require(id.isNotBlank()) { "模块 ID 不能为空" }
    }
}

/** One fixed-branch clone entry. Every entry receives its own physical clone. */
@Serializable
data class IndependentCloneModuleConfig(
    val id: String,
    val name: String = "",
    val branch: String = "origin/master",
    val uatTagEnabled: Boolean = false,
    val uatRef: String = "origin/release/test",
    val initialUatTag: String? = null,
    val tagMessagePrefix: String = "UAT",
) {
    init {
        require(id.isNotBlank()) { "独立克隆模块 ID 不能为空" }
        require(RemoteBranchRef.parse(branch).remote == "origin") {
            "独立克隆模块分支必须使用 origin/<branch> 格式"
        }
    }
}

/** A repository's independently configurable membership inside one ordered group. */
@Serializable
data class GroupServiceConfig(
    val id: String,
    val repositoryId: String,
    val displayName: String,
    val enabled: Boolean = true,
    val ideType: IdeType = IdeType.IDEA,
    val strategy: WorkspaceStrategy = WorkspaceStrategy.STANDARD_WORKTREE,
    val modules: List<ServiceModuleConfig> = listOf(
        ServiceModuleConfig(id = "default"),
    ),
    val cloneModules: List<IndependentCloneModuleConfig> = emptyList(),
    val bootstrap: BootstrapConfig = BootstrapConfig(),
) {
    init {
        require(id.isNotBlank()) { "组内服务 ID 不能为空" }
        require(TaskNaming.directoryName(id) == id) { "组内服务 ID 必须是安全且稳定的目录片段" }
        require(repositoryId.isNotBlank()) { "仓库 ID 不能为空" }
        require(displayName.isNotBlank()) { "服务名称不能为空" }
        when (strategy) {
            WorkspaceStrategy.STANDARD_WORKTREE -> {
                require(modules.isNotEmpty()) { "标准 Worktree 服务至少需要一个模块" }
                require(modules.map { it.id }.distinct().size == modules.size) { "同一服务内模块 ID 不能重复" }
            }
            WorkspaceStrategy.INDEPENDENT_CLONE -> {
                require(cloneModules.isNotEmpty()) { "独立克隆服务至少需要一个克隆模块" }
                require(cloneModules.map { it.id }.distinct().size == cloneModules.size) { "独立克隆模块 ID 不能重复" }
                require(cloneModules.map { it.branch }.distinct().size == cloneModules.size) { "独立克隆模块分支不能重复" }
            }
        }
    }

    companion object {
        fun standard(
            id: String,
            repositoryId: String,
            displayName: String,
            ideType: IdeType = IdeType.IDEA,
            baseRef: String = "origin/master",
        ): GroupServiceConfig = GroupServiceConfig(
            id = id,
            repositoryId = repositoryId,
            displayName = displayName,
            ideType = ideType,
            modules = listOf(ServiceModuleConfig(id = "default", baseRef = baseRef)),
        )
    }
}

@Serializable
data class GroupConfig(
    val id: String,
    val name: String,
    val uatTagEnabled: Boolean = true,
    val services: List<GroupServiceConfig> = emptyList(),
    val defaultBranchPrefix: String = "",
    val defaultWorkspaceToolIds: List<String> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "组 ID 不能为空" }
        require(id.matches(Regex("[A-Za-z0-9._-]+"))) { "组 ID 只能包含字母、数字、点、下划线和连字符" }
        require(TaskNaming.directoryName(id) == id) { "组 ID 必须是安全且稳定的目录片段" }
        require(name.isNotBlank()) { "组名称不能为空" }
        require(defaultBranchPrefix.none(Char::isWhitespace)) { "默认分支名前缀不能包含空白字符" }
        require(defaultWorkspaceToolIds.all(String::isNotBlank)) { "工作区工具 ID 不能为空" }
        require(defaultWorkspaceToolIds.distinct().size == defaultWorkspaceToolIds.size) {
            "同一组内的默认工作区工具不能重复"
        }
        require(services.map { it.repositoryId }.distinct().size == services.size) {
            "同一仓库在一个组内只能出现一次"
        }
        require(services.map { it.id }.distinct().size == services.size) { "同一组内服务 ID 不能重复" }
    }
}

/** Version 0.5.0 is intentionally strict and contains no compatibility-only fields. */
@Serializable
data class AppConfig(
    val schemaVersion: String = CURRENT_APP_CONFIG_SCHEMA_VERSION,
    val taskRoot: String? = null,
    val repositories: List<RepositoryConfig> = emptyList(),
    val groups: List<GroupConfig> = listOf(
        GroupConfig(DEFAULT_GROUP_ID, DEFAULT_GROUP_NAME),
    ),
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val ideaExecutable: String? = null,
    val webStormExecutable: String? = null,
    val terminalExecutable: String? = null,
    val meegleProjects: List<MeegleProjectConfig> = emptyList(),
    /** Controls only opening the create-task dialog; it never runs at app startup. */
    val meegleAutoLoadRequirementLinks: Boolean = false,
) {
    init {
        require(groups.isNotEmpty()) { "至少需要一个组" }
        require(groups.map { it.id }.distinct().size == groups.size) { "组 ID 不能重复" }
        require(repositories.map { it.id }.distinct().size == repositories.size) { "仓库 ID 不能重复" }
        require(repositories.map { it.gitCommonDirectory.lowercase() }.distinct().size == repositories.size) {
            "同一 Git common directory 只能配置一次"
        }
        require(meegleProjects.map(MeegleProjectConfig::projectKey).distinct().size == meegleProjects.size) {
            "Meegle 空间 Key 不能重复"
        }
        val repositoryById = repositories.associateBy(RepositoryConfig::id)
        groups.flatMap(GroupConfig::services).forEach { service ->
            val repository = repositoryById[service.repositoryId]
                ?: throw IllegalArgumentException("服务 ${service.displayName} 引用了不存在的仓库：${service.repositoryId}")
            if (service.strategy == WorkspaceStrategy.STANDARD_WORKTREE) {
                service.modules.forEach { module ->
                    require(module.baseRef.isNotBlank()) { "模块基础分支不能为空" }
                    require(module.baseRemote.isNotBlank()) { "模块基础远程不能为空" }
                    RemoteBranchRef.parse(module.uatRef)
                }
            } else {
                require(!repository.originUrl.isNullOrBlank()) { "独立克隆服务 ${service.displayName} 的仓库没有 origin" }
                service.cloneModules.forEach { module -> RemoteBranchRef.parse(module.uatRef) }
            }
        }
    }

    fun group(groupId: String): GroupConfig =
        groups.firstOrNull { it.id == groupId }
            ?: throw IllegalArgumentException("找不到组：$groupId")

    fun groupService(groupId: String, serviceId: String): GroupServiceConfig =
        group(groupId).services.firstOrNull { it.id == serviceId }
            ?: throw IllegalArgumentException("组中找不到服务：$serviceId")
}

@Serializable
data class RepositoryInfo(
    val id: String,
    val name: String,
    val rootPath: String,
    val gitCommonDirectory: String,
    val remoteUrl: String? = null,
    val currentBranch: String? = null,
    val isBare: Boolean = false,
) {
    fun toConfig(): RepositoryConfig = RepositoryConfig(
        id = id,
        name = name,
        rootPath = rootPath,
        gitCommonDirectory = gitCommonDirectory,
        originUrl = remoteUrl,
        currentBranch = currentBranch,
        defaultRemoteBranch = null,
    )
}

fun RepositoryConfig.toInfo(): RepositoryInfo = RepositoryInfo(
    id = id,
    name = name,
    rootPath = rootPath,
    gitCommonDirectory = gitCommonDirectory,
    remoteUrl = originUrl,
    currentBranch = currentBranch,
)

@Serializable
enum class TaskLifecycleStatus {
    ACTIVE,
    ARCHIVED,
}

@Serializable
enum class WorkspaceHealth {
    CREATING,
    READY,
    READY_WITH_WARNINGS,
    FAILED,
}

@Serializable
data class ServiceWorkspace(
    val repositoryId: String,
    val serviceName: String,
    val repositoryPath: String,
    val worktreePath: String,
    val ideType: IdeType,
    val branch: String,
    val health: WorkspaceHealth = WorkspaceHealth.CREATING,
    val warnings: List<String> = emptyList(),
    val groupServiceId: String = repositoryId,
    val moduleId: String = "default",
    val moduleName: String = serviceName,
    val strategy: WorkspaceStrategy = WorkspaceStrategy.STANDARD_WORKTREE,
    /** Origin is persisted for independent-clone restore; credentials are rejected by repository inspection. */
    val originUrl: String? = null,
    /** Base ref is diagnostic metadata for a standard module and is never re-derived during restore. */
    val baseRef: String? = null,
)

@Serializable
data class TaskManifest(
    val schemaVersion: String = CURRENT_TASK_MANIFEST_SCHEMA_VERSION,
    val folderName: String,
    val taskDirectoryName: String,
    val featureBranch: String,
    val requirementLink: String = "",
    val createdAt: String,
    val updatedAt: String,
    val lifecycleStatus: TaskLifecycleStatus = TaskLifecycleStatus.ACTIVE,
    val services: List<ServiceWorkspace>,
    val groupId: String = DEFAULT_GROUP_ID,
    val workspaceToolLaunches: List<WorkspaceToolLaunch> = emptyList(),
)

/** Task health is derived from its workspaces and is never persisted independently. */
val TaskManifest.health: WorkspaceHealth
    get() = aggregateWorkspaceHealth(services)

fun aggregateWorkspaceHealth(workspaces: List<ServiceWorkspace>): WorkspaceHealth = when {
    workspaces.any { it.health == WorkspaceHealth.FAILED } -> WorkspaceHealth.FAILED
    workspaces.any { it.health == WorkspaceHealth.CREATING } -> WorkspaceHealth.CREATING
    workspaces.any { it.health == WorkspaceHealth.READY_WITH_WARNINGS } -> WorkspaceHealth.READY_WITH_WARNINGS
    else -> WorkspaceHealth.READY
}

@Serializable
enum class WorkspaceToolLaunchStatus {
    PENDING,
    OPENED,
    FAILED,
}

/** Persisted result of opening one external development tool for a task workspace. */
@Serializable
data class WorkspaceToolLaunch(
    val toolId: String,
    val status: WorkspaceToolLaunchStatus,
    val updatedAt: String,
    val message: String? = null,
) {
    init {
        require(toolId.isNotBlank()) { "工作区工具 ID 不能为空" }
    }
}

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
    PARTIAL,
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
    val groupServiceId: String = repositoryId,
    val moduleId: String = "default",
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
