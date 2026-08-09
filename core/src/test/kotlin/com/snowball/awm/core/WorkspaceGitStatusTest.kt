package com.snowball.awm.core

import java.nio.file.Path
import java.nio.file.Files
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkspaceGitStatusTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `porcelain parser counts staged unstaged untracked conflict and rename once`() {
        val output = listOf(
            "1 M. N... 100644 100644 100644 a a staged.txt",
            "1 .M N... 100644 100644 100644 a a unstaged name.txt",
            "u UU N... 100644 100644 100644 100644 a a a conflict.txt",
            "? untracked.txt",
            "2 R. N... 100644 100644 100644 a a R100 renamed.txt",
            "old.txt",
            "! ignored.txt",
            "",
        ).joinToString("\u0000")
        assertEquals(
            setOf("staged.txt", "unstaged name.txt", "conflict.txt", "untracked.txt", "renamed.txt"),
            PorcelainV2Parser.changedPaths(output),
        )
    }

    @Test
    fun `shared physical worktree is inspected once`() {
        var reads = 0
        val service = WorkspaceGitStatusService { path ->
            reads++
            WorkspaceGitHealth(WorkspaceGitHealthState.READY, message = path.toString())
        }
        val path = Path.of("build/shared").toAbsolutePath().normalize().toString()
        val workspace = ServiceWorkspace("repo", "service", "repo", path, IdeType.IDEA, "feature/x")
        val result = service.inspect(listOf(workspace, workspace.copy(moduleId = "other")))
        assertEquals(1, reads)
        assertEquals(1, result.size)
    }

    @Test
    fun `reader reports untracked files and local commits without contacting remote`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("source"))
        val repository = GitTestSupport.clone(remote, temporary.resolve("clone"))
        val reader = GitWorkspaceGitStatusReader()

        assertEquals(LocalPushState.PUSHED, reader.read(repository).pushState)
        Files.writeString(repository.resolve("untracked.txt"), "local")
        assertEquals(1, reader.read(repository).dirtyFileCount)

        GitTestSupport.run(repository, "add", "untracked.txt")
        GitTestSupport.run(repository, "commit", "-m", "local commit")
        val ahead = reader.read(repository)
        assertEquals(LocalPushState.AHEAD, ahead.pushState)
        assertEquals(1, ahead.unpushedCommitCount)

        GitTestSupport.run(repository, "switch", "-c", "feature/no-upstream")
        assertEquals(LocalPushState.NO_UPSTREAM, reader.read(repository).pushState)

        GitTestSupport.run(repository, "config", "branch.feature/no-upstream.remote", "origin")
        GitTestSupport.run(repository, "config", "branch.feature/no-upstream.merge", "refs/heads/missing")
        assertEquals(LocalPushState.REMOTE_BRANCH_MISSING, reader.read(repository).pushState)
    }
}
