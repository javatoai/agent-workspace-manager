package com.snowball.taskwt.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopConfigurationServicesTest {
    @Test
    fun `IDE recommendation checks root markers and gives JVM priority`() {
        val root = Files.createTempDirectory("taskwt-ide-recommendation")
        val service = RootMarkerIdeRecommendationService()

        assertEquals(IdeType.IDEA, service.recommend(root))
        Files.writeString(root.resolve("package.json"), "{}")
        assertEquals(IdeType.WEBSTORM, service.recommend(root))
        Files.writeString(root.resolve("build.gradle.kts"), "")
        assertEquals(IdeType.IDEA, service.recommend(root))
    }

    @Test
    fun `remote branch catalog lists origin heads without refs prefix`() {
        val commands = mutableListOf<List<String>>()
        val runner = object : CommandRunner {
            override fun run(command: List<String>, workingDirectory: Path?, timeout: Duration, environment: Map<String, String>): CommandResult {
                commands += command
                return CommandResult(0, "abc\trefs/heads/main\ndef\trefs/heads/release/test\nabc\trefs/heads/main\n", "")
            }
        }
        val catalog = GitRemoteBranchCatalog(GitClient(runner))

        assertEquals(listOf("main", "release/test"), catalog.list(Path.of("C:/repo")))
        assertEquals(listOf("git", "-c", "core.longpaths=true", "-C", "C:\\repo", "ls-remote", "--heads", "origin"), commands.single())
    }

    @Test
    fun `remote branch search keeps slash names and filters case insensitively`() {
        val branches = listOf("main", "release/test", "feature/ABC")

        assertEquals(listOf("release/test"), RemoteBranchSearch.filter(branches, "TEST"))
        assertEquals(branches, RemoteBranchSearch.filter(branches, ""))
    }
}
