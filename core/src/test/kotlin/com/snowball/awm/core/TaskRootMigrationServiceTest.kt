package com.snowball.awm.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskRootMigrationServiceTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `empty task root switches directly and reports progress`() {
        val paths = ApplicationPaths(temporary.resolve("direct/home"))
        val source = Files.createDirectories(temporary.resolve("direct/tasks"))
        val target = temporary.resolve("direct/new-tasks")
        val store = ConfigStore(paths).also { it.save(AppConfig(taskRoot = source.toString())) }
        val service = TaskRootMigrationService(configStore = store, paths = paths)
        val progress = mutableListOf<TaskRootMigrationProgress>()

        val preview = service.preview(store.load(), target)
        val result = service.migrate(store.load(), target, progress::add)

        assertEquals(TaskRootMigrationMode.DIRECT_SWITCH, preview.mode)
        assertEquals(0, result.migratedTasks)
        assertTrue(target.exists())
        assertTrue(source.exists())
        assertEquals(target.toAbsolutePath().normalize().toString(), store.load().taskRoot)
        assertEquals(
            listOf(TaskRootMigrationPhase.PREPARING, TaskRootMigrationPhase.UPDATING_CONFIG, TaskRootMigrationPhase.COMPLETED),
            progress.map(TaskRootMigrationProgress::phase),
        )
    }

    @Test
    fun `preview blocks nested and non-empty targets`() {
        val paths = ApplicationPaths(temporary.resolve("home"))
        val source = Files.createDirectories(temporary.resolve("tasks"))
        val configStore = ConfigStore(paths).also { it.save(AppConfig(taskRoot = source.toString())) }
        val service = TaskRootMigrationService(configStore = configStore, paths = paths)

        val nested = service.preview(configStore.load(), source.resolve("nested"))
        assertTrue(nested.blockers.any { it.contains("不能互相包含") })

        val nonEmpty = Files.createDirectories(temporary.resolve("occupied"))
        Files.writeString(nonEmpty.resolve("keep.txt"), "keep")
        val occupied = service.preview(configStore.load(), nonEmpty)
        assertTrue(occupied.blockers.any { it.contains("必须为空") })
    }

    @Test
    fun `preview blocks unreadable manifest and workspace outside its task directory`() {
        val paths = ApplicationPaths(temporary.resolve("invalid/home"))
        val source = Files.createDirectories(temporary.resolve("invalid/tasks"))
        val badTask = Files.createDirectories(source.resolve("bad-json"))
        Files.writeString(badTask.resolve("agent-workspace.json"), "{ not-json")
        val store = ConfigStore(paths).also { it.save(AppConfig(taskRoot = source.toString())) }
        val service = TaskRootMigrationService(configStore = store, paths = paths)

        val invalidManifest = service.preview(store.load(), temporary.resolve("invalid/target"))

        assertTrue(invalidManifest.blockers.any { it.contains("任务清单读取失败") })

        Files.delete(badTask.resolve("agent-workspace.json"))
        Files.delete(badTask)
        val fixture = independentFixture("outside-workspace", "TASK-OUTSIDE")
        val original = ManifestStore().load(fixture.sourceTask)
        ManifestStore().save(
            fixture.sourceTask,
            original.copy(services = original.services.map { it.copy(worktreePath = fixture.repository.rootPath) }),
        )

        val outsideWorkspace = TaskRootMigrationService(
            configStore = fixture.store,
            paths = fixture.paths,
        ).preview(fixture.store.load(), fixture.targetRoot)

        assertTrue(outsideWorkspace.blockers.any { it.contains("工作区不在任务目录内") })
    }

    @Test
    fun `same file store migration repairs standard worktree and preserves dirty state`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("standard"))
        val repositoryPath = GitTestSupport.clone(remote, temporary.resolve("standard/source"))
        val repository = GitRepositoryInspector().inspect(repositoryPath)
        val sourceRoot = Files.createDirectories(temporary.resolve("standard/tasks"))
        val sourceTask = Files.createDirectories(sourceRoot.resolve("TASK-1"))
        val worktree = sourceTask.resolve("service/default")
        GitClient().addWorktree(repositoryPath, worktree, "feature/migrate", "origin/master")
        Files.writeString(worktree.resolve("dirty.txt"), "dirty\n")
        val manifest = manifest(
            taskName = "TASK-1",
            repository = repository,
            workspacePath = worktree,
            strategy = WorkspaceStrategy.STANDARD_WORKTREE,
            branch = "feature/migrate",
        )
        ManifestStore().save(sourceTask, manifest)
        val paths = ApplicationPaths(temporary.resolve("standard/home"))
        val configStore = ConfigStore(paths).also {
            it.save(AppConfig(taskRoot = sourceRoot.toString(), repositories = listOf(repository)))
        }
        val targetRoot = temporary.resolve("standard/new-tasks")
        val service = TaskRootMigrationService(
            configStore = configStore,
            paths = paths,
            agentDocuments = AgentDocumentService(paths),
            sameFileStore = { _, _ -> true },
        )

        val preview = service.preview(configStore.load(), targetRoot)
        assertEquals(TaskRootMigrationMode.SAME_FILE_STORE, preview.mode)
        assertTrue(preview.blockers.isEmpty())

        val result = service.migrate(configStore.load(), targetRoot)
        val migratedTask = targetRoot.resolve("TASK-1")
        val migratedWorktree = migratedTask.resolve("service/default")
        val migratedManifest = ManifestStore().load(migratedTask)

        assertEquals(1, result.migratedTasks)
        assertTrue(result.cleanupFailures.isEmpty())
        assertFalse(sourceTask.exists())
        assertTrue(migratedWorktree.resolve("dirty.txt").exists())
        assertEquals(migratedWorktree.toAbsolutePath().normalize().toString(), migratedManifest.services.single().worktreePath)
        assertEquals(migratedWorktree.toAbsolutePath().normalize(), GitClient().topLevel(migratedWorktree))
        assertEquals("feature/migrate", GitClient().currentBranch(migratedWorktree))
        assertTrue(GitClient().worktrees(repositoryPath).any { it.path.toAbsolutePath().normalize() == migratedWorktree.toAbsolutePath().normalize() })
        assertTrue(Files.readString(migratedTask.resolve("AGENTS.md")).contains(migratedWorktree.toString()))
        assertEquals(targetRoot.toAbsolutePath().normalize().toString(), configStore.load().taskRoot)
    }

    @Test
    fun `cross file store migration copies independent clone before removing source`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("clone"))
        val repositoryPath = GitTestSupport.clone(remote, temporary.resolve("clone/source"))
        val repository = GitRepositoryInspector().inspect(repositoryPath)
        val sourceRoot = Files.createDirectories(temporary.resolve("clone/tasks"))
        val sourceTask = Files.createDirectories(sourceRoot.resolve("TASK-2"))
        val clone = GitTestSupport.clone(remote, sourceTask.resolve("service/clone"))
        Files.writeString(clone.resolve("untracked.txt"), "preserve\n")
        val manifest = manifest(
            taskName = "TASK-2",
            repository = repository,
            workspacePath = clone,
            strategy = WorkspaceStrategy.INDEPENDENT_CLONE,
            branch = "master",
        )
        ManifestStore().save(sourceTask, manifest)
        val paths = ApplicationPaths(temporary.resolve("clone/home"))
        val configStore = ConfigStore(paths).also {
            it.save(AppConfig(taskRoot = sourceRoot.toString(), repositories = listOf(repository)))
        }
        val targetRoot = temporary.resolve("clone/new-tasks")
        val service = TaskRootMigrationService(
            configStore = configStore,
            paths = paths,
            agentDocuments = AgentDocumentService(paths),
            sameFileStore = { _, _ -> false },
        )

        assertEquals(TaskRootMigrationMode.CROSS_FILE_STORE, service.preview(configStore.load(), targetRoot).mode)
        val result = service.migrate(configStore.load(), targetRoot)
        val migratedTask = targetRoot.resolve("TASK-2")
        val migratedClone = migratedTask.resolve("service/clone")

        assertEquals(1, result.migratedTasks)
        assertFalse(sourceTask.exists())
        assertTrue(migratedClone.resolve("untracked.txt").exists())
        assertEquals("master", GitClient().currentBranch(migratedClone))
        assertEquals(migratedClone.toAbsolutePath().normalize().toString(), ManifestStore().load(migratedTask).services.single().worktreePath)
    }

    @Test
    fun `cross file store migration repairs copied standard worktree`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("cross-standard"))
        val repositoryPath = GitTestSupport.clone(remote, temporary.resolve("cross-standard/source"))
        val repository = GitRepositoryInspector().inspect(repositoryPath)
        val sourceRoot = Files.createDirectories(temporary.resolve("cross-standard/tasks"))
        val sourceTask = Files.createDirectories(sourceRoot.resolve("TASK-3"))
        val worktree = sourceTask.resolve("service/default")
        GitClient().addWorktree(repositoryPath, worktree, "feature/cross", "origin/master")
        GitTestSupport.configureIdentity(worktree)
        Files.writeString(worktree.resolve("staged.txt"), "staged\n")
        GitTestSupport.run(worktree, "add", "staged.txt")
        ManifestStore().save(
            sourceTask,
            manifest("TASK-3", repository, worktree, WorkspaceStrategy.STANDARD_WORKTREE, "feature/cross"),
        )
        val paths = ApplicationPaths(temporary.resolve("cross-standard/home"))
        val configStore = ConfigStore(paths).also {
            it.save(AppConfig(taskRoot = sourceRoot.toString(), repositories = listOf(repository)))
        }
        val targetRoot = temporary.resolve("cross-standard/new-tasks")
        val before = GitClient().readOnly(worktree, "status", "--porcelain=v2", "--branch", "-z", "--untracked-files=all").stdout
        val service = TaskRootMigrationService(
            configStore = configStore,
            paths = paths,
            agentDocuments = AgentDocumentService(paths),
            sameFileStore = { _, _ -> false },
        )

        val result = service.migrate(configStore.load(), targetRoot)
        val migrated = targetRoot.resolve("TASK-3/service/default")

        assertEquals(1, result.migratedTasks)
        assertFalse(sourceTask.exists())
        assertTrue(migrated.resolve("staged.txt").exists())
        assertEquals(before, GitClient().readOnly(migrated, "status", "--porcelain=v2", "--branch", "-z", "--untracked-files=all").stdout)
        assertTrue(GitClient().worktrees(repositoryPath).any { it.path.toAbsolutePath().normalize() == migrated.toAbsolutePath().normalize() })
    }

    @Test
    fun `failed target worktree repair restores source registration and configuration`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("repair-failure"))
        val repositoryPath = GitTestSupport.clone(remote, temporary.resolve("repair-failure/source"))
        val repository = GitRepositoryInspector().inspect(repositoryPath)
        val sourceRoot = Files.createDirectories(temporary.resolve("repair-failure/tasks"))
        val sourceTask = Files.createDirectories(sourceRoot.resolve("TASK-4"))
        val worktree = sourceTask.resolve("service/default")
        GitClient().addWorktree(repositoryPath, worktree, "feature/repair-failure", "origin/master")
        val manifest = manifest("TASK-4", repository, worktree, WorkspaceStrategy.STANDARD_WORKTREE, "feature/repair-failure")
        ManifestStore().save(sourceTask, manifest)
        val paths = ApplicationPaths(temporary.resolve("repair-failure/home"))
        val store = ConfigStore(paths).also {
            it.save(AppConfig(taskRoot = sourceRoot.toString(), repositories = listOf(repository)))
        }
        val targetRoot = temporary.resolve("repair-failure/new-tasks")
        val git = GitClient()
        val service = TaskRootMigrationService(
            configStore = store,
            paths = paths,
            agentDocuments = AgentDocumentService(paths),
            sameFileStore = { _, _ -> true },
            repairWorktree = { repositoryRoot, worktreePath ->
                if (worktreePath.startsWith(targetRoot)) error("simulated repair failure")
                git.repairWorktree(repositoryRoot, worktreePath)
            },
        )

        val error = assertFailsWith<IllegalStateException> { service.migrate(store.load(), targetRoot) }

        assertTrue(error.message.orEmpty().contains("已恢复原目录"))
        assertTrue(worktree.exists())
        assertFalse(targetRoot.resolve("TASK-4").exists())
        assertEquals(sourceRoot.toAbsolutePath().normalize().toString(), store.load().taskRoot)
        assertTrue(git.worktrees(repositoryPath).any { it.path.toAbsolutePath().normalize() == worktree.toAbsolutePath().normalize() })
        assertFalse(paths.home.resolve("migrations/task-root.json").exists())
    }

    @Test
    fun `configuration save failure rolls back copied task and regenerated documents`() {
        val fixture = independentFixture("config-failure", "TASK-5")
        val original = ManifestStore().load(fixture.sourceTask)
        val documents = AgentDocumentService(fixture.paths)
        documents.writeTaskDocument(
            fixture.sourceTask,
            original,
            listOf(fixture.repository.toInfo()),
            taskNotes = "保留人工说明",
        )
        val failingStore = object : ConfigurationRepository {
            override fun load(): AppConfig = fixture.store.load()
            override fun save(config: AppConfig) {
                if (config.taskRoot == fixture.targetRoot.toAbsolutePath().normalize().toString()) {
                    error("simulated config save failure")
                }
                fixture.store.save(config)
            }
        }
        val service = TaskRootMigrationService(
            configStore = failingStore,
            paths = fixture.paths,
            agentDocuments = documents,
            sameFileStore = { _, _ -> false },
        )

        assertFailsWith<IllegalStateException> { service.migrate(failingStore.load(), fixture.targetRoot) }

        assertTrue(fixture.workspace.exists())
        assertFalse(fixture.targetRoot.resolve(fixture.sourceTask.fileName).exists())
        assertEquals(fixture.sourceRoot.toAbsolutePath().normalize().toString(), fixture.store.load().taskRoot)
        val restoredAgents = Files.readString(fixture.sourceTask.resolve("AGENTS.md"))
        assertTrue(restoredAgents.contains(fixture.workspace.toString()))
        assertTrue(restoredAgents.contains("保留人工说明"))
    }

    @Test
    fun `interrupted rollback is completed from the journal on next startup`() {
        val fixture = independentFixture("interrupted-rollback", "TASK-6")
        val targetTask = fixture.targetRoot.resolve(fixture.sourceTask.fileName)
        val failing = TaskRootMigrationService(
            configStore = fixture.store,
            paths = fixture.paths,
            agentDocuments = AgentDocumentService(fixture.paths),
            sameFileStore = { _, _ -> false },
            copyTaskDirectory = { _, target ->
                Files.createDirectories(target)
                Files.writeString(target.resolve("partial.txt"), "partial")
                error("simulated copy failure")
            },
            deleteTaskDirectory = { error("simulated rollback cleanup failure") },
        )

        val error = assertFailsWith<IllegalStateException> { failing.migrate(fixture.store.load(), fixture.targetRoot) }
        assertTrue(error.message.orEmpty().contains("自动回滚未完成"))
        assertTrue(targetTask.exists())
        assertTrue(fixture.paths.home.resolve("migrations/task-root.json").exists())

        val recovered = TaskRootMigrationService(
            configStore = fixture.store,
            paths = fixture.paths,
            agentDocuments = AgentDocumentService(fixture.paths),
            sameFileStore = { _, _ -> false },
        ).recoverInterruptedMigration(fixture.store.load())

        assertTrue(recovered.isEmpty())
        assertTrue(fixture.sourceTask.exists())
        assertFalse(targetTask.exists())
        assertFalse(fixture.paths.home.resolve("migrations/task-root.json").exists())
    }

    @Test
    fun `source cleanup failure keeps journal and recovery finishes cleanup`() {
        val fixture = independentFixture("cleanup-failure", "TASK-7")
        val service = TaskRootMigrationService(
            configStore = fixture.store,
            paths = fixture.paths,
            agentDocuments = AgentDocumentService(fixture.paths),
            sameFileStore = { _, _ -> false },
            deleteTaskDirectory = { path ->
                if (path == fixture.sourceTask) error("simulated locked directory")
                error("unexpected cleanup path: $path")
            },
        )

        val result = service.migrate(fixture.store.load(), fixture.targetRoot)

        assertEquals(listOf(fixture.sourceTask), result.cleanupFailures)
        assertTrue(fixture.sourceTask.exists())
        assertTrue(fixture.targetRoot.resolve(fixture.sourceTask.fileName).exists())
        assertEquals(fixture.targetRoot.toAbsolutePath().normalize().toString(), fixture.store.load().taskRoot)
        assertTrue(fixture.paths.home.resolve("migrations/task-root.json").exists())

        val recovered = TaskRootMigrationService(
            configStore = fixture.store,
            paths = fixture.paths,
            agentDocuments = AgentDocumentService(fixture.paths),
            sameFileStore = { _, _ -> false },
        ).recoverInterruptedMigration(fixture.store.load())

        assertTrue(recovered.isEmpty())
        assertFalse(fixture.sourceTask.exists())
        assertFalse(fixture.paths.home.resolve("migrations/task-root.json").exists())
    }

    private fun independentFixture(prefix: String, taskName: String): IndependentFixture {
        val base = temporary.resolve(prefix)
        val (remote, _) = GitTestSupport.createRemoteWithSeed(base)
        val repositoryPath = GitTestSupport.clone(remote, base.resolve("source"))
        val repository = GitRepositoryInspector().inspect(repositoryPath)
        val sourceRoot = Files.createDirectories(base.resolve("tasks"))
        val sourceTask = Files.createDirectories(sourceRoot.resolve(taskName))
        val workspace = GitTestSupport.clone(remote, sourceTask.resolve("service/clone"))
        ManifestStore().save(
            sourceTask,
            manifest(taskName, repository, workspace, WorkspaceStrategy.INDEPENDENT_CLONE, "master"),
        )
        val paths = ApplicationPaths(base.resolve("home"))
        val store = ConfigStore(paths).also {
            it.save(AppConfig(taskRoot = sourceRoot.toString(), repositories = listOf(repository)))
        }
        return IndependentFixture(paths, store, repository, sourceRoot, sourceTask, workspace, base.resolve("new-tasks"))
    }

    private data class IndependentFixture(
        val paths: ApplicationPaths,
        val store: ConfigStore,
        val repository: RepositoryConfig,
        val sourceRoot: Path,
        val sourceTask: Path,
        val workspace: Path,
        val targetRoot: Path,
    )

    private fun manifest(
        taskName: String,
        repository: RepositoryConfig,
        workspacePath: Path,
        strategy: WorkspaceStrategy,
        branch: String,
    ): TaskManifest = TaskManifest(
        folderName = taskName,
        taskDirectoryName = taskName,
        featureBranch = branch,
        createdAt = "2026-08-29 00:00:00",
        updatedAt = "2026-08-29 00:00:00",
        services = listOf(
            ServiceWorkspace(
                repositoryId = repository.id,
                serviceName = "service",
                repositoryPath = repository.rootPath,
                worktreePath = workspacePath.toAbsolutePath().normalize().toString(),
                developmentTool = DevelopmentToolType.INTELLIJ_IDEA,
                branch = branch,
                health = WorkspaceHealth.READY,
                strategy = strategy,
                originUrl = repository.originUrl,
            ),
        ),
    )
}
