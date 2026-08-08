package com.snowball.taskwt.core

val ServiceWorkspace.selectionKey: String
    get() = "$groupServiceId:$moduleId"

data class EffectiveTagTarget(
    val workspace: ServiceWorkspace,
    val featureBranch: String,
    val remote: String,
    val uatBranch: String,
    val initialUatTag: String?,
    val tagMessagePrefix: String,
) {
    fun asLegacyServiceConfig(): ServiceConfig = ServiceConfig(
        repositoryId = workspace.repositoryId,
        displayName = workspace.serviceName,
        ideType = workspace.ideType,
        defaultBaseRef = workspace.baseRef ?: "origin/master",
        uatRemote = remote,
        uatBranch = uatBranch,
        initialUatTag = initialUatTag,
        tagMessagePrefix = tagMessagePrefix,
    )
}

/** Resolves the two-level group/child Tag gate and immutable workspace branch. */
object TagPolicy {
    fun resolve(config: AppConfig, manifest: TaskManifest, selection: String): EffectiveTagTarget {
        val candidates = manifest.services.filter {
            it.selectionKey == selection || it.repositoryId == selection
        }
        require(candidates.isNotEmpty()) { "任务中不存在 Tag 目标：$selection" }
        require(candidates.size == 1) { "Tag 目标不唯一，请选择具体模块：$selection" }
        val workspace = candidates.single()
        val group = config.group(manifest.groupId)
        check(group.createTagEnabled) { "组 ${group.name} 已关闭 Tag" }
        val service = group.services.firstOrNull { it.id == workspace.groupServiceId }
        if (service == null) {
            // In-memory compatibility seam for the pre-0.2 integration suite.
            // Legacy maps are transient and can never enter the strict v5 JSON schema.
            val legacy = config.services[workspace.repositoryId]
                ?: error("组内服务配置不存在：${workspace.groupServiceId}")
            check(workspace.tagEnabled) { "服务 ${workspace.serviceName} 已关闭 Tag" }
            return EffectiveTagTarget(
                workspace,
                workspace.branch,
                legacy.uatRemote,
                legacy.uatBranch,
                legacy.initialUatTag,
                legacy.tagMessagePrefix,
            )
        }
        return when (workspace.strategy) {
            WorkspaceStrategy.STANDARD_WORKTREE -> {
                val module = service.modules.firstOrNull { it.id == workspace.moduleId }
                    ?: error("模块配置不存在：${workspace.moduleId}")
                check(module.tagEnabled) { "模块 ${ModuleDisplayNaming.resolve(module.name, service.displayName, module.baseRef, service.modules.size)} 已关闭 Tag" }
                val target = RemoteBranchRef.parse(module.uatRef)
                EffectiveTagTarget(
                    workspace,
                    workspace.branch,
                    target.remote,
                    target.branch,
                    module.initialUatTag,
                    module.tagMessagePrefix,
                )
            }
            WorkspaceStrategy.INDEPENDENT_CLONE -> {
                check(service.cloneTagEnabled) { "独立克隆 ${service.displayName} 已关闭 Tag" }
                val target = RemoteBranchRef.parse(service.cloneUatRef)
                EffectiveTagTarget(
                    workspace,
                    workspace.branch,
                    target.remote,
                    target.branch,
                    service.cloneInitialUatTag,
                    service.cloneTagMessagePrefix,
                )
            }
        }
    }
}
