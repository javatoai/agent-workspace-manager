package com.snowball.awm.core

import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith

class ConfigStoreTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `system default stores application data in hidden home directory`() {
        assertEquals(
            temporary.resolve(".AgentWorkspaceManager"),
            ApplicationPaths.systemDefault(temporary.toString()).home,
        )
    }

    @Test
    fun `missing config returns one hidden default group`() {
        val store = ConfigStore(ApplicationPaths(temporary.resolve("home")))

        val config = store.load()

        assertFalse(store.exists())
        assertEquals(null, config.taskRoot)
        assertEquals(listOf(DEFAULT_GROUP_NAME), config.groups.map { it.name })
        assertEquals(emptyList<RepositoryConfig>(), config.repositories)
    }

    @Test
    fun `round trip preserves group and service array order`() {
        val paths = ApplicationPaths(temporary.resolve("home"))
        val store = ConfigStore(paths)
        val repository = RepositoryConfig(
            id = "repo-api",
            name = "api",
            rootPath = "D:\\code\\api",
            gitCommonDirectory = "D:\\code\\api\\.git",
            originUrl = "git@example.com:team/api.git",
        )
        val expected = AppConfig(
            taskRoot = "D:\\tasks",
            repositories = listOf(repository),
            groups = listOf(
                GroupConfig(
                    id = "payments",
                    name = "支付",
                    defaultBranchPrefix = "feature/pay-",
                    defaultWorkspaceToolIds = listOf("codex", "cursor"),
                    services = emptyList(),
                ),
                GroupConfig(
                    id = "growth",
                    name = "增长",
                    services = listOf(
                        GroupServiceConfig.standard(
                            id = "growth-api",
                            repositoryId = repository.id,
                            displayName = "增长 API",
                        ),
                    ),
                ),
            ),
            theme = ThemePreference.DARK,
        )

        store.save(expected)

        assertEquals(expected, store.load())
        assertEquals("0.5.1", store.load().schemaVersion)
        assertEquals(listOf("payments", "growth"), store.load().groups.map { it.id })
        assertEquals("feature/pay-", store.load().groups.first().defaultBranchPrefix)
        assertEquals(listOf("codex", "cursor"), store.load().groups.first().defaultWorkspaceToolIds)
    }

    @Test
    fun `unsupported config is rejected without being rewritten`() {
        val paths = ApplicationPaths(temporary.resolve("home"))
        Files.createDirectories(paths.home)
        val legacy = """{"schemaVersion":6,"groups":[]}"""
        Files.writeString(paths.config, legacy)

        val store = ConfigStore(paths)
        assertThrows(UnsupportedConfigVersionException::class.java) { store.load() }
        assertEquals(legacy, Files.readString(paths.config))
        assertFailsWith<UnsupportedConfigVersionException> { store.save(AppConfig()) }
        assertEquals(legacy, Files.readString(paths.config))
    }

    @Test
    fun `config from another patch release is read and saved as current patch`() {
        val paths = ApplicationPaths(temporary.resolve("home"))
        Files.createDirectories(paths.home)
        Files.writeString(
            paths.config,
            """{"schemaVersion":"0.5.9","groups":[{"id":"default","name":"默认组","services":[]}]}""",
        )

        val store = ConfigStore(paths)
        val compatible = store.load()
        assertEquals("0.5.9", compatible.schemaVersion)

        store.save(compatible)
        assertEquals(CURRENT_APP_CONFIG_SCHEMA_VERSION, store.load().schemaVersion)
    }

    @Test
    fun `config from another minor release remains rejected`() {
        val paths = ApplicationPaths(temporary.resolve("home"))
        Files.createDirectories(paths.home)
        val incompatible = """{"schemaVersion":"0.4.2","groups":[]}"""
        Files.writeString(paths.config, incompatible)

        assertThrows(UnsupportedConfigVersionException::class.java) { ConfigStore(paths).load() }
        assertEquals(incompatible, Files.readString(paths.config))
    }

    @Test
    fun `unknown fields remain an error`() {
        val paths = ApplicationPaths(temporary.resolve("home"))
        Files.createDirectories(paths.home)
        Files.writeString(
            paths.config,
            """{"schemaVersion":"$CURRENT_APP_CONFIG_SCHEMA_VERSION","groups":[],"legacyField":true}""",
        )

        val original = Files.readString(paths.config)
        val store = ConfigStore(paths)
        assertThrows(SerializationException::class.java) { store.load() }
        assertThrows(SerializationException::class.java) { store.save(AppConfig()) }
        assertEquals(original, Files.readString(paths.config))
    }
}
