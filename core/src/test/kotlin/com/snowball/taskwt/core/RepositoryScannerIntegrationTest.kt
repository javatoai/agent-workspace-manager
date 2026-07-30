package com.snowball.taskwt.core

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class RepositoryScannerIntegrationTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `finds main repositories and ignores task root and linked worktrees`() {
        val scanRoot = temporary.resolve("services")
        val taskRoot = scanRoot.resolve("managed-tasks")
        Files.createDirectories(scanRoot)
        Files.createDirectories(taskRoot)
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("source"))
        val first = GitTestSupport.clone(remote, scanRoot.resolve("service-a"))
        GitTestSupport.run(
            first,
            "worktree",
            "add",
            "--detach",
            taskRoot.resolve("task-a").toString(),
            "HEAD",
        )
        GitTestSupport.run(
            first,
            "worktree",
            "add",
            "--detach",
            scanRoot.resolve("external-worktree").toString(),
            "HEAD",
        )
        Files.createDirectories(scanRoot.resolve("build").resolve("nested"))
        GitTestSupport.run(scanRoot.resolve("build"), "init", "nested")

        val repositories = RepositoryScanner().scan(listOf(scanRoot), taskRoot)

        assertEquals(1, repositories.size)
        assertEquals("service-a", repositories.single().name)
        assertTrue(Path.of(repositories.single().rootPath).endsWith("service-a"))
    }

    @Test
    fun `finds repositories exposed through directory links`() {
        val scanRoot = temporary.resolve("linked-services")
        Files.createDirectories(scanRoot)
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("linked-source"))
        val repository = GitTestSupport.clone(
            remote,
            temporary.resolve("actual-services").resolve("service-linked"),
        )
        createDirectoryLink(scanRoot.resolve("service-linked"), repository)

        val repositories = RepositoryScanner().scan(listOf(scanRoot), null)

        assertEquals(1, repositories.size)
        assertEquals("service-linked", repositories.single().name)
        assertEquals(repository.toRealPath(), Path.of(repositories.single().rootPath).toRealPath())
    }

    @Test
    fun `follows symbolic link repositories without failing on link cycles`() {
        val inspectedRepository = temporary.resolve("inspected").resolve("service-symbolic")
        Files.createDirectories(inspectedRepository.resolve(".git"))
        val runner = object : CommandRunner {
            override fun run(
                command: List<String>,
                workingDirectory: Path?,
                timeout: Duration,
                environment: Map<String, String>,
            ): CommandResult = when {
                "--show-toplevel" in command ->
                    CommandResult(0, "${inspectedRepository.toAbsolutePath()}\n", "")
                "--git-common-dir" in command ->
                    CommandResult(0, "${inspectedRepository.resolve(".git").toAbsolutePath()}\n", "")
                "get-url" in command -> CommandResult(2, "", "no remote")
                "--show-current" in command -> CommandResult(0, "main\n", "")
                else -> error("Unexpected Git command: ${command.joinToString(" ")}")
            }
        }

        Jimfs.newFileSystem(Configuration.unix()).use { fileSystem ->
            val scanRoot = fileSystem.getPath("/scan")
            val linkedRepository = fileSystem.getPath("/repositories/service-symbolic")
            Files.createDirectories(scanRoot)
            Files.createDirectories(linkedRepository.resolve(".git"))
            Files.createSymbolicLink(scanRoot.resolve("service-symbolic"), linkedRepository)
            Files.createSymbolicLink(scanRoot.resolve("cycle"), scanRoot)

            val repositories = RepositoryScanner(GitClient(runner)).scan(listOf(scanRoot), null)

            assertEquals(1, repositories.size)
            assertEquals("service-symbolic", repositories.single().name)
        }
    }

    private fun createDirectoryLink(link: Path, target: Path) {
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            val result = ProcessCommandRunner().run(
                listOf("cmd", "/c", "mklink", "/J", link.toString(), target.toString()),
            )
            assertEquals(0, result.exitCode, result.stderr.ifBlank { result.stdout })
        } else {
            Files.createSymbolicLink(link, target.toAbsolutePath())
        }
    }
}
