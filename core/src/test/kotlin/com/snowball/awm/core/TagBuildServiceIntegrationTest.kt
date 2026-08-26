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
import kotlin.test.assertFailsWith

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
                lifecycleStatus = TaskLifecycleStatus.ACTIVE,
                services = listOf(
                    ServiceWorkspace(
                        repositoryId = repositoryInfo.id,
                        serviceName = "operation-center",
                        repositoryPath = repository.toString(),
                        worktreePath = featureWorktree.toString(),
                        developmentTool = DevelopmentToolType.INTELLIJ_IDEA,
                        branch = "feature/TAG-1",
                        health = WorkspaceHealth.READY,
                        groupServiceId = "operation-center",
                        tagEnabled = true,
                        tagTargetRef = "origin/release/test",
                    ),
                ),
            ),
        )
        val service = GroupServiceConfig.standard(
            id = "operation-center",
            repositoryId = repositoryInfo.id,
            displayName = "operation-center",
        ).copy(modules = listOf(ServiceModuleConfig("default")))
        val config = AppConfig(
            taskRoot = temporary.resolve("tasks").toString(),
            repositories = listOf(repositoryInfo),
            groups = listOf(GroupConfig(DEFAULT_GROUP_ID, DEFAULT_GROUP_NAME, services = listOf(service))),
        )
        val applicationPaths = ApplicationPaths(temporary.resolve("app-home"))
        val builder = TagBuildService(
            paths = applicationPaths,
            clock = Clock.fixed(Instant.parse("2026-08-07T03:26:08Z"), ZoneOffset.UTC),
        )

        RepositoryOperationLock(applicationPaths).withLock(GitClient().commonDirectory(repository)) {
            assertFailsWith<IllegalStateException> {
                builder.preflight(config, taskDirectory, repositoryInfo.id)
            }
        }

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
                """Tag build
Task: TAG-1
需求链接：https://example.com/req
Builder: ${System.getProperty("user.name")}
时间：2026-08-07 11:26:08""",
            ),
        )
        assertTrue(!annotation.contains("Service:"))
        assertTrue(!annotation.contains("Feature:"))
        assertTrue(!annotation.contains("Test:"))

        // Simulate a process stop immediately after the source branch was
        // pushed. The retry must keep one operation record and safely rerun
        // the complete flow rather than creating a duplicate history row.
        val interrupted = result.copy(
            state = TagOperationState.SOURCE_BRANCH_PUSHED,
            tag = null,
            targetSha = null,
            message = null,
        )
        TagOperationStore().save(taskDirectory, interrupted)

        val resumed = builder.resumeInterrupted(config, taskDirectory, interrupted.operationId)

        assertEquals(TagOperationState.SUCCESS, resumed.state, resumed.message)
        assertEquals(interrupted.operationId, resumed.operationId)
        assertEquals(interrupted.createdAt, resumed.createdAt)
        assertEquals("1.6.89.beta-12", resumed.tag)
        assertTrue(
            GitTestSupport.run(repository, "ls-remote", "origin", "refs/tags/1.6.89.beta-12")
                .isNotBlank(),
        )
    }

    @Test
    fun `resumes partial operation with the same tag after remote tag push recovers`() {
        val (remote, seed) = GitTestSupport.createRemoteWithSeed(temporary.resolve("partial-source"))
        GitTestSupport.run(seed, "branch", "release/test")
        GitTestSupport.run(seed, "push", "origin", "release/test")
        createAnnotatedTag(seed, "1.0.0.beta-0", "2025-01-01T00:00:00Z")
        GitTestSupport.run(seed, "push", "origin", "--tags")
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
                lifecycleStatus = TaskLifecycleStatus.ACTIVE,
                services = listOf(
                    ServiceWorkspace(
                        repositoryId = repositoryInfo.id,
                        serviceName = "operation-center",
                        repositoryPath = repository.toString(),
                        worktreePath = featureWorktree.toString(),
                        developmentTool = DevelopmentToolType.INTELLIJ_IDEA,
                        branch = "feature/TAG-PARTIAL",
                        health = WorkspaceHealth.READY,
                        groupServiceId = "operation-center",
                        tagEnabled = true,
                        tagTargetRef = "origin/release/test",
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
                    ).copy(modules = listOf(ServiceModuleConfig("default"))),
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

    @Test
    fun `current branch mode pushes source branch and tags its head without target branch`() {
        val (remote, seed) = GitTestSupport.createRemoteWithSeed(temporary.resolve("direct-source"))
        val repository = GitTestSupport.clone(remote, temporary.resolve("direct-services").resolve("api"))
        val repositoryInfo = GitRepositoryInspector().inspect(repository)
        val taskDirectory = temporary.resolve("direct-tasks").resolve("TAG-DIRECT")
        val worktree = taskDirectory.resolve("api")
        Files.createDirectories(worktree.parent)
        GitClient().addWorktree(repository, worktree, "feature/TAG-DIRECT", "origin/master")
        GitTestSupport.configureIdentity(worktree)
        Files.writeString(worktree.resolve("direct.txt"), "direct\n")
        GitTestSupport.run(worktree, "add", "direct.txt")
        GitTestSupport.run(worktree, "commit", "-m", "direct tag")
        val sourceSha = GitTestSupport.run(worktree, "rev-parse", "HEAD")
        val now = Instant.now().toString()
        ManifestStore().save(
            taskDirectory,
            TaskManifest(
                folderName = "TAG-DIRECT",
                taskDirectoryName = "TAG-DIRECT",
                featureBranch = "feature/TAG-DIRECT",
                createdAt = now,
                updatedAt = now,
                services = listOf(
                    ServiceWorkspace(
                        repositoryId = repositoryInfo.id,
                        serviceName = "api",
                        repositoryPath = repository.toString(),
                        worktreePath = worktree.toString(),
                        developmentTool = DevelopmentToolType.INTELLIJ_IDEA,
                        branch = "feature/TAG-DIRECT",
                        health = WorkspaceHealth.READY,
                        groupServiceId = "api",
                        pushRemote = "origin",
                        tagEnabled = true,
                        tagMode = TagBuildMode.CURRENT_BRANCH,
                        tagTargetRef = null,
                    ),
                ),
            ),
        )
        val module = ServiceModuleConfig(
            id = "default",
            tagMode = TagBuildMode.CURRENT_BRANCH,
            tagTargetRef = null,
        )
        val config = AppConfig(
            taskRoot = temporary.resolve("direct-tasks").toString(),
            repositories = listOf(repositoryInfo),
            groups = listOf(
                GroupConfig(
                    DEFAULT_GROUP_ID,
                    DEFAULT_GROUP_NAME,
                    services = listOf(
                        GroupServiceConfig.standard("api", repositoryInfo.id, "api")
                            .copy(modules = listOf(module)),
                    ),
                ),
            ),
        )
        val builder = TagBuildService(paths = ApplicationPaths(temporary.resolve("direct-app-home")))

        val noTagError = assertFailsWith<IllegalStateException> {
            builder.preflight(config, taskDirectory, repositoryInfo.id)
        }
        assertTrue(noTagError.message.orEmpty().contains("仓库没有可用的历史 Tag"))

        createAnnotatedTag(seed, "1.0.0.beta-0", "2025-01-01T00:00:00Z")
        GitTestSupport.run(seed, "push", "origin", "--tags")

        val preview = builder.preflight(config, taskDirectory, repositoryInfo.id)
        val result = builder.build(config, taskDirectory, repositoryInfo.id)

        assertEquals(TagBuildMode.CURRENT_BRANCH, preview.tagMode)
        assertEquals(null, preview.targetBranch)
        assertEquals(TagOperationState.SUCCESS, result.state, result.message)
        assertEquals(null, result.targetBranch)
        assertEquals(sourceSha, GitTestSupport.run(repository, "ls-remote", "origin", "refs/heads/feature/TAG-DIRECT").substringBefore('\t'))
        assertTrue(GitTestSupport.run(repository, "ls-remote", "origin", "refs/tags/1.0.0.beta-1").isNotBlank())
        assertTrue(!Files.exists(temporary.resolve("direct-app-home").resolve("temp").resolve("tag-build")))
    }

    @Test
    fun `already merged protected target allows tag only delivery`() {
        val root = temporary.resolve("tag-only-protected-target")
        val (remote, seed) = GitTestSupport.createRemoteWithSeed(root)
        createAnnotatedTag(seed, "1.0.0.beta-0", "2025-01-01T00:00:00Z")
        GitTestSupport.run(seed, "push", "origin", "--tags")
        val repository = GitTestSupport.clone(remote, root.resolve("repository"))
        val repositoryInfo = GitRepositoryInspector().inspect(repository)
        val taskDirectory = root.resolve("tasks/TAG-ONLY")
        val worktree = taskDirectory.resolve("service")
        Files.createDirectories(taskDirectory)
        GitClient().addWorktree(repository, worktree, "feature/tag-only", "origin/master")
        val now = Instant.now().toString()
        ManifestStore().save(
            taskDirectory,
            TaskManifest(
                folderName = "TAG-ONLY",
                taskDirectoryName = "TAG-ONLY",
                featureBranch = "feature/tag-only",
                createdAt = now,
                updatedAt = now,
                services = listOf(
                    ServiceWorkspace(
                        repositoryId = repositoryInfo.id,
                        serviceName = "service",
                        repositoryPath = repository.toString(),
                        worktreePath = worktree.toString(),
                        developmentTool = DevelopmentToolType.INTELLIJ_IDEA,
                        branch = "feature/tag-only",
                        health = WorkspaceHealth.READY,
                        groupServiceId = "service",
                        moduleId = "default",
                        moduleName = "default",
                        baseRef = "origin/master",
                        targetBranch = "feature/tag-only",
                        tagEnabled = true,
                        tagMode = TagBuildMode.MERGE_TO_TARGET_BRANCH,
                        tagTargetRef = "origin/master",
                    ),
                ),
            ),
        )
        val config = AppConfig(
            taskRoot = root.resolve("tasks").toString(),
            repositories = listOf(repositoryInfo),
            groups = listOf(
                GroupConfig(
                    DEFAULT_GROUP_ID,
                    DEFAULT_GROUP_NAME,
                    services = listOf(
                        GroupServiceConfig.standard("service", repositoryInfo.id, "service").copy(
                            modules = listOf(ServiceModuleConfig("default", tagTargetRef = "origin/master")),
                        ),
                    ),
                ),
            ),
            blockedGitWriteBranches = listOf("master"),
        )
        val builder = TagBuildService(paths = ApplicationPaths(root.resolve("app-home")))

        val preview = builder.preflight(config, taskDirectory, "service:default")
        val result = builder.build(config, taskDirectory, "service:default")

        assertEquals(MergeMode.ALREADY_MERGED, preview.mergeMode)
        assertEquals(TagOperationState.SUCCESS, result.state, result.message)
        assertTrue(GitTestSupport.run(repository, "ls-remote", "origin", "refs/tags/1.0.0.beta-1").isNotBlank())
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
