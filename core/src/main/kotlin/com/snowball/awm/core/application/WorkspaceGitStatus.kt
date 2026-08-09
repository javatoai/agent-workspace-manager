package com.snowball.awm.core

import java.nio.file.Path

fun interface WorkspaceGitStatusReader {
    fun read(worktreePath: Path): WorkspaceGitHealth
}

/** Deduplicates shared physical worktrees before invoking Git. */
class WorkspaceGitStatusService(
    private val reader: WorkspaceGitStatusReader,
) {
    fun inspect(workspaces: List<ServiceWorkspace>): Map<Path, WorkspaceGitHealth> = workspaces
        .map { Path.of(it.worktreePath).toAbsolutePath().normalize() }
        .distinct()
        .associateWith(reader::read)
}
