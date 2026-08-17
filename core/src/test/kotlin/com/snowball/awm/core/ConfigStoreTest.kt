package com.snowball.awm.core

import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
    fun `concurrent config mutations are serialized and preserve both updates`() {
        val store = ConfigStore(ApplicationPaths(temporary.resolve("concurrent-home")))
        store.save(AppConfig())
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val activeTransforms = AtomicInteger()
        val maximumConcurrentTransforms = AtomicInteger()
        val executor = Executors.newFixedThreadPool(2)

        fun recordTransform(block: (AppConfig) -> AppConfig): AppConfig = store.update { current ->
            val active = activeTransforms.incrementAndGet()
            maximumConcurrentTransforms.accumulateAndGet(active, ::maxOf)
            try {
                Thread.sleep(100)
                block(current)
            } finally {
                activeTransforms.decrementAndGet()
            }
        }

        try {
            val taskRoot = executor.submit<AppConfig> {
                ready.countDown()
                check(start.await(5, TimeUnit.SECONDS))
                recordTransform { it.copy(taskRoot = "D:/tasks") }
            }
            val theme = executor.submit<AppConfig> {
                ready.countDown()
                check(start.await(5, TimeUnit.SECONDS))
                recordTransform { it.copy(theme = ThemePreference.DARK) }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            taskRoot.get(10, TimeUnit.SECONDS)
            theme.get(10, TimeUnit.SECONDS)

            assertEquals(1, maximumConcurrentTransforms.get())
            assertEquals("D:/tasks", store.load().taskRoot)
            assertEquals(ThemePreference.DARK, store.load().theme)
        } finally {
            executor.shutdownNow()
        }
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
            developmentTools = listOf(
                DevelopmentToolConfig(DevelopmentToolType.ANDROID_STUDIO, "D:\\tools\\studio64.exe"),
                DevelopmentToolConfig(DevelopmentToolType.VISUAL_STUDIO_CODE, "D:\\tools\\Code.exe"),
            ),
            defaultDevelopmentTool = DevelopmentToolType.VISUAL_STUDIO_CODE,
            allowTemporaryDevelopmentToolSelection = true,
            hiddenTaskDetailBranches = listOf("master", "develop"),
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
        assertEquals("0.8.1", store.load().schemaVersion)
        assertEquals(listOf("payments", "growth"), store.load().groups.map { it.id })
        assertEquals("feature/pay-", store.load().groups.first().defaultBranchPrefix)
        assertEquals(listOf("codex", "cursor"), store.load().groups.first().defaultWorkspaceToolIds)
    }

    @Test
    fun `legacy auto open field from 0_7 is rejected without rewrite`() {
        val paths = ApplicationPaths(temporary.resolve("legacy-auto-open"))
        Files.createDirectories(paths.home)
        Files.writeString(
            paths.config,
            """
            {
              "schemaVersion": "0.7.0",
              "autoOpenServicesAfterTaskCreation": true
            }
            """.trimIndent(),
        )
        val store = ConfigStore(paths)

        assertFailsWith<UnsupportedConfigVersionException> { store.load() }
        assertTrue(Files.readString(paths.config).contains("autoOpenServicesAfterTaskCreation"))
    }

    @Test
    fun `save keeps recoverable backups and import rejects another minor version`() {
        val paths = ApplicationPaths(temporary.resolve("home-backups"))
        val store = ConfigStore(paths)
        store.save(AppConfig(taskRoot = "D:/first"))
        store.save(AppConfig(taskRoot = "D:/second"))

        val backup = store.backups().single()
        assertEquals("D:/first", store.restore(backup.path).taskRoot)

        val incompatible = temporary.resolve("old.json")
        Files.writeString(incompatible, """{"schemaVersion":"0.6.0"}""")
        assertFailsWith<UnsupportedConfigVersionException> { store.importFrom(incompatible) }
        assertEquals("D:/first", store.load().taskRoot)
    }

    @Test
    fun `configuration can be exported and imported after validation`() {
        val sourceStore = ConfigStore(ApplicationPaths(temporary.resolve("source")))
        sourceStore.save(AppConfig(taskRoot = "D:/tasks"))
        val exported = sourceStore.exportTo(temporary.resolve("shared/config.json"))
        val targetStore = ConfigStore(ApplicationPaths(temporary.resolve("target")))

        val preview = targetStore.previewImport(exported)
        assertTrue(preview.changes.any { it.startsWith("任务路径") })

        val imported = targetStore.importFrom(exported)

        assertEquals("D:/tasks", imported.taskRoot)
        assertTrue(targetStore.exists())
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
            """{"schemaVersion":"0.8.9","groups":[{"id":"default","name":"默认组","services":[]}]}""",
        )

        val store = ConfigStore(paths)
        val compatible = store.load()
        assertEquals("0.8.9", compatible.schemaVersion)

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

    @Test
    fun `removed source repository strategy is rejected without rewriting config`() {
        val paths = ApplicationPaths(temporary.resolve("home"))
        Files.createDirectories(paths.home)
        val original = """{"schemaVersion":"0.7.0","groups":[{"id":"default","name":"默认组","services":[{"id":"source","repositoryId":"repo","displayName":"source","strategy":"SOURCE_REPOSITORY"}]}]}"""
        Files.writeString(paths.config, original)

        val error = assertThrows(UnsupportedConfigVersionException::class.java) { ConfigStore(paths).load() }

        assertTrue(error.message.orEmpty().contains("版本不受支持"))
        assertEquals(original, Files.readString(paths.config))
    }

    @Test
    fun `platforms remains unknown outside bootstrap commands`() {
        val paths = ApplicationPaths(temporary.resolve("home"))
        Files.createDirectories(paths.home)
        Files.writeString(
            paths.config,
            """{"schemaVersion":"$CURRENT_APP_CONFIG_SCHEMA_VERSION","groups":[],"platforms":[]}""",
        )

        assertThrows(SerializationException::class.java) { ConfigStore(paths).load() }
    }

    @Test
    fun `early 0_7 tag fields and bootstrap platforms are normalized on save`() {
        val paths = ApplicationPaths(temporary.resolve("home"))
        Files.createDirectories(paths.home)
        Files.writeString(
            paths.config,
            """{
              "schemaVersion":"0.7.0",
              "repositories":[{"id":"repo","name":"repo","rootPath":"D:/repo","gitCommonDirectory":"D:/repo/.git","originUrl":"https://example.test/repo.git"}],
              "groups":[{
                "id":"default","name":"默认组","uatTagEnabled":true,
                "services":[{
                  "id":"service","repositoryId":"repo","displayName":"Service",
                  "modules":[{"id":"default","uatTagEnabled":true,"uatRef":"origin/release/test","initialUatTag":"1.0.0","initialTag":"2.0.0","tagMessagePrefix":"UAT"}],
                  "bootstrap":{"commands":[{"name":"init","executable":"git","platforms":["windows"]}]}
                }]
              }]
            }""".trimIndent(),
        )

        assertFailsWith<UnsupportedConfigVersionException> { ConfigStore(paths).load() }
        assertTrue(Files.readString(paths.config).contains("uatTagEnabled"))
    }

}
