package com.snowball.awm.core

/** Delegates branch grammar to Git itself so UI and provisioning share Git's exact rules. */
class GitBranchReferenceValidator(
    private val runner: CommandRunner = ProcessCommandRunner(),
) : BranchReferenceValidator {
    override fun isValid(branch: String): Boolean = runCatching {
        runner.run(listOf("git", "check-ref-format", "--branch", branch)).succeeded
    }.getOrDefault(false)
}
