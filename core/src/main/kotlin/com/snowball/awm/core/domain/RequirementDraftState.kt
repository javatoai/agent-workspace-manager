package com.snowball.awm.core

/** Pure creation-form state that protects explicit user edits from asynchronous metadata results. */
data class RequirementDraftState(
    val requirementLink: String = "",
    val taskName: String = "",
    val branch: String = "",
    val nameEdited: Boolean = false,
    val branchEdited: Boolean = false,
    val metadataLoading: Boolean = false,
    val metadataHint: String? = null,
) {
    fun changeRequirement(value: String, branchPrefix: String): RequirementDraftState {
        val resolved = BranchPrefixResolver.resolve(branchPrefix, value)
        return copy(
            requirementLink = value,
            branch = if (branchEdited) branch else resolved ?: branchPrefix,
            metadataLoading = FeishuWorkItemLink.parse(value) != null,
            metadataHint = if (BranchPrefixResolver.containsUnresolvedPlaceholder(branchPrefix) && resolved == null) {
                "未从需求链接中解析到编号"
            } else null,
        )
    }

    fun changeGroup(branchPrefix: String): RequirementDraftState = copy(
        branch = if (branchEdited) branch else BranchPrefixResolver.resolve(branchPrefix, requirementLink) ?: branchPrefix,
        metadataHint = if (!branchEdited && BranchPrefixResolver.containsUnresolvedPlaceholder(branchPrefix) &&
            BranchPrefixResolver.resolve(branchPrefix, requirementLink) == null
        ) "未从需求链接中解析到编号" else null,
    )

    fun editName(value: String): RequirementDraftState = copy(taskName = value, nameEdited = true)

    fun editBranch(value: String): RequirementDraftState = copy(branch = value, branchEdited = true)

    fun applyMetadata(requestedLink: String, metadata: RequirementMetadata?): RequirementDraftState {
        if (requestedLink != requirementLink) return this
        val title = metadata?.title?.takeIf(String::isNotBlank)
        return copy(
            taskName = if (!nameEdited && title != null) title else taskName,
            metadataLoading = false,
            metadataHint = if (metadata == null) "未获取到需求标题，可手工填写" else metadataHint,
        )
    }
}
