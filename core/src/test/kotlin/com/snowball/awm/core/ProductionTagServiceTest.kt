package com.snowball.awm.core

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProductionTagServiceTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `pipeline creates release merges an ordered feature batch and builds the formal tag`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val config = managedConfig(repository)
        val git = FakeProductionGitGateway()
        val service = ProductionTagService(
            store = ProductionTagPipelineStore(ApplicationPaths(temporary.resolve("home"))),
            versions = ProductionVersionProvider {
                ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
            },
            git = git,
            now = { "2026-08-24T10:00:00+08:00" },
            id = { "pipeline-1" },
        )

        val created = service.create(config, repository.id)
        val released = service.createRelease(config, created.id)
        val selected = service.selectFeatures(config, created.id, listOf("feature/a", "feature/b"))
        val merged = service.mergeFeatures(config, created.id)
        val built = service.buildConfirmedTag(config, created.id)

        assertEquals(ProductionBaselineState.ALREADY_CONTAINED, created.baselineState)
        assertEquals("3.11.71", created.baseVersion)
        assertEquals("master-sha", released.releaseSha)
        assertEquals(listOf("feature/a", "feature/b"), selected.selectedFeatures.map { it.branch })
        assertEquals(ProductionFeatureBatchState.MERGED, merged.featureState)
        assertEquals(listOf("feature/a", "feature/b"), merged.mergedFeatures.map { it.branch })
        assertEquals(ProductionTagBuildState.PUSHED, built.buildRecords.single().state)
        assertEquals("3.11.71", built.buildRecords.single().expectedTag)
        assertEquals(listOf("feature/a", "feature/b"), git.mergedBranches)
    }

    @Test
    fun `feature conflict stops the batch without recording successful merges`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git")
        val config = managedConfig(repository)
        val git = FakeProductionGitGateway(conflict = ProductionConflict("feature/b", listOf("src/App.kt"), "sha-b"))
        val service = ProductionTagService(
            store = ProductionTagPipelineStore(ApplicationPaths(temporary.resolve("conflict-home"))),
            versions = ProductionVersionProvider {
                ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
            },
            git = git,
            now = { "now" },
            id = { "pipeline-conflict" },
        )
        val pipeline = service.create(config, repository.id)
        service.createRelease(config, pipeline.id)
        service.selectFeatures(config, pipeline.id, listOf("feature/a", "feature/b"))

        val result = service.mergeFeatures(config, pipeline.id)

        assertEquals(ProductionFeatureBatchState.CONFLICT, result.featureState)
        assertEquals("feature/b", result.conflicts.single().branch)
        assertTrue(result.mergedFeatures.isEmpty())
    }

    @Test
    fun `a later feature batch excludes a previously merged unchanged branch`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val config = managedConfig(repository)
        val git = FakeProductionGitGateway()
        val service = ProductionTagService(
            store = ProductionTagPipelineStore(ApplicationPaths(temporary.resolve("incremental-feature-home"))),
            versions = ProductionVersionProvider {
                ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
            },
            git = git,
            now = { "now" },
            id = { "incremental" },
        )
        val pipeline = service.create(config, repository.id)
        service.createRelease(config, pipeline.id)
        service.selectFeatures(config, pipeline.id, listOf("feature/a"))
        service.mergeFeatures(config, pipeline.id)

        val selected = service.selectFeatures(config, pipeline.id, listOf("feature/a", "feature/b"))
        val merged = service.mergeFeatures(config, pipeline.id)

        assertEquals(listOf("feature/b"), selected.selectedFeatures.map { it.branch })
        assertEquals(listOf("feature/a", "feature/b"), merged.mergedFeatures.map { it.branch })
        assertEquals(listOf("feature/b"), git.mergedBranches)
    }

    @Test
    fun `merge request refresh records the actual target head instead of the source commit`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git")
        val config = managedConfig(repository)
        val git = FakeProductionGitGateway(
            baselineState = ProductionBaselineState.MERGE_REQUIRED,
            mergedTargetSha = "master-with-later-commits",
        )
        val service = ProductionTagService(
            store = ProductionTagPipelineStore(ApplicationPaths(temporary.resolve("mr-home"))),
            versions = ProductionVersionProvider {
                ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
            },
            git = git,
            now = { "now" },
            id = { "pipeline-mr" },
        )

        val created = service.create(config, repository.id)
        val awaiting = service.mergeProduction(config, created.id)
        val refreshed = service.refreshMergeRequest(config, created.id)

        assertEquals(ProductionBaselineState.AWAITING_MERGE_REQUEST, awaiting.baselineState)
        assertEquals("GITLAB", awaiting.auditEvents.last().mergeRequestPlatform)
        assertEquals("https://gitlab.example/mr", awaiting.auditEvents.last().mergeRequestUrl)
        assertEquals(ProductionBaselineState.ALREADY_CONTAINED, refreshed.baselineState)
        assertEquals("master-with-later-commits", refreshed.masterSha)
    }

    @Test
    fun `production drift cancels merge and persists the refreshed baseline`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val config = managedConfig(repository)
        var runtimeTag = "3.11.70"
        val git = FakeProductionGitGateway(baselineState = ProductionBaselineState.MERGE_REQUIRED)
        val store = ProductionTagPipelineStore(ApplicationPaths(temporary.resolve("drift-home")))
        val service = ProductionTagService(
            store = store,
            versions = ProductionVersionProvider {
                ProductionRuntimeSnapshot("svc", "PRD", runtimeTag, listOf(ProductionPodSnapshot("pod", runtimeTag, "Running", true, 0)))
            },
            git = git,
            now = { "now" },
            id = { "pipeline-drift" },
        )
        val created = service.create(config, repository.id)
        runtimeTag = "3.11.71"
        git.formalTagValues = listOf("3.11.70", "3.11.71")

        val error = assertFailsWith<ProductionBaselineChangedException> {
            service.mergeProduction(config, created.id)
        }

        assertEquals("3.11.71", error.refreshed.productionTag)
        assertEquals("3.11.72", error.refreshed.baseVersion)
        assertEquals("release/3.11.72", error.refreshed.releaseBranch)
        assertEquals("3.11.71", store.get(created.id)?.productionTag)
        assertEquals(0, git.productionMergeCalls)
    }

    @Test
    fun `tag gateway interruption leaves a lease that resume reconciles safely`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val config = managedConfig(repository)
        val git = FakeProductionGitGateway(throwDuringTagPush = true)
        val store = ProductionTagPipelineStore(ApplicationPaths(temporary.resolve("tag-crash-home")))
        val service = ProductionTagService(
            store = store,
            versions = ProductionVersionProvider {
                ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
            },
            git = git,
            now = { "now" },
            id = { "pipeline-tag-crash" },
        )
        val pipeline = service.create(config, repository.id)
        service.createRelease(config, pipeline.id)
        service.selectFeatures(config, pipeline.id, listOf("feature/a"))
        service.mergeFeatures(config, pipeline.id)

        assertFailsWith<IllegalStateException> { service.buildConfirmedTag(config, pipeline.id) }

        val interrupted = store.get(pipeline.id)!!
        assertEquals(ProductionTagBuildState.PUSHING, interrupted.buildRecords.single().state)
        assertEquals(ProductionOperationAction.BUILD_TAG, interrupted.activeOperation?.action)
        assertFailsWith<IllegalStateException> { service.close(pipeline.id) }

        val recovered = service.resume(config, pipeline.id)
        val record = recovered.buildRecords.single()
        assertEquals(ProductionTagBuildState.FAILED, record.state)
        assertEquals("3.11.71", record.expectedTag)
        assertTrue(record.failureReason.orEmpty().contains("远端 Tag 不存在"))
        assertEquals(null, recovered.activeOperation)
    }

    @Test
    fun `process exit after tag push is reconciled as pushed on resume`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val config = managedConfig(repository)
        val git = FakeProductionGitGateway()
        var interrupt = true
        val store = ProductionTagPipelineStore(ApplicationPaths(temporary.resolve("tag-post-push-crash-home")))
        val service = ProductionTagService(
            store = store,
            versions = ProductionVersionProvider {
                ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
            },
            git = git,
            now = { "now" },
            id = { "pipeline-post-push-crash" },
            afterRemoteWrite = { action ->
                if (interrupt && action == ProductionOperationAction.BUILD_TAG) error("simulated process exit")
            },
        )
        val pipeline = service.create(config, repository.id)
        service.createRelease(config, pipeline.id)
        service.selectFeatures(config, pipeline.id, listOf("feature/a"))
        service.mergeFeatures(config, pipeline.id)

        assertFailsWith<IllegalStateException> { service.buildConfirmedTag(config, pipeline.id) }
        interrupt = false
        val recovered = service.resume(config, pipeline.id)

        assertEquals(ProductionTagBuildState.PUSHED, recovered.buildRecords.single().state)
        assertEquals("release-with-features", recovered.buildRecords.single().remoteTagSha)
        assertEquals(ProductionAuditState.RECOVERED, recovered.auditEvents.last().state)
        assertEquals(null, recovered.activeOperation)
    }

    @Test
    fun `uncertain tag push result keeps the lease and resumes from remote evidence`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val config = managedConfig(repository)
        val git = FakeProductionGitGateway(throwAfterTagWrite = true)
        val service = ProductionTagService(
            store = ProductionTagPipelineStore(ApplicationPaths(temporary.resolve("uncertain-tag-push-home"))),
            versions = ProductionVersionProvider {
                ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
            },
            git = git,
            now = { "now" },
            id = { "uncertain-tag-push" },
        )
        val pipeline = service.create(config, repository.id)
        service.createRelease(config, pipeline.id)
        service.selectFeatures(config, pipeline.id, listOf("feature/a"))
        service.mergeFeatures(config, pipeline.id)

        assertFailsWith<IllegalStateException> { service.buildConfirmedTag(config, pipeline.id) }
        assertEquals(ProductionOperationAction.BUILD_TAG, service.get(pipeline.id).activeOperation?.action)

        val recovered = service.resume(config, pipeline.id)
        assertEquals(ProductionTagBuildState.PUSHED, recovered.buildRecords.single().state)
        assertEquals(ProductionAuditState.RECOVERED, recovered.auditEvents.last().state)
    }

    @Test
    fun `a live remote operation cannot be reconciled by another process`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val config = managedConfig(repository)
        val paths = ApplicationPaths(temporary.resolve("live-operation-home"))
        val enteredPush = CountDownLatch(1)
        val allowPush = CountDownLatch(1)
        val git = FakeProductionGitGateway(onTagPush = {
            enteredPush.countDown()
            check(allowPush.await(10, TimeUnit.SECONDS)) { "test did not release tag push" }
        })
        val provider = ProductionVersionProvider {
            ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
        }
        val first = ProductionTagService(ProductionTagPipelineStore(paths), provider, git, now = { "now" }, id = { "live-operation" })
        val pipeline = first.create(config, repository.id)
        first.createRelease(config, pipeline.id)
        first.selectFeatures(config, pipeline.id, listOf("feature/a"))
        first.mergeFeatures(config, pipeline.id)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val build = executor.submit<ProductionTagPipeline> { first.buildConfirmedTag(config, pipeline.id) }
            assertTrue(enteredPush.await(10, TimeUnit.SECONDS))
            val second = ProductionTagService(ProductionTagPipelineStore(paths), provider, git)

            val error = assertFailsWith<IllegalStateException> { second.resume(config, pipeline.id) }

            assertTrue(error.message.orEmpty().contains("另一个 AWM 实例执行"))
            allowPush.countDown()
            assertEquals(ProductionTagBuildState.PUSHED, build.get(10, TimeUnit.SECONDS).buildRecords.single().state)
        } finally {
            allowPush.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `release write lease blocks another instance and recovers the remote branch`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val config = managedConfig(repository)
        val git = FakeProductionGitGateway()
        val paths = ApplicationPaths(temporary.resolve("release-post-push-crash-home"))
        val provider = ProductionVersionProvider {
            ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
        }
        var interrupt = true
        val service = ProductionTagService(
            store = ProductionTagPipelineStore(paths),
            versions = provider,
            git = git,
            now = { "now" },
            id = { "pipeline-release-crash" },
            afterRemoteWrite = { if (interrupt && it == ProductionOperationAction.CREATE_RELEASE) error("simulated process exit") },
        )
        val pipeline = service.create(config, repository.id)

        assertFailsWith<IllegalStateException> { service.createRelease(config, pipeline.id) }
        val secondInstance = ProductionTagService(ProductionTagPipelineStore(paths), provider, git)
        assertFailsWith<IllegalStateException> { secondInstance.close(pipeline.id) }

        interrupt = false
        val recovered = secondInstance.resume(config, pipeline.id)
        assertEquals("master-sha", recovered.releaseSha)
        assertEquals(ProductionAuditState.RECOVERED, recovered.auditEvents.last().state)
        assertEquals(null, recovered.activeOperation)
    }

    @Test
    fun `tag build cancels when the page confirmation is stale`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val config = managedConfig(repository)
        val git = FakeProductionGitGateway()
        val service = ProductionTagService(
            store = ProductionTagPipelineStore(ApplicationPaths(temporary.resolve("stale-tag-confirmation-home"))),
            versions = ProductionVersionProvider {
                ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
            },
            git = git,
            now = { "now" },
            id = { "stale-tag-confirmation" },
        )
        val pipeline = service.create(config, repository.id)
        service.createRelease(config, pipeline.id)
        service.selectFeatures(config, pipeline.id, listOf("feature/a"))
        service.mergeFeatures(config, pipeline.id)
        git.publishExternalTag("3.11.71", "older-release")
        val confirmation = service.tagConfirmation(config, pipeline.id)
        assertEquals("3.11.71.1", confirmation.tag)
        git.publishExternalTag("3.11.71.1", "another-release")

        assertFailsWith<ProductionTagConfirmationChangedException> {
            service.buildTag(
                config,
                pipeline.id,
                confirmation.tag,
                confirmation.releaseSha,
                confirmation.pipelineRevision,
            )
        }

        assertEquals(0, git.tagPushCalls)
        val cancelled = service.get(pipeline.id)
        assertEquals(ProductionTagBuildState.FAILED, cancelled.buildRecords.single().state)
        assertEquals(confirmation.tag, cancelled.buildRecords.single().expectedTag)
        assertEquals(confirmation.releaseSha, cancelled.buildRecords.single().releaseSha)
        assertTrue(cancelled.buildRecords.single().failureReason.orEmpty().contains("页面预期 Tag 已失效"))
        assertEquals(ProductionAuditState.FAILED, cancelled.auditEvents.last().state)
        assertEquals(ProductionOperationAction.BUILD_TAG.name, cancelled.auditEvents.last().action)
    }

    @Test
    fun `tag view retries when another instance updates the release during remote tag lookup`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val config = managedConfig(repository)
        val paths = ApplicationPaths(temporary.resolve("tag-view-snapshot-home"))
        val store = ProductionTagPipelineStore(paths)
        val git = FakeProductionGitGateway()
        val service = ProductionTagService(
            store = store,
            versions = ProductionVersionProvider {
                ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
            },
            git = git,
            now = { "now" },
            id = { "tag-view-snapshot" },
        )
        val pipeline = service.create(config, repository.id)
        service.createRelease(config, pipeline.id)
        service.selectFeatures(config, pipeline.id, listOf("feature/a"))
        service.mergeFeatures(config, pipeline.id)
        git.beforeTagsForBase = {
            val latest = store.get(pipeline.id)!!
            store.save(latest.copy(
                releaseSha = "release-after-external-feature",
                mergedFeatures = latest.mergedFeatures + ProductionFeatureMergeRecord(
                    "feature/b",
                    "sha-b",
                    "merge-sha-b",
                    "later",
                ),
            ))
        }

        val view = service.tagView(config, pipeline.id)

        assertEquals("release-after-external-feature", view.pipeline.releaseSha)
        assertEquals(view.pipeline.releaseSha, view.confirmation?.releaseSha)
        assertEquals(view.pipeline.revision, view.confirmation?.pipelineRevision)
        assertEquals(listOf("feature/a", "feature/b"), view.pipeline.mergedFeatures.map { it.branch })
    }

    @Test
    fun `stale build click after another instance closes the pipeline is audited without a push`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val config = managedConfig(repository)
        val paths = ApplicationPaths(temporary.resolve("closed-tag-confirmation-home"))
        val provider = ProductionVersionProvider {
            ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
        }
        val git = FakeProductionGitGateway()
        val first = ProductionTagService(
            store = ProductionTagPipelineStore(paths),
            versions = provider,
            git = git,
            now = { "now" },
            id = { "closed-tag-confirmation" },
        )
        val pipeline = first.create(config, repository.id)
        first.createRelease(config, pipeline.id)
        first.selectFeatures(config, pipeline.id, listOf("feature/a"))
        first.mergeFeatures(config, pipeline.id)
        val confirmation = first.tagConfirmation(config, pipeline.id)
        val second = ProductionTagService(ProductionTagPipelineStore(paths), provider, git, now = { "later" })
        second.close(pipeline.id)

        assertFailsWith<ProductionTagConfirmationChangedException> {
            first.buildTag(
                config,
                pipeline.id,
                confirmation.tag,
                confirmation.releaseSha,
                confirmation.pipelineRevision,
            )
        }

        val cancelled = first.get(pipeline.id)
        assertTrue(cancelled.closed)
        assertEquals(0, git.tagPushCalls)
        assertEquals(ProductionTagBuildState.FAILED, cancelled.buildRecords.single().state)
        assertTrue(cancelled.buildRecords.single().failureReason.orEmpty().contains("页面确认已失效"))
        assertEquals(ProductionAuditState.FAILED, cancelled.auditEvents.last().state)
        assertEquals(ProductionOperationAction.BUILD_TAG.name, cancelled.auditEvents.last().action)
    }

    @Test
    fun `concurrent build clicks queue and the stale click is audited after one push`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val config = managedConfig(repository)
        val paths = ApplicationPaths(temporary.resolve("concurrent-tag-build-home"))
        val provider = ProductionVersionProvider {
            ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
        }
        val firstPushEntered = CountDownLatch(1)
        val releaseFirstPush = CountDownLatch(1)
        val secondClickEntered = CountDownLatch(1)
        val git = FakeProductionGitGateway(onTagPush = {
            firstPushEntered.countDown()
            check(releaseFirstPush.await(10, TimeUnit.SECONDS)) { "test did not release the first Tag push" }
        })
        val first = ProductionTagService(
            ProductionTagPipelineStore(paths),
            provider,
            git,
            now = { "now" },
            id = { "concurrent-tag-build" },
        )
        val pipeline = first.create(config, repository.id)
        first.createRelease(config, pipeline.id)
        first.selectFeatures(config, pipeline.id, listOf("feature/a"))
        first.mergeFeatures(config, pipeline.id)
        val confirmation = first.tagConfirmation(config, pipeline.id)
        val second = ProductionTagService(ProductionTagPipelineStore(paths), provider, git, now = { "later" })
        val executor = Executors.newFixedThreadPool(2)
        try {
            val firstBuild = executor.submit<ProductionTagPipeline> {
                first.buildTag(config, pipeline.id, confirmation.tag, confirmation.releaseSha, confirmation.pipelineRevision)
            }
            assertTrue(firstPushEntered.await(5, TimeUnit.SECONDS))
            val secondBuild = executor.submit<ProductionTagPipeline> {
                secondClickEntered.countDown()
                second.buildTag(config, pipeline.id, confirmation.tag, confirmation.releaseSha, confirmation.pipelineRevision)
            }
            assertTrue(secondClickEntered.await(5, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertFalse(secondBuild.isDone)

            releaseFirstPush.countDown()
            assertEquals(ProductionTagBuildState.PUSHED, firstBuild.get(5, TimeUnit.SECONDS).buildRecords.last().state)
            val failure = assertFailsWith<ExecutionException> { secondBuild.get(5, TimeUnit.SECONDS) }
            assertTrue(failure.cause is ProductionTagConfirmationChangedException)

            val completed = first.get(pipeline.id)
            assertEquals(1, git.tagPushCalls)
            assertEquals(
                listOf(ProductionTagBuildState.PUSHED, ProductionTagBuildState.FAILED),
                completed.buildRecords.map { it.state },
            )
            assertEquals(ProductionAuditState.FAILED, completed.auditEvents.last().state)
            assertEquals(ProductionOperationAction.BUILD_TAG.name, completed.auditEvents.last().action)
        } finally {
            releaseFirstPush.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `interrupted queued build still waits for the writer and records the stale click`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val config = managedConfig(repository)
        val paths = ApplicationPaths(temporary.resolve("interrupted-tag-build-home"))
        val provider = ProductionVersionProvider {
            ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
        }
        val firstPushEntered = CountDownLatch(1)
        val releaseFirstPush = CountDownLatch(1)
        val secondClickEntered = CountDownLatch(1)
        val secondThread = AtomicReference<Thread>()
        val interruptedAtExit = AtomicBoolean(false)
        val git = FakeProductionGitGateway(onTagPush = {
            firstPushEntered.countDown()
            check(releaseFirstPush.await(10, TimeUnit.SECONDS)) { "test did not release the first Tag push" }
        })
        val first = ProductionTagService(
            ProductionTagPipelineStore(paths),
            provider,
            git,
            now = { "now" },
            id = { "interrupted-tag-build" },
        )
        val pipeline = first.create(config, repository.id)
        first.createRelease(config, pipeline.id)
        first.selectFeatures(config, pipeline.id, listOf("feature/a"))
        first.mergeFeatures(config, pipeline.id)
        val confirmation = first.tagConfirmation(config, pipeline.id)
        val second = ProductionTagService(ProductionTagPipelineStore(paths), provider, git, now = { "later" })
        val executor = Executors.newFixedThreadPool(2)
        try {
            val firstBuild = executor.submit<ProductionTagPipeline> {
                first.buildTag(config, pipeline.id, confirmation.tag, confirmation.releaseSha, confirmation.pipelineRevision)
            }
            assertTrue(firstPushEntered.await(5, TimeUnit.SECONDS))
            val secondBuild = executor.submit<ProductionTagPipeline> {
                secondThread.set(Thread.currentThread())
                secondClickEntered.countDown()
                try {
                    second.buildTag(config, pipeline.id, confirmation.tag, confirmation.releaseSha, confirmation.pipelineRevision)
                } finally {
                    interruptedAtExit.set(Thread.currentThread().isInterrupted)
                }
            }
            assertTrue(secondClickEntered.await(5, TimeUnit.SECONDS))
            Thread.sleep(100)
            secondThread.get().interrupt()
            Thread.sleep(100)
            assertFalse(secondBuild.isDone)

            releaseFirstPush.countDown()
            assertEquals(ProductionTagBuildState.PUSHED, firstBuild.get(5, TimeUnit.SECONDS).buildRecords.last().state)
            val failure = assertFailsWith<ExecutionException> { secondBuild.get(5, TimeUnit.SECONDS) }
            assertTrue(failure.cause is ProductionTagConfirmationChangedException)
            assertTrue(interruptedAtExit.get())

            val completed = first.get(pipeline.id)
            assertEquals(1, git.tagPushCalls)
            assertEquals(
                listOf(ProductionTagBuildState.PUSHED, ProductionTagBuildState.FAILED),
                completed.buildRecords.map { it.state },
            )
            assertEquals(ProductionAuditState.FAILED, completed.auditEvents.last().state)
            assertEquals(ProductionOperationAction.BUILD_TAG.name, completed.auditEvents.last().action)
        } finally {
            releaseFirstPush.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `release permission denial is finalized and shown as no push permission`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val config = managedConfig(repository)
        val service = ProductionTagService(
            store = ProductionTagPipelineStore(ApplicationPaths(temporary.resolve("release-no-permission-home"))),
            versions = ProductionVersionProvider {
                ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
            },
            git = FakeProductionGitGateway(releaseNoPushPermission = true),
            now = { "now" },
            id = { "release-no-permission" },
        )
        val pipeline = service.create(config, repository.id)

        val error = assertFailsWith<ProductionNoPushPermissionException> {
            service.createRelease(config, pipeline.id)
        }
        val failed = service.get(pipeline.id)

        assertEquals("无推送权限", error.message)
        assertEquals(null, failed.activeOperation)
        assertEquals(ProductionAuditState.FAILED, failed.auditEvents.last().state)
        assertEquals("无推送权限", failed.auditEvents.last().reason)
    }

    @Test
    fun `production merge conflict releases the lease without reconciliation`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val config = managedConfig(repository)
        val service = ProductionTagService(
            store = ProductionTagPipelineStore(ApplicationPaths(temporary.resolve("master-merge-conflict-home"))),
            versions = ProductionVersionProvider {
                ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
            },
            git = FakeProductionGitGateway(
                baselineState = ProductionBaselineState.MERGE_REQUIRED,
                productionMergeConflict = true,
            ),
            now = { "now" },
            id = { "master-merge-conflict" },
        )
        val pipeline = service.create(config, repository.id)

        val error = assertFailsWith<ProductionMergeConflictException> {
            service.mergeProduction(config, pipeline.id)
        }
        val conflicted = service.get(pipeline.id)

        assertEquals("生产 Tag 合并到 master 存在冲突：src/App.kt", error.message)
        assertEquals(null, conflicted.activeOperation)
        assertEquals(ProductionAuditState.CONFLICT, conflicted.auditEvents.last().state)
        assertEquals(error.message, conflicted.auditEvents.last().reason)
        assertEquals(ProductionBaselineState.MERGE_REQUIRED, conflicted.baselineState)
    }

    @Test
    fun `master merge is reconciled after process exit following the remote write`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val config = managedConfig(repository)
        val git = FakeProductionGitGateway(
            baselineState = ProductionBaselineState.MERGE_REQUIRED,
            directProductionMerge = true,
        )
        var interrupt = true
        val service = ProductionTagService(
            store = ProductionTagPipelineStore(ApplicationPaths(temporary.resolve("master-post-push-crash-home"))),
            versions = ProductionVersionProvider {
                ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
            },
            git = git,
            now = { "now" },
            id = { "pipeline-master-crash" },
            afterRemoteWrite = { if (interrupt && it == ProductionOperationAction.MERGE_PRODUCTION) error("simulated process exit") },
        )
        val pipeline = service.create(config, repository.id)

        assertFailsWith<IllegalStateException> { service.mergeProduction(config, pipeline.id) }
        interrupt = false
        val recovered = service.resume(config, pipeline.id)

        assertEquals(ProductionBaselineState.ALREADY_CONTAINED, recovered.baselineState)
        assertEquals(ProductionAuditState.SUCCEEDED, recovered.auditEvents.last().state)
        assertEquals(null, recovered.activeOperation)
    }

    @Test
    fun `master merge request source branch is recovered after process exit`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val config = managedConfig(repository)
        val git = FakeProductionGitGateway(baselineState = ProductionBaselineState.MERGE_REQUIRED)
        var interrupt = true
        val service = ProductionTagService(
            store = ProductionTagPipelineStore(ApplicationPaths(temporary.resolve("master-mr-crash-home"))),
            versions = ProductionVersionProvider {
                ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
            },
            git = git,
            now = { "now" },
            id = { "master-mr-crash" },
            afterRemoteWrite = { if (interrupt && it == ProductionOperationAction.MERGE_PRODUCTION) error("simulated process exit") },
        )
        val pipeline = service.create(config, repository.id)

        assertFailsWith<IllegalStateException> { service.mergeProduction(config, pipeline.id) }
        assertTrue(service.get(pipeline.id).activeOperation?.sourceBranch.orEmpty().startsWith("awm/production-tag/"))
        interrupt = false
        val recovered = service.resume(config, pipeline.id)

        assertEquals(ProductionBaselineState.AWAITING_MERGE_REQUEST, recovered.baselineState)
        assertEquals("https://gitlab.example/mr", recovered.mergeRequest?.url)
        assertEquals(ProductionAuditState.AWAITING_MERGE_REQUEST, recovered.auditEvents.last().state)
        assertEquals(null, recovered.activeOperation)
    }

    @Test
    fun `unknown merge request platform finalizes the operation without a remote write`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "unknown://host/repo")
        val config = managedConfig(repository)
        val service = ProductionTagService(
            store = ProductionTagPipelineStore(ApplicationPaths(temporary.resolve("unknown-mr-platform-home"))),
            versions = ProductionVersionProvider {
                ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
            },
            git = FakeProductionGitGateway(
                baselineState = ProductionBaselineState.MERGE_REQUIRED,
                mergeRequestUnavailable = true,
            ),
            now = { "now" },
            id = { "unknown-mr-platform" },
        )
        val pipeline = service.create(config, repository.id)

        val error = assertFailsWith<ProductionMergeRequestUnavailableException> {
            service.mergeProduction(config, pipeline.id)
        }
        val failed = service.get(pipeline.id)

        assertEquals("无合并权限，无法生成合并请求链接", error.message)
        assertEquals(null, failed.activeOperation)
        assertEquals(ProductionAuditState.FAILED, failed.auditEvents.last().state)
        assertEquals(error.message, failed.auditEvents.last().reason)
    }

    @Test
    fun `feature merge is reconciled with merge evidence after process exit`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val config = managedConfig(repository)
        val git = FakeProductionGitGateway()
        var interrupt = true
        val service = ProductionTagService(
            store = ProductionTagPipelineStore(ApplicationPaths(temporary.resolve("feature-post-push-crash-home"))),
            versions = ProductionVersionProvider {
                ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
            },
            git = git,
            now = { "now" },
            id = { "pipeline-feature-crash" },
            afterRemoteWrite = { if (interrupt && it == ProductionOperationAction.MERGE_FEATURES) error("simulated process exit") },
        )
        val pipeline = service.create(config, repository.id)
        service.createRelease(config, pipeline.id)
        service.selectFeatures(config, pipeline.id, listOf("feature/a"))

        assertFailsWith<IllegalStateException> { service.mergeFeatures(config, pipeline.id) }
        interrupt = false
        val recovered = service.resume(config, pipeline.id)

        assertEquals("release-with-features", recovered.releaseSha)
        assertEquals("recovered-sha-a", recovered.mergedFeatures.single().mergeCommit)
        assertEquals(ProductionAuditState.RECOVERED, recovered.auditEvents.last().state)
        assertEquals(null, recovered.activeOperation)
    }

    @Test
    fun `feature merge request source branch is recovered after process exit`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val config = managedConfig(repository)
        val git = FakeProductionGitGateway(featureMergeAwaiting = true)
        var interrupt = true
        val service = ProductionTagService(
            store = ProductionTagPipelineStore(ApplicationPaths(temporary.resolve("feature-mr-crash-home"))),
            versions = ProductionVersionProvider {
                ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
            },
            git = git,
            now = { "now" },
            id = { "feature-mr-crash" },
            afterRemoteWrite = { if (interrupt && it == ProductionOperationAction.MERGE_FEATURES) error("simulated process exit") },
        )
        val pipeline = service.create(config, repository.id)
        service.createRelease(config, pipeline.id)
        service.selectFeatures(config, pipeline.id, listOf("feature/a"))

        assertFailsWith<IllegalStateException> { service.mergeFeatures(config, pipeline.id) }
        interrupt = false
        val recovered = service.resume(config, pipeline.id)

        assertEquals(ProductionFeatureBatchState.AWAITING_MERGE_REQUEST, recovered.featureState)
        assertEquals("https://gitlab.example/feature-mr", recovered.mergeRequest?.url)
        assertEquals("recovered-sha-a", recovered.mergedFeatures.single().mergeCommit)
        assertEquals(ProductionAuditState.AWAITING_MERGE_REQUEST, recovered.auditEvents.last().state)
        assertEquals(null, recovered.activeOperation)
    }

    @Test
    fun `invalid repository configuration blocks Genbu before it is queried`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        var genbuCalls = 0
        val service = ProductionTagService(
            store = ProductionTagPipelineStore(ApplicationPaths(temporary.resolve("invalid-repo-home"))),
            versions = ProductionVersionProvider {
                genbuCalls += 1
                error("Genbu must not be called")
            },
            git = FakeProductionGitGateway(validationError = "origin/master missing"),
        )

        assertFailsWith<IllegalStateException> { service.create(managedConfig(repository), repository.id) }
        assertEquals(0, genbuCalls)
    }

    @Test
    fun `unadopted remote release blocks local pipeline creation`() {
        val repository = RepositoryConfig("repo", "android-transit-service", "Q:/repo", "Q:/repo/.git", "git@example.test:fp/repo.git")
        val service = ProductionTagService(
            store = ProductionTagPipelineStore(ApplicationPaths(temporary.resolve("unadopted-home"))),
            versions = ProductionVersionProvider {
                ProductionRuntimeSnapshot("svc", "PRD", "3.11.70", listOf(ProductionPodSnapshot("pod", "3.11.70", "Running", true, 0)))
            },
            git = FakeProductionGitGateway(unadoptedReleaseSha = "someone-elses-release"),
        )

        val blocked = service.create(managedConfig(repository), repository.id)

        assertEquals("someone-elses-release", blocked.unmanagedReleaseSha)
        assertFailsWith<IllegalStateException> { service.createRelease(managedConfig(repository), blocked.id) }
    }

    private class FakeProductionGitGateway(
        private val conflict: ProductionConflict? = null,
        private val baselineState: ProductionBaselineState = ProductionBaselineState.ALREADY_CONTAINED,
        private val mergedTargetSha: String = "production-sha",
        private val throwDuringTagPush: Boolean = false,
        private val validationError: String? = null,
        private val unadoptedReleaseSha: String? = null,
        private val directProductionMerge: Boolean = false,
        private val featureMergeAwaiting: Boolean = false,
        private val releaseNoPushPermission: Boolean = false,
        private val onTagPush: () -> Unit = {},
        private val throwAfterTagWrite: Boolean = false,
        private val mergeRequestUnavailable: Boolean = false,
        private val productionMergeConflict: Boolean = false,
    ) : ProductionGitGateway {
        var mergedBranches: List<String> = emptyList()
        var productionMergeCalls: Int = 0
        var tagPushCalls: Int = 0
        var formalTagValues: List<String> = listOf("3.11.70", "3.11.71.beta-1")
        var beforeTagsForBase: (() -> Unit)? = null
        private var remoteReleaseSha: String? = unadoptedReleaseSha
        private val remoteTags = mutableListOf<ProductionRemoteTag>()
        private var currentBaselineState: ProductionBaselineState = baselineState
        private var currentMasterSha: String = "master-sha"
        private var pendingProductionRequest: ProductionMergeRequest? = null
        private var pendingFeatureRequest: ProductionMergeRequest? = null

        override fun validateRepository(repository: RepositoryConfig) {
            validationError?.let(::error)
        }

        override fun operator(repository: RepositoryConfig): String? = "Test User <test@example.com>"

        override fun inspectBaseline(repository: RepositoryConfig, productionTag: String) =
            ProductionBaselineEvidence(productionTag, "production-sha", currentMasterSha, currentBaselineState)

        override fun formalTags(repository: RepositoryConfig) = formalTagValues

        override fun releaseHead(repository: RepositoryConfig, branch: String): String? = remoteReleaseSha

        override fun mergeProduction(repository: RepositoryConfig, pipeline: ProductionTagPipeline): ProductionBranchWrite {
            productionMergeCalls += 1
            if (productionMergeConflict) throw ProductionMergeConflictException("生产 Tag 合并到 master 存在冲突：src/App.kt")
            if (mergeRequestUnavailable) throw ProductionMergeRequestUnavailableException()
            return if (currentBaselineState == ProductionBaselineState.MERGE_REQUIRED && !directProductionMerge) {
                val request = ProductionMergeRequest(
                    "GITLAB",
                    "https://gitlab.example/mr",
                    pipeline.activeOperation?.sourceBranch ?: "source",
                    "master",
                    "production-merge",
                )
                pendingProductionRequest = request
                ProductionBranchWrite.AwaitingRequest(request)
            } else {
                currentBaselineState = ProductionBaselineState.ALREADY_CONTAINED
                currentMasterSha = "production-merge"
                ProductionBranchWrite.Direct(currentMasterSha)
            }
        }

        override fun recoverProductionWrite(
            repository: RepositoryConfig,
            targetBranch: String,
            beforeSha: String,
            productionSha: String,
            sourceBranch: String?,
        ): ProductionBranchWrite? = when {
            currentMasterSha != beforeSha -> ProductionBranchWrite.Direct(currentMasterSha)
            pendingProductionRequest?.sourceBranch == sourceBranch -> ProductionBranchWrite.AwaitingRequest(pendingProductionRequest!!)
            else -> null
        }

        override fun createRelease(repository: RepositoryConfig, pipeline: ProductionTagPipeline): String {
            if (releaseNoPushPermission) throw ProductionNoPushPermissionException()
            return pipeline.masterSha.also { remoteReleaseSha = it }
        }

        override fun resolveFeatures(repository: RepositoryConfig, branches: List<String>) = branches.mapIndexed { index, branch ->
            ProductionFeatureSelection(branch, "sha-${('a'.code + index).toChar()}")
        }

        override fun mergeFeatures(
            repository: RepositoryConfig,
            pipeline: ProductionTagPipeline,
            features: List<ProductionFeatureSelection>,
        ): ProductionFeatureWrite {
            conflict?.let { return ProductionFeatureWrite.Conflict(listOf(it)) }
            mergedBranches = features.map { it.branch }
            if (featureMergeAwaiting) {
                val request = ProductionMergeRequest(
                    "GITLAB",
                    "https://gitlab.example/feature-mr",
                    pipeline.activeOperation?.sourceBranch ?: "feature-source",
                    pipeline.releaseBranch,
                    "release-with-features",
                )
                pendingFeatureRequest = request
                return ProductionFeatureWrite.AwaitingRequest(
                    "release-with-features",
                    features.map { ProductionFeatureMergeRecord(it.branch, it.sha, "merge-${it.sha}", "now") },
                    request,
                )
            }
            remoteReleaseSha = "release-with-features"
            return ProductionFeatureWrite.Direct(
                releaseSha = "release-with-features",
                merges = features.map { ProductionFeatureMergeRecord(it.branch, it.sha, "merge-${it.sha}", "now") },
            )
        }

        override fun tagsForBase(repository: RepositoryConfig, baseVersion: String): List<ProductionRemoteTag> {
            beforeTagsForBase?.also { beforeTagsForBase = null }?.invoke()
            return remoteTags.toList()
        }

        override fun recoverFeatureWrite(
            repository: RepositoryConfig,
            releaseBranch: String,
            sourceBranch: String?,
            beforeSha: String,
            features: List<ProductionFeatureSelection>,
        ): ProductionFeatureWrite? {
            val merges = features.map { ProductionFeatureMergeRecord(it.branch, it.sha, "recovered-${it.sha}", "now") }
            if (pendingFeatureRequest?.sourceBranch == sourceBranch) {
                return ProductionFeatureWrite.AwaitingRequest("release-with-features", merges, pendingFeatureRequest!!)
            }
            val head = remoteReleaseSha?.takeIf { it != beforeSha } ?: return null
            return ProductionFeatureWrite.Direct(head, merges)
        }

        override fun pushTag(repository: RepositoryConfig, releaseBranch: String, tag: String, releaseSha: String): ProductionTagPush {
            if (throwDuringTagPush) error("simulated process interruption")
            tagPushCalls += 1
            onTagPush()
            remoteTags += ProductionRemoteTag(tag, releaseSha)
            if (throwAfterTagWrite) error("network lost after server accepted tag")
            return ProductionTagPush.Pushed(releaseSha)
        }

        fun publishExternalTag(tag: String, sha: String) {
            remoteTags += ProductionRemoteTag(tag, sha)
        }

        override fun mergedTargetSha(repository: RepositoryConfig, targetBranch: String, expectedCommit: String) = mergedTargetSha
    }

    private fun managedConfig(repository: RepositoryConfig) = AppConfig(
        repositories = listOf(repository),
        groups = listOf(GroupConfig(
            id = "group",
            name = "Group",
            services = listOf(GroupServiceConfig.standard("service", repository.id, repository.name)),
        )),
    )

    private fun ProductionTagService.buildConfirmedTag(config: AppConfig, pipelineId: String): ProductionTagPipeline {
        val confirmation = tagConfirmation(config, pipelineId)
        return buildTag(config, pipelineId, confirmation.tag, confirmation.releaseSha, confirmation.pipelineRevision)
    }
}
