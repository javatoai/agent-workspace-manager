package com.snowball.awm.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TaskApplicationServiceTest {
    @Test
    fun `create rejects an unsafe task directory name before provisioning`() {
        val root = Files.createTempDirectory("unsafe-task-name-")
        val service = TaskApplicationService(operationLock = NoOpTaskOperationLock)

        assertFailsWith<IllegalArgumentException> {
            service.create(
                taskConfig(root),
                CreateGroupedTaskRequest(
                    folderName = "unsafe:name",
                    featureBranch = "feature/unsafe-name",
                    groupId = "alpha",
                    serviceIds = listOf("standard"),
                ),
            )
        }
        Files.list(root).use { children ->
            assertTrue(!children.findAny().isPresent)
        }
    }

    @Test
    fun `task belongs to selected group and delegates each service to its strategy`() {
        val root = Files.createTempDirectory("task-app-")
        val standard = RecordingProvisioner(WorkspaceStrategy.STANDARD_WORKTREE)
        val clone = RecordingProvisioner(WorkspaceStrategy.INDEPENDENT_CLONE)
        val documents = RecordingAgentDocuments()
        val config = taskConfig(root)
        val service = TaskApplicationService(
            manifests = ManifestStore(),
            provisioning = WorkspaceProvisioningService(listOf(standard, clone)),
            agentDocuments = documents,
            operationLock = NoOpTaskOperationLock,
            clock = Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC),
        )

        val manifest = service.create(
            config,
            CreateGroupedTaskRequest(
                folderName = "TASK-20",
                featureBranch = "feature/task-20",
                groupId = "alpha",
                serviceIds = listOf("standard", "clone"),
                requirementLink = "https://example.test/task/20",
                taskNotes = "only edit the API module",
                baseOverrides = listOf(
                    ModuleBaseOverride("standard", "default", "upstream/develop", "feature/custom-standard"),
                    ModuleBaseOverride("clone", "clone", "origin/release/test"),
                ),
            ),
        )

        assertEquals("alpha", manifest.groupId)
        assertEquals(listOf(WorkspaceStrategy.STANDARD_WORKTREE), standard.requests.map { it.service.strategy })
        assertEquals("upstream/develop", standard.requests.single().service.modules.single().baseRef)
        assertEquals("upstream", standard.requests.single().service.modules.single().baseRemote)
        assertEquals(mapOf("default" to "feature/custom-standard"), standard.requests.single().moduleBranches)
        assertEquals("origin/release/test", clone.requests.single().service.cloneModules.single().branch)
        assertEquals(2, manifest.services.size)
        assertEquals("2026-08-08 08:00:00", manifest.createdAt)
        assertEquals("2026-08-08 08:00:00", manifest.updatedAt)
        assertEquals("only edit the API module", documents.lastNotes)
        assertTrue(Files.exists(root.resolve("TASK-20").resolve(ManifestStore.FILE_NAME)))
    }

    @Test
    fun `create rolls back completed services through their provisioning strategy`() {
        val root = Files.createTempDirectory("task-create-rollback-")
        val standard = RecordingProvisioner(WorkspaceStrategy.STANDARD_WORKTREE)
        val failingClone = RecordingProvisioner(WorkspaceStrategy.INDEPENDENT_CLONE, fail = true)
        val application = TaskApplicationService(
            provisioning = WorkspaceProvisioningService(listOf(standard, failingClone)),
            agentDocuments = RecordingAgentDocuments(),
            operationLock = NoOpTaskOperationLock,
        )

        assertFailsWith<IllegalStateException> {
            application.create(
                taskConfig(root),
                CreateGroupedTaskRequest("TASK-ROLLBACK", "feature/rollback", "alpha", listOf("standard", "clone")),
            )
        }

        assertEquals(1, standard.rollbackCalls)
        assertTrue(!Files.exists(root.resolve("TASK-ROLLBACK")))
    }

    @Test
    fun `startup snapshot loads current files without repository inspection`() {
        val root = Files.createTempDirectory("startup-")
        val paths = ApplicationPaths(root.resolve("home"))
        val config = taskConfig(root.resolve("tasks"))
        ConfigStore(paths).save(config)
        val loader = StartupSnapshotLoader(ConfigStore(paths), ManifestStore())

        val snapshot = loader.load()

        assertEquals(config.repositories, snapshot.config.repositories)
        assertTrue(snapshot.tasks.isEmpty())
    }

    @Test
    fun `adds only services from the task group and rewrites agents document`() {
        val root = Files.createTempDirectory("task-add-services-")
        val taskDirectory = root.resolve("TASK-20")
        val manifests = ManifestStore()
        val original = TaskManifest(
            folderName = "TASK-20",
            taskDirectoryName = "TASK-20",
            featureBranch = "feature/task-20",
            createdAt = "2026-08-08 08:00:00",
            updatedAt = "2026-08-08 08:00:00",
            lifecycleStatus = TaskLifecycleStatus.ACTIVE,
            services = emptyList(),
            groupId = "alpha",
        )
        manifests.save(taskDirectory, original)
        val clone = RecordingProvisioner(WorkspaceStrategy.INDEPENDENT_CLONE)
        val documents = RecordingAgentDocuments()
        val application = TaskApplicationService(
            manifests = manifests,
            provisioning = WorkspaceProvisioningService(listOf(clone)),
            agentDocuments = documents,
            operationLock = NoOpTaskOperationLock,
            clock = Clock.fixed(Instant.parse("2026-08-08T01:02:03Z"), ZoneOffset.UTC),
        )

        val updated = application.addServices(
            taskConfig(root),
            taskDirectory,
            AddGroupedTaskServicesRequest(
                serviceIds = listOf("clone"),
            ),
        )

        assertEquals(listOf("clone"), updated.services.map(ServiceWorkspace::groupServiceId))
        assertEquals("feature/task-20", clone.requests.single().requestedFeatureBranch)
        assertEquals("origin/master", clone.requests.single().service.cloneModules.single().branch)
        assertEquals("2026-08-08 09:02:03", updated.updatedAt)
        assertEquals(null, documents.lastNotes)
    }

    @Test
    fun `add services rolls back earlier additions when a later service fails`() {
        val root = Files.createTempDirectory("task-add-rollback-")
        val taskDirectory = root.resolve("TASK-20")
        val manifests = ManifestStore()
        val original = TaskManifest(
            folderName = "TASK-20",
            taskDirectoryName = "TASK-20",
            featureBranch = "feature/task-20",
            createdAt = "2026-08-08 08:00:00",
            updatedAt = "2026-08-08 08:00:00",
            lifecycleStatus = TaskLifecycleStatus.ACTIVE,
            services = emptyList(),
            groupId = "alpha",
        )
        manifests.save(taskDirectory, original)
        val standard = RecordingProvisioner(WorkspaceStrategy.STANDARD_WORKTREE)
        val failingClone = RecordingProvisioner(WorkspaceStrategy.INDEPENDENT_CLONE, fail = true)
        val application = TaskApplicationService(
            manifests = manifests,
            provisioning = WorkspaceProvisioningService(listOf(standard, failingClone)),
            agentDocuments = RecordingAgentDocuments(),
            operationLock = NoOpTaskOperationLock,
        )

        assertFailsWith<IllegalStateException> {
            application.addServices(
                taskConfig(root),
                taskDirectory,
                AddGroupedTaskServicesRequest(listOf("standard", "clone")),
            )
        }

        assertEquals(1, standard.rollbackCalls)
        assertEquals(original, manifests.load(taskDirectory))
    }

    @Test
    fun `add services restores manifest and workspaces when agents regeneration fails`() {
        val root = Files.createTempDirectory("task-add-agent-failure-")
        val taskDirectory = root.resolve("TASK-20")
        val manifests = ManifestStore()
        val original = TaskManifest(
            folderName = "TASK-20",
            taskDirectoryName = "TASK-20",
            featureBranch = "feature/task-20",
            createdAt = "2026-08-08 08:00:00",
            updatedAt = "2026-08-08 08:00:00",
            lifecycleStatus = TaskLifecycleStatus.ACTIVE,
            services = emptyList(),
            groupId = "alpha",
        )
        manifests.save(taskDirectory, original)
        val standard = RecordingProvisioner(WorkspaceStrategy.STANDARD_WORKTREE)
        val application = TaskApplicationService(
            manifests = manifests,
            provisioning = WorkspaceProvisioningService(listOf(standard)),
            agentDocuments = RecordingAgentDocuments(failOnFirstWrite = true),
            operationLock = NoOpTaskOperationLock,
        )

        assertFailsWith<IllegalStateException> {
            application.addServices(
                taskConfig(root),
                taskDirectory,
                AddGroupedTaskServicesRequest(listOf("standard")),
            )
        }

        assertEquals(1, standard.rollbackCalls)
        assertEquals(original, manifests.load(taskDirectory))
    }

    @Test
    fun `existing task directory is never reused or deleted by failed creation`() {
        val root = Files.createTempDirectory("task-existing-")
        val existing = root.resolve("TASK-20")
        Files.createDirectories(existing)
        val userFile = existing.resolve("keep.txt")
        Files.writeString(userFile, "keep")
        val service = TaskApplicationService(
            provisioning = WorkspaceProvisioningService(listOf(RecordingProvisioner(WorkspaceStrategy.STANDARD_WORKTREE))),
            agentDocuments = RecordingAgentDocuments(),
            operationLock = NoOpTaskOperationLock,
        )

        assertFailsWith<IllegalArgumentException> {
            service.create(
                taskConfig(root).copy(
                    groups = listOf(
                        GroupConfig(
                            "alpha",
                            "Alpha",
                            services = listOf(GroupServiceConfig.standard("standard", "repo-a", "Repo A")),
                        ),
                    ),
                ),
                CreateGroupedTaskRequest("TASK-20", "feature/x", "alpha", listOf("standard")),
            )
        }
        assertEquals("keep", Files.readString(userFile))
    }

    @Test
    fun `tampered clone path outside task directory is refused`() {
        val root = Files.createTempDirectory("task-delete-")
        val taskDirectory = root.resolve("task")
        val outside = root.resolve("outside")
        Files.createDirectories(taskDirectory)
        Files.createDirectories(outside)
        Files.writeString(outside.resolve("keep.txt"), "keep")
        ManifestStore().save(
            taskDirectory,
            TaskManifest(
                folderName = "task",
                taskDirectoryName = "task",
                featureBranch = "feature/x",
                createdAt = "2026-08-08T00:00:00Z",
                updatedAt = "2026-08-08T00:00:00Z",
                lifecycleStatus = TaskLifecycleStatus.ACTIVE,
                services = listOf(
                    ServiceWorkspace(
                        repositoryId = "repo-a",
                        serviceName = "clone",
                        repositoryPath = outside.toString(),
                        worktreePath = outside.toString(),
                        developmentTool = DevelopmentToolType.INTELLIJ_IDEA,
                        branch = "master",
                        strategy = WorkspaceStrategy.INDEPENDENT_CLONE,
                        originUrl = "https://example.test/a.git",
                    ),
                ),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            TaskApplicationService(operationLock = NoOpTaskOperationLock)
                .delete(taskConfig(root), taskDirectory, forceDiscard = true)
        }
        assertEquals("keep", Files.readString(outside.resolve("keep.txt")))
    }

    @Test
    fun `archive and restore do not mutate physical workspaces when manifest commit fails`() {
        val root = Files.createTempDirectory("task-commit-compensation-")
        val taskDirectory = root.resolve("task")
        Files.createDirectories(taskDirectory)
        val ready = emptyManifest(TaskLifecycleStatus.ACTIVE)
        val archiveLifecycle = RecordingLifecycle()
        val archiveApp = TaskApplicationService(
            manifests = FailingSaveManifests(ready),
            agentDocuments = RecordingAgentDocuments(),
            lifecycle = archiveLifecycle,
            operationLock = NoOpTaskOperationLock,
        )
        val config = AppConfig(taskRoot = root.toString())

        assertFailsWith<IllegalStateException> { archiveApp.archive(config, taskDirectory) }
        assertEquals(0, archiveLifecycle.removeCalls)
        assertEquals(0, archiveLifecycle.restoreCalls)

        val restoreLifecycle = RecordingLifecycle()
        val restoreApp = TaskApplicationService(
            manifests = FailingSaveManifests(emptyManifest(TaskLifecycleStatus.ARCHIVED)),
            agentDocuments = RecordingAgentDocuments(),
            lifecycle = restoreLifecycle,
            operationLock = NoOpTaskOperationLock,
        )
        assertFailsWith<IllegalStateException> { restoreApp.restore(config, taskDirectory) }
        assertEquals(0, restoreLifecycle.restoreCalls)
        assertEquals(0, restoreLifecycle.removeCalls)
    }
}

