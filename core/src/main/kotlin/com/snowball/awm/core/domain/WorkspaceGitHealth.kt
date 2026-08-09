package com.snowball.awm.core

enum class WorkspaceGitHealthState { CHECKING, READY, FAILED, MISSING }

enum class LocalPushState { PUSHED, AHEAD, REMOTE_BRANCH_MISSING, NO_UPSTREAM, FAILED }

data class WorkspaceGitHealth(
    val state: WorkspaceGitHealthState,
    val dirtyFileCount: Int = 0,
    val pushState: LocalPushState = LocalPushState.FAILED,
    val unpushedCommitCount: Int = 0,
    val message: String? = null,
)
