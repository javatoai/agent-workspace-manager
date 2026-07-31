package com.snowball.taskwt.core

import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.Path

class GitException(
    message: String,
    val result: CommandResult,
) : RuntimeException("$message\n${result.stderr.ifBlank { result.stdout }}")

data class WorktreeRecord(
    val path: Path,
    val head: String?,
    val branch: String?,
    val bare: Boolean = false,
    val detached: Boolean = false,
)

data class RepositoryStatus(
    val staged: Boolean,
    val unstaged: Boolean,
    val untracked: Boolean,
    val unpushedCommits: Int,
    val operationInProgress: String?,
) {
    val safeToArchive: Boolean
        get() = !staged && !unstaged && !untracked && unpushedCommits == 0 && operationInProgress == null

    /** Uncommitted work or in-progress git operation; unpushed commits alone do not count. */
    val hasUncommittedChanges: Boolean
        get() = staged || unstaged || untracked || operationInProgress != null
}

class GitClient(
    private val runner: CommandRunner = ProcessCommandRunner(),
) {
    fun run(
        repository: Path,
        vararg arguments: String,
        timeout: Duration = Duration.ofMinutes(10),
        check: Boolean = true,
    ): CommandResult {
        val result = runner.run(
            listOf("git", "-c", "core.longpaths=true", "-C", repository.toString()) + arguments,
            timeout = timeout,
        )
        if (check && !result.succeeded) {
            throw GitException("Git 命令失败：git ${arguments.joinToString(" ")}", result)
        }
        return result
    }

    fun commonDirectory(repository: Path): Path {
        val output = run(repository, "rev-parse", "--path-format=absolute", "--git-common-dir").stdout.trim()
        return Path(output).toAbsolutePath().normalize()
    }

    fun topLevel(repository: Path): Path =
        Path(run(repository, "rev-parse", "--show-toplevel").stdout.trim()).toAbsolutePath().normalize()

    fun currentBranch(repository: Path): String? =
        run(repository, "branch", "--show-current").stdout.trim().ifBlank { null }

    fun remoteUrl(repository: Path, remote: String = "origin"): String? =
        run(repository, "remote", "get-url", remote, check = false)
            .takeIf { it.succeeded }
            ?.stdout
            ?.trim()
            ?.ifBlank { null }

    fun worktrees(repository: Path): List<WorktreeRecord> {
        val records = mutableListOf<WorktreeRecord>()
        var path: Path? = null
        var head: String? = null
        var branch: String? = null
        var bare = false
        var detached = false

        fun flush() {
            val value = path ?: return
            records += WorktreeRecord(value, head, branch, bare, detached)
            path = null
            head = null
            branch = null
            bare = false
            detached = false
        }

        run(repository, "worktree", "list", "--porcelain").stdout.lineSequence().forEach { line ->
            when {
                line.isBlank() -> flush()
                line.startsWith("worktree ") -> {
                    flush()
                    path = Path(line.removePrefix("worktree ")).toAbsolutePath().normalize()
                }
                line.startsWith("HEAD ") -> head = line.removePrefix("HEAD ")
                line.startsWith("branch ") -> branch = line.removePrefix("branch ").removePrefix("refs/heads/")
                line == "bare" -> bare = true
                line == "detached" -> detached = true
            }
        }
        flush()
        return records
    }

    fun refExists(repository: Path, ref: String): Boolean =
        run(repository, "show-ref", "--verify", "--quiet", ref, check = false).succeeded

    fun resolve(repository: Path, ref: String): String =
        run(repository, "rev-parse", "--verify", "$ref^{commit}").stdout.trim()

    fun isAncestor(repository: Path, ancestor: String, descendant: String): Boolean =
        run(repository, "merge-base", "--is-ancestor", ancestor, descendant, check = false).succeeded

    fun fetch(repository: Path, remote: String = "origin") {
        run(repository, "fetch", "--prune", "--tags", "--force", remote, timeout = Duration.ofMinutes(5))
    }

    fun addWorktree(repository: Path, target: Path, branch: String, baseRef: String) {
        run(
            repository,
            "-c",
            "core.symlinks=false",
            "worktree",
            "add",
            "-b",
            branch,
            target.toString(),
            baseRef,
            timeout = Duration.ofMinutes(5),
        )
    }

    fun addExistingWorktree(repository: Path, target: Path, branch: String) {
        run(
            repository,
            "-c",
            "core.symlinks=false",
            "worktree",
            "add",
            target.toString(),
            branch,
            timeout = Duration.ofMinutes(5),
        )
    }

    fun addDetachedWorktree(repository: Path, target: Path, ref: String) {
        run(
            repository,
            "-c",
            "core.symlinks=false",
            "worktree",
            "add",
            "--detach",
            target.toString(),
            ref,
            timeout = Duration.ofMinutes(5),
        )
    }

    fun removeWorktree(repository: Path, target: Path, force: Boolean = false) {
        val args = buildList {
            add("worktree")
            add("remove")
            if (force) add("--force")
            add(target.toString())
        }
        run(repository, *args.toTypedArray(), timeout = Duration.ofMinutes(5))
    }

    fun status(repository: Path): RepositoryStatus {
        val lines = run(repository, "status", "--porcelain=v1", "--untracked-files=all").stdout.lines()
        val staged = lines.any { it.length >= 2 && it[0] != ' ' && it[0] != '?' }
        val unstaged = lines.any { it.length >= 2 && it[1] != ' ' && it[1] != '?' }
        val untracked = lines.any { it.startsWith("??") }
        val upstream = run(
            repository,
            "rev-parse",
            "--abbrev-ref",
            "--symbolic-full-name",
            "@{upstream}",
            check = false,
        )
        val unpushed = if (upstream.succeeded) {
            run(repository, "rev-list", "--count", "@{upstream}..HEAD").stdout.trim().toIntOrNull() ?: 0
        } else {
            run(repository, "rev-list", "--count", "HEAD", "--not", "--remotes").stdout
                .trim()
                .toIntOrNull() ?: 0
        }
        return RepositoryStatus(
            staged = staged,
            unstaged = unstaged,
            untracked = untracked,
            unpushedCommits = unpushed,
            operationInProgress = operationInProgress(repository),
        )
    }

    private fun operationInProgress(repository: Path): String? {
        val gitDirectory = Path(run(repository, "rev-parse", "--absolute-git-dir").stdout.trim())
        return when {
            gitDirectory.resolve("MERGE_HEAD").toFile().exists() -> "merge"
            gitDirectory.resolve("rebase-merge").toFile().exists() -> "rebase"
            gitDirectory.resolve("rebase-apply").toFile().exists() -> "rebase"
            gitDirectory.resolve("CHERRY_PICK_HEAD").toFile().exists() -> "cherry-pick"
            gitDirectory.resolve("REVERT_HEAD").toFile().exists() -> "revert"
            else -> null
        }
    }
}
