package com.snowball.awm.core

import java.nio.file.Files
import java.nio.file.Path

/** Reads only local Git metadata; it deliberately never fetches or contacts a remote. */
class GitWorkspaceGitStatusReader(
    private val git: GitClient = GitClient(),
) : WorkspaceGitStatusReader {
    override fun read(worktreePath: Path): WorkspaceGitHealth {
        if (!Files.isDirectory(worktreePath)) return WorkspaceGitHealth(WorkspaceGitHealthState.MISSING)
        return runCatching {
            val status = git.run(worktreePath, "status", "--porcelain=v2", "-z", "--untracked-files=all")
            val dirty = PorcelainV2Parser.changedPaths(status.stdout).size
            val branchResult = git.run(worktreePath, "symbolic-ref", "--quiet", "--short", "HEAD", check = false)
            val branch = branchResult.stdout.trim()
            val remote = branch.takeIf(String::isNotBlank)?.let {
                git.run(worktreePath, "config", "--get", "branch.$it.remote", check = false)
            }
            val merge = branch.takeIf(String::isNotBlank)?.let {
                git.run(worktreePath, "config", "--get", "branch.$it.merge", check = false)
            }
            if (remote?.succeeded != true || merge?.succeeded != true) {
                WorkspaceGitHealth(WorkspaceGitHealthState.READY, dirty, LocalPushState.NO_UPSTREAM)
            } else {
                val remoteName = remote.stdout.trim()
                val mergeBranch = merge.stdout.trim().removePrefix("refs/heads/")
                val upstream = if (remoteName == ".") merge.stdout.trim() else "refs/remotes/$remoteName/$mergeBranch"
                val known = git.run(worktreePath, "rev-parse", "--verify", "$upstream^{commit}", check = false)
                if (!known.succeeded) {
                    WorkspaceGitHealth(WorkspaceGitHealthState.READY, dirty, LocalPushState.REMOTE_BRANCH_MISSING)
                } else {
                    val ahead = git.run(worktreePath, "rev-list", "--count", "$upstream..HEAD").stdout.trim().toInt()
                    WorkspaceGitHealth(
                        WorkspaceGitHealthState.READY,
                        dirty,
                        if (ahead == 0) LocalPushState.PUSHED else LocalPushState.AHEAD,
                        ahead,
                    )
                }
            }
        }.getOrElse { error ->
            WorkspaceGitHealth(
                WorkspaceGitHealthState.FAILED,
                pushState = LocalPushState.FAILED,
                message = error.message ?: "Git 状态检查失败",
            )
        }
    }
}

/** Parses NUL-delimited porcelain-v2 output and counts a rename as one destination path. */
object PorcelainV2Parser {
    fun changedPaths(output: String): Set<String> {
        val fields = output.split('\u0000')
        val paths = linkedSetOf<String>()
        var index = 0
        while (index < fields.size) {
            val record = fields[index]
            when {
                record.startsWith("1 ") -> pathAfterFields(record, 8)?.let(paths::add)
                record.startsWith("2 ") -> {
                    pathAfterFields(record, 9)?.let(paths::add)
                    index++
                }
                record.startsWith("u ") -> pathAfterFields(record, 10)?.let(paths::add)
                record.startsWith("? ") -> record.removePrefix("? ").takeIf(String::isNotEmpty)?.let(paths::add)
            }
            index++
        }
        return paths
    }

    private fun pathAfterFields(record: String, fieldCount: Int): String? =
        record.split(' ', limit = fieldCount + 1).getOrNull(fieldCount)?.takeIf(String::isNotEmpty)
}
