package com.snowball.awm.core

val ServiceWorkspace.selectionKey: String
    get() = "$groupServiceId:$moduleId"

data class EffectiveTagTarget(
    val workspace: ServiceWorkspace,
    val featureBranch: String,
    val remote: String,
    val uatBranch: String,
    val initialUatTag: String?,
    val tagMessagePrefix: String,
)

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
        check(group.uatTagEnabled) { "组 ${group.name} 已关闭 Tag" }
        val service = group.services.firstOrNull { it.id == workspace.groupServiceId }
            ?: error("组内服务配置不存在：${workspace.groupServiceId}")
        return when (workspace.strategy) {
            WorkspaceStrategy.STANDARD_WORKTREE -> {
                val module = service.modules.firstOrNull { it.id == workspace.moduleId }
                    ?: error("模块配置不存在：${workspace.moduleId}")
                check(module.uatTagEnabled) { "模块 ${ModuleDisplayNaming.resolve(module.name, service.displayName, module.baseRef, service.modules.size)} 已关闭 Tag" }
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
                val module = service.cloneModules.firstOrNull { it.id == workspace.moduleId }
                    ?: error("独立克隆模块配置不存在：${workspace.moduleId}")
                check(module.uatTagEnabled) { "模块 ${workspace.moduleName} 已关闭 Tag" }
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
        }
    }
}
