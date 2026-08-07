package com.snowball.taskwt.core

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GroupConfigurationServiceTest {
    @Test
    fun `groups retain array order and only empty groups can be deleted`() {
        val repository = InMemoryConfigurationRepository(AppConfig())
        val service = GroupConfigurationService(repository, StubRepositoryInspector())

        val alpha = service.addGroup("Alpha").groups.last()
        val beta = service.addGroup("Beta").groups.last()
        service.moveGroup(beta.id, -1)

        assertEquals(listOf(DEFAULT_GROUP_ID, beta.id, alpha.id), repository.load().groups.map { it.id })
        service.addRepository(alpha.id, Path.of("C:/repo-a"))
        assertFailsWith<IllegalStateException> { service.deleteGroup(alpha.id) }
        service.deleteGroup(beta.id)
        assertEquals(listOf(DEFAULT_GROUP_ID, alpha.id), repository.load().groups.map { it.id })
    }

    @Test
    fun `single group mode remains hidden until a second group exists`() {
        val repository = InMemoryConfigurationRepository(AppConfig())
        val service = GroupConfigurationService(repository, StubRepositoryInspector())

        assertEquals(false, service.load().showsGroupUi)
        service.addGroup("Second")
        assertEquals(true, service.load().showsGroupUi)
    }

    @Test
    fun `same repository is reused across groups but cannot repeat inside one group`() {
        val repository = InMemoryConfigurationRepository(AppConfig())
        val service = GroupConfigurationService(repository, StubRepositoryInspector())
        val second = service.addGroup("Second").groups.last()

        service.addRepository(DEFAULT_GROUP_ID, Path.of("C:/repo-a"))
        service.addRepository(second.id, Path.of("C:/repo-a"))

        assertEquals(1, repository.load().repositories.size)
        assertEquals("origin/main", repository.load().group(DEFAULT_GROUP_ID).services.single().modules.single().baseRef)
        assertEquals(1, repository.load().group(DEFAULT_GROUP_ID).services.size)
        assertEquals(1, repository.load().group(second.id).services.size)
        assertFailsWith<IllegalArgumentException> {
            service.addRepository(DEFAULT_GROUP_ID, Path.of("C:/repo-a"))
        }
    }

    @Test
    fun `task scan failures block destructive group changes`() {
        val extraGroup = ServiceGroupConfig(id = "extra", name = "Extra")
        val repository = InMemoryConfigurationRepository(
            AppConfig(taskRoot = "C:/tasks", groups = AppConfig().groups + extraGroup),
        )
        val failingManifests = object : TaskManifestRepository {
            override fun save(taskDirectory: Path, manifest: TaskManifest) = Unit
            override fun load(taskDirectory: Path): TaskManifest = error("unused")
            override fun scan(taskRoot: Path) = ManifestScanResult(
                current = emptyList(),
                ignoredLegacyDirectories = emptyList(),
                failures = mapOf(taskRoot.resolve("broken") to "invalid JSON"),
            )
        }
        val service = GroupConfigurationService(
            repository,
            StubRepositoryInspector(),
            ManifestTaskGroupUsage(failingManifests),
        )

        assertFailsWith<IllegalStateException> { service.deleteGroup(extraGroup.id) }
        assertEquals(listOf(DEFAULT_GROUP_ID, extraGroup.id), repository.load().groups.map { it.id })
    }
}

private class InMemoryConfigurationRepository(
    private var value: AppConfig,
) : ConfigurationRepository {
    override fun load(): AppConfig = value
    override fun save(config: AppConfig) {
        value = config
    }
}

private class StubRepositoryInspector : RepositoryInspector {
    override fun inspect(selectedDirectory: Path): RepositoryConfig {
        val name = selectedDirectory.fileName.toString()
        return RepositoryConfig(
            id = name,
            name = name,
            rootPath = selectedDirectory.toAbsolutePath().normalize().toString(),
            gitCommonDirectory = "C:/git-common/$name",
            originUrl = "https://example.test/$name.git",
            currentBranch = "master",
            defaultRemoteBranch = "main",
        )
    }
}
