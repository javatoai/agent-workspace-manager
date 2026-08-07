package com.snowball.taskwt.core

/** Derives deterministic task branches while respecting Git's one-branch-per-worktree invariant. */
object TaskBranchNaming {
    fun derive(requestedBranch: String, modules: List<ServiceModuleConfig>): Map<String, String> {
        require(requestedBranch.isNotBlank()) { "任务分支不能为空" }
        require(modules.isNotEmpty()) { "至少需要一个模块" }
        require(modules.map { it.id }.distinct().size == modules.size) { "模块 ID 不能重复" }
        val identities = modules.associateWith(::baseIdentity)
        val distinctBases = identities.values.distinct()
        if (distinctBases.size == 1) return modules.associate { it.id to requestedBranch }

        val suffixByBase = modules.distinctBy { identities.getValue(it) }
            .associate { identities.getValue(it) to suffixFor(it) }
        require(suffixByBase.values.distinct().size == suffixByBase.size) {
            "基础分支生成了重复后缀，请调整模块配置"
        }
        return modules.associate { module ->
            module.id to "$requestedBranch-${suffixByBase.getValue(identities.getValue(module))}"
        }
    }

    /** Canonical identity used to decide whether modules may share one physical Worktree. */
    fun baseIdentity(module: ServiceModuleConfig): String = "${module.baseRemote}|${normalizeBaseRef(module)}"

    private fun normalizeBaseRef(module: ServiceModuleConfig): String {
        val value = module.baseRef.trim()
        return value
            .removePrefix("refs/remotes/${module.baseRemote}/")
            .removePrefix("refs/heads/")
            .removePrefix("${module.baseRemote}/")
    }

    private fun suffixFor(module: ServiceModuleConfig): String {
        val suffix = normalizeBaseRef(module)
            .split('/')
            .filter(String::isNotBlank)
            .joinToString("-")
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-', '.')
        require(suffix.isNotBlank()) { "无法从基础分支生成后缀：${module.baseRef}" }
        return suffix
    }
}
