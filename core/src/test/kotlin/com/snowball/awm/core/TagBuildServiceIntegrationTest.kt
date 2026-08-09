package com.snowball.awm.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.Clock
import java.time.ZoneOffset

class TagBuildServiceIntegrationTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `pushes feature test branch and annotated tag without touching feature checkout`() {
        val (remote, seed) = GitTestSupport.createRemoteWithSeed(temporary.resolve("source"))
        GitTestSupport.run(seed, "branch", "release/test")
        GitTestSupport.run(seed, "push", "origin", "release/test")
        createAnnotatedTag(seed, "2.0.0", "2024-01-01T00:00:00Z")
        createAnnotatedTag(seed, "1.6.88", "2025-01-01T00:00:00Z")
        createAnnotatedTag(seed, "1.6.89.beta-10", "2026-01-01T00:00:00Z")
        GitTestSupport.run(seed, "push", "origin", "--tags")
        val repository = GitTestSupport.clone(remote, temporary.resolve("services").resolve("operation-center"))
        val repositoryInfo = GitRepositoryInspector().inspect(repository)
        val featureWorktree = temporary.resolve("tasks").resolve("TAG-1").resolve("operation-center")
        Files.createDirectories(featureWorktree.parent)
        GitClient().addWorktree(repository, featureWorktree, "feature/TAG-1", "origin/master")
        GitTestSupport.configureIdentity(featureWorktree)
        Files.writeString(featureWorktree.resolve("feature.txt"), "feature\n")
        GitTestSupport.run(featureWorktree, "add", "feature.txt")
        GitTestSupport.run(featureWorktree, "commit", "-m", "feature change")
        val featureSha = GitTestSupport.run(featureWorktree, "rev-parse", "HEAD")
        val taskDirectory = temporary.resolve("tasks").resolve("TAG-1")
        val now = Instant.now().toString()
        ManifestStore().save(
            taskDirectory,
            TaskManifest(
                folderName = "TAG-1",
                taskDirectoryName = "TAG-1",
                featureBranch = "feature/TAG-1",
                requirementLink = "https://example.com/req",
                createdAt = now,
                updatedAt = now,
                status = WorkspaceStatus.READY,
                services = listOf(
                    ServiceWorkspace(
                        repositoryId = repositoryInfo.id,
                        serviceName = "operation-center",
                        repositoryPath = repository.toString(),
                        worktreePath = featureWorktree.toString(),
                        ideType = IdeType.IDEA,
                        branch = "feature/TAG-1",
                        status = WorkspaceStatus.READY,
                        groupServiceId = "operation-center",
                    ),
                ),
            ),
        )
        val service = GroupServiceConfig.standard(
            id = "operation-center",
            repositoryId = repositoryInfo.id,
            displayName = "operation-center",
        ).copy(modules = listOf(ServiceModuleConfig("default", initialUatTag = "1.0.0.beta-1")))
        val config = AppConfig(
            taskRoot = temporary.resolve("tasks").toString(),
            repositories = listOf(repositoryInfo),
            groups = listOf(GroupConfig(DEFAULT_GROUP_ID, DEFAULT_GROUP_NAME, services = listOf(service))),
        )
        val builder = TagBuildService(
            paths = ApplicationPaths(temporary.resolve("app-home")),
            clock = Clock.fixed(Instant.parse("2026-08-07T03:26:08Z"), ZoneOffset.UTC),
        )

        val preview = builder.preflight(config, taskDirectory, repositoryInfo.id)
        assertEquals("1.6.89.beta-11", preview.estimatedTag)
        val result = builder.build(config, taskDirectory, repositoryInfo.id)

