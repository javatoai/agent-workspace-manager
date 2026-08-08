package com.snowball.taskwt.core

import java.nio.file.Files
import java.nio.file.Path

/** Read-only branch source used by the service editor; implementations must not fetch or mutate a repository. */
interface RemoteBranchCatalog {
    fun list(repository: Path, remote: String = "origin"): List<String>
}

class GitRemoteBranchCatalog(
    private val git: GitClient = GitClient(),
) : RemoteBranchCatalog {
    override fun list(repository: Path, remote: String): List<String> = git
        .run(repository, "ls-remote", "--heads", remote)
        .stdout
        .lineSequence()
        .mapNotNull { line -> line.substringAfter("refs/heads/", "").trim().ifBlank { null } }
        .distinct()
        .sorted()
        .toList()
}

/** Stable case-insensitive filtering used by the editable branch dropdown. */
object RemoteBranchSearch {
    fun filter(branches: List<String>, query: String): List<String> = branches
        .filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) }
}

/** Recommends an editor from root-level marker files only; the persisted user choice always wins afterwards. */
fun interface IdeRecommendationService {
    fun recommend(repositoryRoot: Path): IdeType
}

class RootMarkerIdeRecommendationService : IdeRecommendationService {
    override fun recommend(repositoryRoot: Path): IdeType {
        val jvmMarkers = listOf("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts", "pom.xml")
        if (jvmMarkers.any { Files.exists(repositoryRoot.resolve(it)) }) return IdeType.IDEA
        val webMarkers = listOf("package.json", "pnpm-workspace.yaml", "yarn.lock", "package-lock.json", "bun.lock", "bun.lockb")
        return if (webMarkers.any { Files.exists(repositoryRoot.resolve(it)) }) IdeType.WEBSTORM else IdeType.IDEA
    }
}
