package com.snowball.taskwt.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class TaskManagerIntegrationTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `creates independent worktrees archives safely and restores existing branch`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("source"))
        val repository = GitTestSupport.clone(remote, temporary.resolve("services").resolve("job-manager"))
        Files.writeString(repository.resolve("local.template"), "local")
        Files.writeString(repository.resolve(".gitignore"), "local.conf\n")
        GitTestSupport.run(repository, "add", "local.template", ".gitignore")
        GitTestSupport.run(repository, "commit", "-m", "add bootstrap template")
        GitTestSupport.run(repository, "push", "origin", "master")
        val repositoryInfo = RepositoryScanner().scan(listOf(repository.parent), null).single()
        val taskRoot = temporary.resolve("tasks")
        val service = ServiceConfig(
            repositoryId = repositoryInfo.id,
            displayName = repositoryInfo.name,
            bootstrap = BootstrapConfig(
                copyRules = listOf(BootstrapCopyRule("local.template", "local.conf")),
            ),
        )
        val config = AppConfig(
            scanRoots = listOf(repository.parent.toString()),
            taskRoot = taskRoot.toString(),
            services = mapOf(repositoryInfo.id to service),
        )
        val manager = TaskManager()

        val created = manager.create(
            config,
            listOf(repositoryInfo),
            CreateTaskRequest("OBT/123 支付", "feature/OBT-123", listOf(repositoryInfo.id), "https://example.com/req"),
        )

        assertEquals(WorkspaceStatus.READY, created.status)
        assertEquals("https://example.com/req", created.requirementLink)
        val aiDataDirectory = taskRoot.resolve(created.taskDirectoryName).resolve("ai-data")
        assertTrue(Files.isDirectory(aiDataDirectory))
        Files.delete(aiDataDirectory)
        assertEquals(aiDataDirectory, manager.ensureAiDataDirectory(taskRoot.resolve(created.taskDirectoryName)))
        assertTrue(Files.isDirectory(aiDataDirectory))
        val agentsMd = Files.readString(
            taskRoot.resolve(created.taskDirectoryName).resolve(AgentsMdWriter.FILE_NAME),
        )
        assertTrue(agentsMd.contains("https://example.com/req"))
        assertTrue(agentsMd.contains("本任务可改动的 Worktree"))
        assertTrue(agentsMd.contains("job-manager"))
        val worktree = Path.of(created.services.single().worktreePath)
        assertTrue(Files.exists(worktree.resolve("local.conf")))
        assertEquals(
            "origin",
            GitTestSupport.run(repository, "config", "--get", "branch.feature/OBT-123.remote"),
        )
        assertEquals(
            "refs/heads/feature/OBT-123",
            GitTestSupport.run(repository, "config", "--get", "branch.feature/OBT-123.merge"),
        )
        val aggregateDirectory = taskRoot.resolve(created.taskDirectoryName)
            .resolve("idea-${created.taskDirectoryName}")
        assertFalse(Files.exists(aggregateDirectory))
        assertTrue(Files.exists(worktree.resolve(".git")))

        val archived = manager.archive(config, taskRoot.resolve(created.taskDirectoryName))
        assertEquals(WorkspaceStatus.ARCHIVED, archived.status)
        assertFalse(Files.exists(worktree))
        assertTrue(GitClient().refExists(repository, "refs/heads/feature/OBT-123"))

        val restored = manager.restore(config, taskRoot.resolve(created.taskDirectoryName))
        assertEquals(WorkspaceStatus.READY, restored.status)
        assertTrue(Files.exists(worktree))

        Files.writeString(worktree.resolve("uncommitted.txt"), "dirty")
        assertThrows(IllegalStateException::class.java) {
            manager.archive(config, taskRoot.resolve(created.taskDirectoryName))
        }
        assertTrue(Files.exists(worktree))
    }

    @Test
    fun `requires explicit confirmation before reusing a local branch`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("reuse-local-source"))
        val repository = GitTestSupport.clone(remote, temporary.resolve("reuse-local-services").resolve("svc"))
        GitTestSupport.run(repository, "checkout", "-b", "feature/existing")
        GitTestSupport.run(repository, "checkout", "master")
        val info = RepositoryScanner().scan(listOf(repository.parent), null).single()
        val taskRoot = temporary.resolve("reuse-local-tasks")
        val config = AppConfig(
            taskRoot = taskRoot.toString(),
            services = mapOf(info.id to ServiceConfig(info.id, displayName = info.name)),
        )
        val manager = TaskManager()
        val request = CreateTaskRequest(
            "reuse-local",
            "feature/existing",
            listOf(info.id),
            "https://example.com/req",
        )

        val conflict = manager.inspectBranchConflicts(config, listOf(info), request.featureBranch, request.repositoryIds)
        assertTrue(conflict.single().localBranchExists)
        assertThrows(IllegalArgumentException::class.java) {
            manager.create(config, listOf(info), request)
        }
        assertFalse(Files.exists(taskRoot.resolve("reuse-local").resolve(ManifestStore.FILE_NAME)))

        val created = manager.create(
            config,
            listOf(info),
            request.copy(reuseExistingBranchRepositoryIds = setOf(info.id)),
        )
        assertTrue(Files.isDirectory(Path.of(created.services.single().worktreePath)))
        assertEquals("feature/existing", GitClient().currentBranch(Path.of(created.services.single().worktreePath)))
        assertEquals("origin", GitTestSupport.run(repository, "config", "--get", "branch.feature/existing.remote"))
    }

    @Test
    fun `reuses a remote branch and tracks the remote feature branch`() {
        val (remote, seed) = GitTestSupport.createRemoteWithSeed(temporary.resolve("reuse-remote-source"))
        val repository = GitTestSupport.clone(remote, temporary.resolve("reuse-remote-services").resolve("svc"))
        GitTestSupport.run(seed, "checkout", "-b", "feature/remote-existing")
        GitTestSupport.run(seed, "push", "-u", "origin", "feature/remote-existing")
        GitTestSupport.run(seed, "checkout", "master")
        val info = RepositoryScanner().scan(listOf(repository.parent), null).single()
        val taskRoot = temporary.resolve("reuse-remote-tasks")
        val config = AppConfig(
            taskRoot = taskRoot.toString(),
            services = mapOf(info.id to ServiceConfig(info.id, displayName = info.name)),
        )
        val manager = TaskManager()
        val request = CreateTaskRequest(
            "reuse-remote",
            "feature/remote-existing",
            listOf(info.id),
            "https://example.com/req",
            setOf(info.id),
        )

        val created = manager.create(config, listOf(info), request)
        val worktree = Path.of(created.services.single().worktreePath)
        assertEquals("origin", GitTestSupport.run(repository, "config", "--get", "branch.feature/remote-existing.remote"))
        assertEquals(
            "refs/heads/feature/remote-existing",
            GitTestSupport.run(repository, "config", "--get", "branch.feature/remote-existing.merge"),
        )
        assertEquals("feature/remote-existing", GitClient().currentBranch(worktree))
    }

    @Test
    fun `rejecting one conflicting service prevents a multi-service task from starting`() {
        val servicesRoot = temporary.resolve("atomic-conflict-services")
        val alpha = cloneNamedService(servicesRoot, "alpha-service")
        cloneNamedService(servicesRoot, "beta-service")
        GitTestSupport.run(alpha, "checkout", "-b", "feature/atomic-conflict")
        GitTestSupport.run(alpha, "checkout", "master")
        val repositories = RepositoryScanner().scan(listOf(servicesRoot), null)
        val taskRoot = temporary.resolve("atomic-conflict-tasks")
        val config = AppConfig(
            taskRoot = taskRoot.toString(),
            services = repositories.associate { it.id to ServiceConfig(it.id, displayName = it.name) },
        )
        val request = CreateTaskRequest(
            "atomic-conflict",
            "feature/atomic-conflict",
            repositories.map { it.id },
            "https://example.com/req",
        )

        assertThrows(IllegalArgumentException::class.java) {
            TaskManager().create(config, repositories, request)
        }
        assertFalse(Files.exists(taskRoot.resolve("atomic-conflict").resolve(ManifestStore.FILE_NAME)))
    }

    @Test
    fun `force reuses a branch already attached to another worktree`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("reuse-occupied-source"))
        val repository = GitTestSupport.clone(remote, temporary.resolve("reuse-occupied-services").resolve("svc"))
        GitTestSupport.run(repository, "checkout", "-b", "feature/occupied")
        GitTestSupport.run(repository, "checkout", "master")
        val existingWorktree = temporary.resolve("existing-worktree")
        GitTestSupport.run(repository, "worktree", "add", existingWorktree.toString(), "feature/occupied")
        val info = RepositoryScanner().scan(listOf(repository.parent), null).single()
        val taskRoot = temporary.resolve("reuse-occupied-tasks")
        val config = AppConfig(
            taskRoot = taskRoot.toString(),
            services = mapOf(info.id to ServiceConfig(info.id, displayName = info.name)),
        )
        val created = TaskManager().create(
            config,
            listOf(info),
            CreateTaskRequest(
                "reuse-occupied",
                "feature/occupied",
                listOf(info.id),
                "https://example.com/req",
                setOf(info.id),
            ),
        )

        val worktrees = GitClient().worktrees(repository).filter { it.branch == "feature/occupied" }
        assertEquals(2, worktrees.size)
        assertTrue(Files.isDirectory(Path.of(created.services.single().worktreePath)))
    }

    @Test
    fun `addServices appends independent worktree`() {
        val servicesRoot = temporary.resolve("services")
        cloneNamedService(servicesRoot, "alpha-service")
        cloneNamedService(servicesRoot, "beta-service")
        val repositories = RepositoryScanner().scan(listOf(servicesRoot), null)
        val firstInfo = repositories.first { it.name == "alpha-service" }
        val secondInfo = repositories.first { it.name == "beta-service" }
        val taskRoot = temporary.resolve("tasks")
        val config = AppConfig(
            scanRoots = listOf(servicesRoot.toString()),
            taskRoot = taskRoot.toString(),
            services = mapOf(
                firstInfo.id to ServiceConfig(repositoryId = firstInfo.id, displayName = firstInfo.name),
                secondInfo.id to ServiceConfig(repositoryId = secondInfo.id, displayName = secondInfo.name),
            ),
        )
        val manager = TaskManager()
        val created = manager.create(
            config,
            repositories,
            CreateTaskRequest("OBT-add", "feature/OBT-add", listOf(firstInfo.id), "https://example.com/req"),
        )
        assertEquals(1, created.services.size)

        val added = manager.addServices(
            config,
            repositories,
            taskRoot.resolve(created.taskDirectoryName),
            AddServicesRequest(listOf(secondInfo.id)),
        )
        assertEquals(2, added.services.size)
        assertTrue(added.services.all { it.status == WorkspaceStatus.READY })
        assertTrue(Files.isDirectory(Path.of(added.services.first { it.repositoryId == secondInfo.id }.worktreePath)))

        assertTrue(Files.exists(Path.of(added.services.first { it.repositoryId == firstInfo.id }.worktreePath)))
        assertTrue(Files.exists(Path.of(added.services.first { it.repositoryId == secondInfo.id }.worktreePath)))

        assertThrows(IllegalArgumentException::class.java) {
            manager.addServices(
                config,
                repositories,
                taskRoot.resolve(created.taskDirectoryName),
                AddServicesRequest(listOf(secondInfo.id)),
            )
        }

        manager.archive(config, taskRoot.resolve(created.taskDirectoryName))
        val archivedError = assertThrows(IllegalArgumentException::class.java) {
            manager.addServices(
                config,
                repositories,
                taskRoot.resolve(created.taskDirectoryName),
                AddServicesRequest(listOf(secondInfo.id)),
            )
        }
        assertTrue(archivedError.message!!.contains("归档"))
    }

    @Test
    fun `initialize does not revive FAILED when worktree missing`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("source"))
        val repository = GitTestSupport.clone(remote, temporary.resolve("services").resolve("svc"))
        val repositoryInfo = RepositoryScanner().scan(listOf(repository.parent), null).single()
        val taskRoot = temporary.resolve("tasks")
        val config = AppConfig(
            scanRoots = listOf(repository.parent.toString()),
            taskRoot = taskRoot.toString(),
            services = mapOf(
                repositoryInfo.id to ServiceConfig(
                    repositoryId = repositoryInfo.id,
                    displayName = repositoryInfo.name,
                    bootstrap = BootstrapPresets.empty(),
                ),
            ),
        )
        val manager = TaskManager()
        val created = manager.create(
            config,
            listOf(repositoryInfo),
            CreateTaskRequest("OBT-fail", "feature/OBT-fail", listOf(repositoryInfo.id), "https://example.com/req"),
        )
        val taskDirectory = taskRoot.resolve(created.taskDirectoryName)
        val worktree = Path.of(created.services.single().worktreePath)
        GitClient().removeWorktree(repository, worktree, force = true)
        assertFalse(Files.exists(worktree))

        val store = ManifestStore()
        val failed = created.copy(
            status = WorkspaceStatus.FAILED,
            services = created.services.map {
                it.copy(status = WorkspaceStatus.FAILED, warnings = listOf("checkout failed"))
            },
        )
        store.save(taskDirectory, failed)

        val afterInit = manager.initialize(config, taskDirectory, failedOnly = false)
        assertEquals(WorkspaceStatus.FAILED, afterInit.status)
        assertEquals(WorkspaceStatus.FAILED, afterInit.services.single().status)
        assertFalse(Files.exists(worktree))

        // Residual directory that is not a registered worktree must not become READY.
        Files.createDirectories(worktree)
        Files.writeString(worktree.resolve("junk.txt"), "leftover")
        val afterResidual = manager.initialize(config, taskDirectory, failedOnly = false)
        assertEquals(WorkspaceStatus.FAILED, afterResidual.status)
        assertEquals(WorkspaceStatus.FAILED, afterResidual.services.single().status)
        assertEquals(listOf("checkout failed"), afterResidual.services.single().warnings)
    }

    @Test
    fun `addServices succeeds when feature branch already exists on target repo`() {
        val servicesRoot = temporary.resolve("services-existing-branch")
        cloneNamedService(servicesRoot, "alpha-svc")
        cloneNamedService(servicesRoot, "beta-svc")
        val repositories = RepositoryScanner().scan(listOf(servicesRoot), null)
        val firstInfo = repositories.first { it.name == "alpha-svc" }
        val secondInfo = repositories.first { it.name == "beta-svc" }
        val betaRepo = Path.of(secondInfo.rootPath)
        val branch = "feature/OBT-shared"
        GitTestSupport.run(betaRepo, "checkout", "-b", branch)
        GitTestSupport.run(betaRepo, "push", "-u", "origin", branch)
        GitTestSupport.run(betaRepo, "checkout", "master")

        val taskRoot = temporary.resolve("tasks-existing-branch")
        val config = AppConfig(
            scanRoots = listOf(servicesRoot.toString()),
            taskRoot = taskRoot.toString(),
            services = mapOf(
                firstInfo.id to ServiceConfig(repositoryId = firstInfo.id, displayName = firstInfo.name),
                secondInfo.id to ServiceConfig(repositoryId = secondInfo.id, displayName = secondInfo.name),
            ),
        )
        val manager = TaskManager()
        val created = manager.create(
            config,
            repositories,
            CreateTaskRequest(
                "OBT-shared",
                branch,
                listOf(firstInfo.id),
                "https://example.com/req",
            ),
        )
        val added = manager.addServices(
            config,
            repositories,
            taskRoot.resolve(created.taskDirectoryName),
            AddServicesRequest(listOf(secondInfo.id), setOf(secondInfo.id)),
        )
        assertEquals(2, added.services.size)
        val beta = added.services.single { it.repositoryId == secondInfo.id }
        assertEquals(WorkspaceStatus.READY, beta.status)
        assertTrue(Files.isDirectory(Path.of(beta.worktreePath)))
        assertTrue(GitClient().refExists(betaRepo, "refs/heads/$branch"))
    }

    @Test
    fun `retryFailedServices restores missing worktree when branch already exists`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("source"))
        val repository = GitTestSupport.clone(remote, temporary.resolve("services").resolve("retry-svc"))
        val repositoryInfo = RepositoryScanner().scan(listOf(repository.parent), null).single()
        val taskRoot = temporary.resolve("tasks")
        val config = AppConfig(
            scanRoots = listOf(repository.parent.toString()),
            taskRoot = taskRoot.toString(),
            services = mapOf(
                repositoryInfo.id to ServiceConfig(
                    repositoryId = repositoryInfo.id,
                    displayName = repositoryInfo.name,
                ),
            ),
        )
        val manager = TaskManager()
        val created = manager.create(
            config,
            listOf(repositoryInfo),
            CreateTaskRequest("OBT-retry", "feature/OBT-retry", listOf(repositoryInfo.id), "https://example.com/req"),
        )
        val taskDirectory = taskRoot.resolve(created.taskDirectoryName)
        val worktree = Path.of(created.services.single().worktreePath)
        GitClient().removeWorktree(repository, worktree, force = true)
        assertTrue(GitClient().refExists(repository, "refs/heads/feature/OBT-retry"))

        ManifestStore().save(
            taskDirectory,
            created.copy(
                status = WorkspaceStatus.FAILED,
                services = created.services.map {
                    it.copy(status = WorkspaceStatus.FAILED, warnings = listOf("checkout failed"))
                },
            ),
        )

        val retried = manager.retryFailedServices(config, taskDirectory)
        assertEquals(WorkspaceStatus.READY, retried.status)
        assertEquals(WorkspaceStatus.READY, retried.services.single().status)
        assertTrue(Files.isDirectory(Path.of(retried.services.single().worktreePath)))
    }

    @Test
    fun `retryFailedServices creates branch when missing`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("source"))
        val repository = GitTestSupport.clone(remote, temporary.resolve("services").resolve("retry-new"))
        val repositoryInfo = RepositoryScanner().scan(listOf(repository.parent), null).single()
        val taskRoot = temporary.resolve("tasks")
        val config = AppConfig(
            scanRoots = listOf(repository.parent.toString()),
            taskRoot = taskRoot.toString(),
            services = mapOf(
                repositoryInfo.id to ServiceConfig(
                    repositoryId = repositoryInfo.id,
                    displayName = repositoryInfo.name,
                ),
            ),
        )
        val manager = TaskManager()
        val created = manager.create(
            config,
            listOf(repositoryInfo),
            CreateTaskRequest("OBT-retry2", "feature/OBT-retry2", listOf(repositoryInfo.id), "https://example.com/req"),
        )
        val taskDirectory = taskRoot.resolve(created.taskDirectoryName)
        val worktree = Path.of(created.services.single().worktreePath)
        GitClient().removeWorktree(repository, worktree, force = true)
        GitTestSupport.run(repository, "branch", "-D", "feature/OBT-retry2")
        assertFalse(GitClient().refExists(repository, "refs/heads/feature/OBT-retry2"))

        ManifestStore().save(
            taskDirectory,
            created.copy(
                status = WorkspaceStatus.FAILED,
                services = created.services.map {
                    it.copy(status = WorkspaceStatus.FAILED, warnings = listOf("checkout failed"))
                },
            ),
        )

        val retried = manager.retryFailedServices(config, taskDirectory)
        assertEquals(WorkspaceStatus.READY, retried.status)
        assertTrue(Files.isDirectory(Path.of(retried.services.single().worktreePath)))
        assertTrue(GitClient().refExists(repository, "refs/heads/feature/OBT-retry2"))
    }

    @Test
    fun `delete removes worktree and task directory but keeps feature branch`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("source-delete"))
        val repository = GitTestSupport.clone(remote, temporary.resolve("services").resolve("delete-svc"))
        val repositoryInfo = RepositoryScanner().scan(listOf(repository.parent), null).single()
        val taskRoot = temporary.resolve("tasks-delete")
        val config = AppConfig(
            scanRoots = listOf(repository.parent.toString()),
            taskRoot = taskRoot.toString(),
            services = mapOf(
                repositoryInfo.id to ServiceConfig(
                    repositoryId = repositoryInfo.id,
                    displayName = repositoryInfo.name,
                ),
            ),
        )
        val manager = TaskManager()
        val created = manager.create(
            config,
            listOf(repositoryInfo),
            CreateTaskRequest("OBT-del", "feature/OBT-del", listOf(repositoryInfo.id), "https://example.com/req"),
        )
        val taskDirectory = taskRoot.resolve(created.taskDirectoryName)
        val worktree = Path.of(created.services.single().worktreePath)
        assertTrue(Files.isDirectory(worktree))
        assertTrue(Files.isDirectory(taskDirectory))

        manager.delete(taskDirectory)

        assertFalse(Files.exists(worktree))
        assertFalse(Files.exists(taskDirectory))
        assertTrue(GitClient().refExists(repository, "refs/heads/feature/OBT-del"))
    }

    @Test
    fun `delete requires forceDiscard when worktree has uncommitted files`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("source-dirty"))
        val repository = GitTestSupport.clone(remote, temporary.resolve("services").resolve("dirty-svc"))
        val repositoryInfo = RepositoryScanner().scan(listOf(repository.parent), null).single()
        val taskRoot = temporary.resolve("tasks-dirty")
        val config = AppConfig(
            scanRoots = listOf(repository.parent.toString()),
            taskRoot = taskRoot.toString(),
            services = mapOf(
                repositoryInfo.id to ServiceConfig(
                    repositoryId = repositoryInfo.id,
                    displayName = repositoryInfo.name,
                ),
            ),
        )
        val manager = TaskManager()
        val created = manager.create(
            config,
            listOf(repositoryInfo),
            CreateTaskRequest("OBT-dirty", "feature/OBT-dirty", listOf(repositoryInfo.id), "https://example.com/req"),
        )
        val taskDirectory = taskRoot.resolve(created.taskDirectoryName)
        val worktree = Path.of(created.services.single().worktreePath)
        Files.writeString(worktree.resolve("uncommitted.txt"), "dirty")

        val risks = manager.inspectDeleteRisk(taskDirectory)
        assertEquals(1, risks.size)
        assertTrue(risks.single().untracked)

        val error = assertThrows(IllegalStateException::class.java) {
            manager.delete(taskDirectory)
        }
        assertTrue(error.message!!.contains("未提交"))
        assertTrue(Files.exists(worktree))
        assertTrue(Files.exists(taskDirectory))

        manager.delete(taskDirectory, forceDiscard = true)
        assertFalse(Files.exists(worktree))
        assertFalse(Files.exists(taskDirectory))
        assertTrue(GitClient().refExists(repository, "refs/heads/feature/OBT-dirty"))
    }

    @Test
    fun `delete allows unpushed commits without force while archive does not`() {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("source-unpushed"))
        val repository = GitTestSupport.clone(remote, temporary.resolve("services").resolve("unpushed-svc"))
        val repositoryInfo = RepositoryScanner().scan(listOf(repository.parent), null).single()
        val taskRoot = temporary.resolve("tasks-unpushed")
        val config = AppConfig(
            scanRoots = listOf(repository.parent.toString()),
            taskRoot = taskRoot.toString(),
            services = mapOf(
                repositoryInfo.id to ServiceConfig(
                    repositoryId = repositoryInfo.id,
                    displayName = repositoryInfo.name,
                ),
            ),
        )
        val manager = TaskManager()
        val created = manager.create(
            config,
            listOf(repositoryInfo),
            CreateTaskRequest("OBT-unpushed", "feature/OBT-unpushed", listOf(repositoryInfo.id), "https://example.com/req"),
        )
        val taskDirectory = taskRoot.resolve(created.taskDirectoryName)
        val worktree = Path.of(created.services.single().worktreePath)
        Files.writeString(worktree.resolve("local-only.txt"), "commit me")
        GitTestSupport.run(worktree, "add", "local-only.txt")
        GitTestSupport.run(worktree, "commit", "-m", "local only commit")

        val status = GitClient().status(worktree)
        assertTrue(status.unpushedCommits > 0)
        assertFalse(status.hasUncommittedChanges)
        assertFalse(status.safeToArchive)
        assertTrue(manager.inspectDeleteRisk(taskDirectory).isEmpty())

        assertThrows(IllegalStateException::class.java) {
            manager.archive(config, taskDirectory)
        }
        assertTrue(Files.exists(worktree))

        manager.delete(taskDirectory)
        assertFalse(Files.exists(worktree))
        assertFalse(Files.exists(taskDirectory))
        assertTrue(GitClient().refExists(repository, "refs/heads/feature/OBT-unpushed"))
    }

    @Test
    fun `treats git status failure as a delete risk`() {
        val taskDirectory = temporary.resolve("task-status-failure")
        val worktree = temporary.resolve("not-a-git-worktree")
        val repository = temporary.resolve("not-a-repository")
        Files.createDirectories(worktree)
        Files.createDirectories(repository)
        val workspace = ServiceWorkspace(
            repositoryId = "repo-test",
            serviceName = "broken-service",
            repositoryPath = repository.toString(),
            worktreePath = worktree.toString(),
            ideType = IdeType.IDEA,
            branch = "feature/test",
        )
        ManifestStore().save(
            taskDirectory,
            TaskManifest(
                folderName = "status-failure",
                taskDirectoryName = taskDirectory.fileName.toString(),
                featureBranch = "feature/test",
                createdAt = "2026-01-01T00:00:00Z",
                updatedAt = "2026-01-01T00:00:00Z",
                status = WorkspaceStatus.READY,
                services = listOf(workspace),
            ),
        )

        val manager = TaskManager()
        val risks = manager.inspectDeleteRisk(taskDirectory)

        assertEquals(1, risks.size)
        assertTrue(risks.single().statusCheckError != null)
        assertThrows(IllegalStateException::class.java) {
            manager.delete(taskDirectory)
        }
        assertTrue(Files.exists(worktree))
        assertTrue(Files.exists(taskDirectory))
    }

    @Test
    fun `create rejects blank folderName or requirementLink`() {
        val config = AppConfig(taskRoot = temporary.resolve("tasks").toString())
        val manager = TaskManager()
        assertThrows(IllegalArgumentException::class.java) {
            manager.create(
                config,
                emptyList(),
                CreateTaskRequest("  ", "feature/x", listOf("id"), "https://example.com"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            manager.create(
                config,
                emptyList(),
                CreateTaskRequest("OBT-1", "feature/x", listOf("id"), "   "),
            )
        }
    }

    @Test
    fun `agents md includes other local repos and appendix`() {
        val servicesRoot = temporary.resolve("services")
        cloneNamedService(servicesRoot, "alpha-service")
        cloneNamedService(servicesRoot, "beta-service")
        val repositories = RepositoryScanner().scan(listOf(servicesRoot), null)
        val firstInfo = repositories.first { it.name == "alpha-service" }
        val secondInfo = repositories.first { it.name == "beta-service" }
        val taskRoot = temporary.resolve("tasks")
        val config = AppConfig(
            scanRoots = listOf(servicesRoot.toString()),
            taskRoot = taskRoot.toString(),
            services = mapOf(
                firstInfo.id to ServiceConfig(repositoryId = firstInfo.id, displayName = firstInfo.name),
                secondInfo.id to ServiceConfig(repositoryId = secondInfo.id, displayName = secondInfo.name),
            ),
            agentsMdAppendix = "团队约定：先跑单测",
        )
        val manager = TaskManager()
        val created = manager.create(
            config,
            repositories,
            CreateTaskRequest(
                "OBT-agents",
                "feature/OBT-agents",
                listOf(firstInfo.id),
                "飞书需求：支付改造",
            ),
        )
        val agentsPath = taskRoot.resolve(created.taskDirectoryName).resolve(AgentsMdWriter.FILE_NAME)
        val content = Files.readString(agentsPath)
        assertTrue(content.contains("飞书需求：支付改造"))
        assertTrue(content.contains("alpha-service"))
        assertTrue(content.contains("其它本地服务（只读上下文）"))
        assertTrue(content.contains("beta-service"))
        assertTrue(content.contains(secondInfo.rootPath))
        assertTrue(content.contains("## 自定义说明"))
        assertTrue(content.contains("团队约定：先跑单测"))

        val refreshedConfig = config.copy(agentsMdAppendix = "刷新后的附录")
        manager.refreshAgentsMd(
            refreshedConfig,
            taskRoot.resolve(created.taskDirectoryName),
            repositories,
        )
        val refreshed = Files.readString(agentsPath)
        assertTrue(refreshed.contains("刷新后的附录"))
        assertFalse(refreshed.contains("团队约定：先跑单测"))
    }

    private fun cloneNamedService(servicesRoot: Path, name: String): Path {
        val (remote, _) = GitTestSupport.createRemoteWithSeed(temporary.resolve("remotes").resolve(name))
        return GitTestSupport.clone(remote, servicesRoot.resolve(name))
    }
}
