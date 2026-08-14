package com.snowball.awm.core

val ServiceWorkspace.selectionKey: String
    get() = "$groupServiceId:$moduleId"

data class EffectiveTagTarget(
    val workspace: ServiceWorkspace,
    val sourceBranch: String,
    val remote: String,
    val targetBranch: String?,
    val tagMessagePrefix: String,
    val mode: TagBuildMode,
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
        check(group.tagEnabled) { "组 ${group.name} 已关闭 Tag" }
        check(workspace.tagEnabled) { "模块 ${workspace.moduleName} 已关闭 Tag" }
        val target = if (workspace.tagMode == TagBuildMode.MERGE_TO_TARGET_BRANCH) {
            RemoteBranchRef.parse(requireNotNull(workspace.tagTargetRef))
        } else null
        return EffectiveTagTarget(
            workspace,
            workspace.branch,
            target?.remote ?: workspace.pushRemote,
            target?.branch,
            workspace.tagMessagePrefix,
            workspace.tagMode,
        )
    }
}