private fun emptyManifest(status: TaskLifecycleStatus) = TaskManifest(
    folderName = "task",
    taskDirectoryName = "task",
    featureBranch = "feature/task",
    createdAt = "2026-08-08T00:00:00Z",
    updatedAt = "2026-08-08T00:00:00Z",
    lifecycleStatus = status,
    services = emptyList(),
)

private class FailingSaveManifests(private val manifest: TaskManifest) : TaskManifestRepository {
    override fun save(taskDirectory: Path, manifest: TaskManifest) = error("manifest disk full")
    override fun load(taskDirectory: Path): TaskManifest = manifest
    override fun scan(taskRoot: Path) = ManifestScanResult(emptyList(), emptyList())
}

private class RecordingLifecycle : WorkspaceLifecycle {
    var removeCalls = 0
    var restoreCalls = 0
    override fun inspectDeleteRisks(config: AppConfig, taskDirectory: Path, manifest: TaskManifest) = emptyList<DeleteRisk>()
    override fun requireArchiveSafe(config: AppConfig, taskDirectory: Path, manifest: TaskManifest, force: Boolean) = Unit
    override fun removeAll(config: AppConfig, taskDirectory: Path, manifest: TaskManifest, force: Boolean) {
        removeCalls++
    }
    override fun restoreAll(config: AppConfig, taskDirectory: Path, manifest: TaskManifest): List<ServiceWorkspace> {
        restoreCalls++
        return manifest.services
    }
    override fun validateForMutation(
        config: AppConfig,
        taskDirectory: Path,
        manifest: TaskManifest,
        workspace: ServiceWorkspace,
    ) = WorkspaceMutationTarget(Path.of(workspace.repositoryPath), Path.of(workspace.worktreePath))
}

