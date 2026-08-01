package com.snowball.taskwt.core

import java.nio.file.FileVisitResult
import java.nio.file.FileVisitOption
import java.nio.file.FileSystemLoopException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.EnumSet
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class RepositoryScanner(
    private val git: GitClient = GitClient(),
) {
    private val ignoredDirectoryNames = setOf(
        ".gradle",
        ".idea",
        ".git",
        "build",
        "out",
        "target",
        "node_modules",
        ".next",
        "dist",
    )

    fun scan(scanRoots: List<Path>, taskRoot: Path?): List<RepositoryInfo> {
        val excludedTaskRoot = taskRoot?.canonicalOrNormalized()
        val byCommonDirectory = linkedMapOf<Path, RepositoryInfo>()

        scanRoots.forEach { configuredRoot ->
            val root = configuredRoot.canonicalOrNormalized()
            if (!root.exists() || !root.isDirectory()) return@forEach
            Files.walkFileTree(
                root,
                // Do not recursively follow links. Direct links to repositories are
                // handled in visitFile below, while this prevents cycles and scans
                // escaping into arbitrary external directory trees.
                EnumSet.noneOf(FileVisitOption::class.java),
                Int.MAX_VALUE,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        directory: Path,
                        attributes: BasicFileAttributes,
                    ): FileVisitResult {
                        val canonical = directory.canonicalOrNormalized()
                        if (excludedTaskRoot != null && canonical.startsWith(excludedTaskRoot)) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        if (directory != root && directory.fileName.toString() in ignoredDirectoryNames) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        val dotGit = directory.resolve(".git")
                        if (Files.isRegularFile(dotGit)) {
                            // Linked worktrees and submodules use a .git pointer file.
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        if (dotGit.isDirectory()) {
                            runCatching { inspect(directory) }
                                .onSuccess { repository ->
                                    byCommonDirectory.putIfAbsent(
                                        Path.of(repository.gitCommonDirectory).canonicalOrNormalized(),
                                        repository,
                                    )
                                }
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(
                        file: Path,
                        exception: java.io.IOException,
                    ): FileVisitResult =
                        if (exception is FileSystemLoopException) FileVisitResult.CONTINUE
                        else super.visitFileFailed(file, exception)

                    override fun visitFile(
                        file: Path,
                        attributes: BasicFileAttributes,
                    ): FileVisitResult {
                        // A configured scan root may contain a direct directory link
                        // to a repository. Inspect that link without traversing it.
                        if (Files.isDirectory(file)) {
                            val canonical = file.canonicalOrNormalized()
                            if (excludedTaskRoot != null && canonical.startsWith(excludedTaskRoot)) {
                                return FileVisitResult.CONTINUE
                            }
                            val dotGit = file.resolve(".git")
                            if (dotGit.isDirectory()) {
                                runCatching { inspect(file) }
                                    .onSuccess { repository ->
                                        byCommonDirectory.putIfAbsent(
                                            Path.of(repository.gitCommonDirectory).canonicalOrNormalized(),
                                            repository,
                                        )
                                    }
                            }
                        }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        }
        return byCommonDirectory.values.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    private fun inspect(directory: Path): RepositoryInfo {
        val root = git.topLevel(directory).canonicalOrNormalized()
        val common = git.commonDirectory(root).canonicalOrNormalized()
        return RepositoryInfo(
            id = repositoryId(common),
            name = root.fileName.toString(),
            rootPath = root.toString(),
            gitCommonDirectory = common.toString(),
            remoteUrl = git.remoteUrl(root),
            currentBranch = git.currentBranch(root),
        )
    }

    private fun repositoryId(commonDirectory: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(commonDirectory.toString().lowercase(Locale.ROOT).toByteArray(StandardCharsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
        return "repo-$digest"
    }
}

internal fun Path.canonicalOrNormalized(): Path =
    runCatching { toRealPath() }.getOrElse { toAbsolutePath().normalize() }
