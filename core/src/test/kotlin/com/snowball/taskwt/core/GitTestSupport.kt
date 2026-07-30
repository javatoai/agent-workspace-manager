package com.snowball.taskwt.core

import java.nio.file.Files
import java.nio.file.Path

internal object GitTestSupport {
    fun run(directory: Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", directory.toString()) + arguments)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) {
            "git ${arguments.joinToString(" ")} failed in $directory:\n$output"
        }
        return output.trim()
    }

    fun createRemoteWithSeed(root: Path): Pair<Path, Path> {
        val remote = root.resolve("remote.git")
        val seed = root.resolve("seed")
        Files.createDirectories(remote)
        Files.createDirectories(seed)
        run(root, "init", "--bare", remote.toString())
        run(root, "init", "-b", "master", seed.toString())
        configureIdentity(seed)
        Files.writeString(seed.resolve("README.md"), "seed\n")
        run(seed, "add", "README.md")
        run(seed, "commit", "-m", "seed")
        run(seed, "remote", "add", "origin", remote.toString())
        run(seed, "push", "-u", "origin", "master")
        run(remote, "symbolic-ref", "HEAD", "refs/heads/master")
        return remote to seed
    }

    fun clone(remote: Path, target: Path): Path {
        Files.createDirectories(target.parent)
        run(target.parent, "clone", remote.toString(), target.toString())
        configureIdentity(target)
        return target
    }

    fun configureIdentity(repository: Path) {
        run(repository, "config", "user.name", "TaskWT Tests")
        run(repository, "config", "user.email", "taskwt-tests@example.invalid")
    }
}