private fun taskConfig(taskRoot: Path): AppConfig {
    val repositories = listOf(
        RepositoryConfig("repo-a", "Repo A", "C:/repo-a", "C:/repo-a/.git", "https://example.test/a.git"),
        RepositoryConfig("repo-b", "Repo B", "C:/repo-b", "C:/repo-b/.git", "https://example.test/b.git"),
    )
    return AppConfig(
        taskRoot = taskRoot.toString(),
        repositories = repositories,
        groups = listOf(
            GroupConfig(
                id = "alpha",
                name = "Alpha",
                services = listOf(
                    GroupServiceConfig.standard("standard", "repo-a", "Repo A"),
                    GroupServiceConfig(
                        id = "clone",
                        repositoryId = "repo-b",
                        displayName = "Repo B",
                        strategy = WorkspaceStrategy.INDEPENDENT_CLONE,
                        modules = emptyList(),
                        cloneModules = listOf(IndependentCloneModuleConfig("clone", branch = "origin/master")),
                    ),
                ),
            ),
        ),
    )
}

private class RecordingProvisioner(
    override val strategy: WorkspaceStrategy,
    private val fail: Boolean = false,
) : WorkspaceProvisioner {
    val requests = mutableListOf<WorkspaceProvisionRequest>()
    var rollbackCalls = 0

    override fun provision(request: WorkspaceProvisionRequest): List<ServiceWorkspace> {
        requests += request
        if (fail) error("provision failed")
        return listOf(
            ServiceWorkspace(
                repositoryId = request.repository.id,
                serviceName = request.service.displayName,
                repositoryPath = request.repository.rootPath,
                worktreePath = request.taskDirectory.resolve(request.service.id).toString(),
                developmentTool = request.service.developmentTool,
                branch = if (strategy == WorkspaceStrategy.INDEPENDENT_CLONE) request.service.cloneModules.first().branch.removePrefix("origin/") else request.requestedFeatureBranch.orEmpty(),
                health = WorkspaceHealth.READY,
                groupServiceId = request.service.id,
                strategy = strategy,
            ),
        )
    }

    override fun rollback(request: WorkspaceProvisionRequest, workspaces: List<ServiceWorkspace>) {
        rollbackCalls++
    }
}

private class RecordingAgentDocuments(
    private val failOnFirstWrite: Boolean = false,
) : AgentDocuments {
    var lastNotes: String? = null
    private var writes = 0
    override fun readGlobal(): String = ""
    override fun saveGlobal(content: String) = Unit
    override fun readGroup(groupId: String): String = ""
    override fun saveGroup(groupId: String, content: String) = Unit
    override fun writeTaskDocument(
        taskDirectory: Path,
        manifest: TaskManifest,
        repositories: List<RepositoryInfo>,
        taskNotes: String?,
    ): Path {
        writes++
        if (failOnFirstWrite && writes == 1) error("agents disk full")
        lastNotes = taskNotes
        return taskDirectory.resolve("AGENTS.md")
    }
}