        assertEquals(TagOperationState.SUCCESS, result.state, result.message)
        assertEquals("1.6.89.beta-11", result.tag)
        assertEquals(featureSha, GitTestSupport.run(featureWorktree, "rev-parse", "HEAD"))
        assertTrue(Files.exists(featureWorktree.resolve("feature.txt")))
        val remoteFeature = GitTestSupport.run(repository, "ls-remote", "origin", "refs/heads/feature/TAG-1")
        assertTrue(remoteFeature.startsWith(featureSha))
        val remoteTestSha = GitTestSupport.run(repository, "ls-remote", "origin", "refs/heads/release/test")
            .substringBefore('\t')
        assertTrue(GitClient().isAncestor(repository, featureSha, remoteTestSha))
        assertTrue(
            GitTestSupport.run(repository, "ls-remote", "origin", "refs/tags/1.6.89.beta-11")
                .isNotBlank(),
        )
        assertTrue(Files.exists(taskDirectory.resolve("tag-build-history.jsonl")))
        val annotation = GitTestSupport.run(repository, "cat-file", "tag", "1.6.89.beta-11")
        assertTrue(
            annotation.contains(
                """UAT build
Task: TAG-1
需求链接：https://example.com/req
Builder: ${System.getProperty("user.name")}
时间：2026-08-07 11:26:08""",
            ),
        )
        assertTrue(!annotation.contains("Service:"))
        assertTrue(!annotation.contains("Feature:"))
        assertTrue(!annotation.contains("Test:"))
    }

    @Test
    fun `resumes partial operation with the same tag after remote tag push recovers`() {
        val (remote, seed) = GitTestSupport.createRemoteWithSeed(temporary.resolve("partial-source"))
        GitTestSupport.run(seed, "branch", "release/test")
        GitTestSupport.run(seed, "push", "origin", "release/test")
        val repository = GitTestSupport.clone(
            remote,
            temporary.resolve("partial-services").resolve("operation-center"),
        )
        val repositoryInfo = GitRepositoryInspector().inspect(repository)
        val taskDirectory = temporary.resolve("partial-tasks").resolve("TAG-PARTIAL")
        val featureWorktree = taskDirectory.resolve("operation-center")
        Files.createDirectories(featureWorktree.parent)
        GitClient().addWorktree(
            repository,
            featureWorktree,
            "feature/TAG-PARTIAL",
            "origin/master",
        )
        GitTestSupport.configureIdentity(featureWorktree)
        Files.writeString(featureWorktree.resolve("feature.txt"), "partial feature\n")
        GitTestSupport.run(featureWorktree, "add", "feature.txt")
        GitTestSupport.run(featureWorktree, "commit", "-m", "partial feature change")
        val featureSha = GitTestSupport.run(featureWorktree, "rev-parse", "HEAD")
        val now = Instant.now().toString()
        ManifestStore().save(
            taskDirectory,
            TaskManifest(
                folderName = "TAG-PARTIAL",
                taskDirectoryName = "TAG-PARTIAL",
                featureBranch = "feature/TAG-PARTIAL",
                createdAt = now,
                updatedAt = now,
                status = WorkspaceStatus.READY,
                services = listOf(
                    ServiceWorkspace(
                        repositoryId = repositoryInfo.id,
                        serviceName = "operation-center",
                        repositoryPath = repository.toString(),
                        worktreePath = featureWorktree.toString(),
                        ideType = IdeType.IDEA,
                        branch = "feature/TAG-PARTIAL",
                        status = WorkspaceStatus.READY,
                        groupServiceId = "operation-center",
                    ),
                ),
            ),
        )
        val config = AppConfig(
            taskRoot = temporary.resolve("partial-tasks").toString(),
            repositories = listOf(repositoryInfo),
            groups = listOf(
                GroupConfig(DEFAULT_GROUP_ID, DEFAULT_GROUP_NAME, services = listOf(
                    GroupServiceConfig.standard(
                        id = "operation-center",
                        repositoryId = repositoryInfo.id,
                        displayName = "operation-center",
                    ).copy(modules = listOf(ServiceModuleConfig("default", initialUatTag = "1.0.0.beta-1"))),
                )),
            ),
        )
        val builder = TagBuildService(
            paths = ApplicationPaths(temporary.resolve("partial-app-home")),
        )
        val hook = remote.resolve("hooks").resolve("pre-receive")
        Files.writeString(
            hook,
            """
            |#!/bin/sh
            |while read old new ref
            |do
            |  case "${'$'}ref" in
            |    refs/tags/*) exit 1 ;;
            |  esac
            |done
            |exit 0
            |
            """.trimMargin(),
        )
        // Git only executes receive hooks when the file is executable on POSIX hosts.
        // Windows Git does not require this bit, which previously hid the portability bug.
        hook.toFile().setExecutable(true)

        val partial = builder.build(config, taskDirectory, repositoryInfo.id)

        assertEquals(TagOperationState.PARTIAL, partial.state, partial.message)
        assertEquals("1.0.0.beta-1", partial.tag)
        val remoteTestSha = GitTestSupport.run(
            repository,
            "ls-remote",
            "origin",
            "refs/heads/release/test",
        ).substringBefore('\t')
        assertTrue(GitClient().isAncestor(repository, featureSha, remoteTestSha))
        assertTrue(
            GitTestSupport.run(repository, "ls-remote", "origin", "refs/tags/1.0.0.beta-1")
                .isBlank(),
        )

        Files.delete(hook)
        val resumed = builder.resumePartial(config, taskDirectory, partial.operationId)

        assertEquals(TagOperationState.SUCCESS, resumed.state, resumed.message)
        assertEquals(partial.operationId, resumed.operationId)
        assertEquals(partial.tag, resumed.tag)
        assertTrue(
            GitTestSupport.run(repository, "ls-remote", "origin", "refs/tags/1.0.0.beta-1")
                .isNotBlank(),
        )
    }

    private fun createAnnotatedTag(repository: Path, tag: String, timestamp: String) {
        val result = ProcessCommandRunner().run(
            command = listOf("git", "tag", "-a", tag, "-m", tag),
            workingDirectory = repository,
            environment = mapOf("GIT_COMMITTER_DATE" to timestamp),
        )
        assertEquals(0, result.exitCode, result.stderr)
    }
}
