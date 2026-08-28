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
    fun `file snapshot exposes malformed content without parsing or rewriting it`() {
        val paths = ApplicationPaths(temporary.resolve("preview-home"))
        Files.createDirectories(paths.home)
        val original = "{ invalid json"
        Files.writeString(paths.config, original)

        val snapshot = ConfigStore(paths).fileSnapshot()

        assertEquals(paths.config.toAbsolutePath().normalize(), snapshot.path)
        assertTrue(snapshot.exists)
        assertEquals(original, snapshot.content)
        assertEquals(null, snapshot.readError)
        assertEquals(original, Files.readString(paths.config))
    }

    @Test
    fun `file snapshot reports a configuration that has not been created`() {
        val paths = ApplicationPaths(temporary.resolve("missing-preview-home"))

        val snapshot = ConfigStore(paths).fileSnapshot()

        assertEquals(paths.config.toAbsolutePath().normalize(), snapshot.path)
        assertFalse(snapshot.exists)
        assertEquals(null, snapshot.content)
    }

    @Test
    fun `file snapshot returns the raw current configuration`() {
        val paths = ApplicationPaths(temporary.resolve("current-preview-home"))
        val store = ConfigStore(paths)
        store.save(AppConfig(taskRoot = "D:/tasks"))

        val snapshot = store.fileSnapshot()

        assertTrue(snapshot.exists)
        assertTrue(snapshot.content.orEmpty().contains("\"schemaVersion\": \"$CURRENT_APP_CONFIG_SCHEMA_VERSION\""))
        assertTrue(snapshot.content.orEmpty().contains("\"taskRoot\": \"D:/tasks\""))
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
        assertEquals(CURRENT_APP_CONFIG_SCHEMA_VERSION, store.load().schemaVersion)
        assertEquals(listOf("payments", "growth"), store.load().groups.map { it.id })
        assertEquals("feature/pay-", store.load().groups.first().defaultBranchPrefix)
        assertEquals(listOf("codex", "cursor"), store.load().groups.first().defaultWorkspaceToolIds)
    }

    @Test
    fun `legacy Genbu detection audit is ignored on load and omitted on save`() {
        val paths = ApplicationPaths(temporary.resolve("retired-genbu-audit"))
        Files.createDirectories(paths.home)
        Files.writeString(
            paths.config,
            """
            {
              "schemaVersion": "$CURRENT_APP_CONFIG_SCHEMA_VERSION",
              "taskRoot": "D:/tasks",
              "genbuDetectionAudit": [
                {"detectedAt": "2026-08-24 12:00:00", "status": "LOADED", "command": "genbu"}
              ]
            }
            """.trimIndent(),
        )

        val loaded = ConfigStore(paths).load()

        assertEquals("D:/tasks", loaded.taskRoot)
        assertTrue(Files.readString(paths.config).contains("genbuDetectionAudit"))

        ConfigStore(paths).save(loaded)

        val persisted = Files.readString(paths.config)
        assertFalse(persisted.contains("genbuDetectionAudit"))
        assertEquals("D:/tasks", ConfigStore(paths).load().taskRoot)
    }

    @Test
    fun `retired Genbu detection audit does not weaken strict unknown field validation`() {
        val paths = ApplicationPaths(temporary.resolve("retired-genbu-audit-unknown"))
        Files.createDirectories(paths.home)
        Files.writeString(
            paths.config,
            """
            {
              "schemaVersion": "$CURRENT_APP_CONFIG_SCHEMA_VERSION",
              "genbuDetectionAudit": [],
              "unknownField": true
            }
            """.trimIndent(),
        )

        assertThrows(SerializationException::class.java) { ConfigStore(paths).load() }
    }

    @Test
    fun `retired production Tag switches are ignored and omitted on save`() {
        val paths = ApplicationPaths(temporary.resolve("legacy-production-tag"))
        Files.createDirectories(paths.home)
        Files.writeString(
            paths.config,
            """
            {
              "schemaVersion": "$CURRENT_APP_CONFIG_SCHEMA_VERSION",
              "productionTagBuildEnabled": false,
              "groups": [
                {"id": "one", "name": "一组", "productionTagBuildEnabled": true, "services": []},
                {"id": "two", "name": "二组", "productionTagBuildEnabled": false, "services": []}
              ]
            }
            """.trimIndent(),
        )

        val loaded = ConfigStore(paths).load()
        assertEquals(listOf("one", "two"), loaded.groups.map { it.id })

        ConfigStore(paths).save(loaded)

        val persisted = Files.readString(paths.config)
        assertFalse(persisted.contains("productionTagBuildEnabled"))
        assertEquals(listOf("one", "two"), ConfigStore(paths).load().groups.map { it.id })
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
        sourceStore.save(
            AppConfig(
                taskRoot = "D:/tasks",
                requirementMaterialsRoot = "D:/requirement-materials",
                requirementMaterialsSubdirectory = "研发",
            ),
        )
        val exported = sourceStore.exportTo(temporary.resolve("shared/config.json"))
        val targetStore = ConfigStore(ApplicationPaths(temporary.resolve("target")))

        val preview = targetStore.previewImport(exported)
        assertTrue(preview.changes.any { it.startsWith("任务路径") })
        assertTrue(preview.changes.any { it.startsWith("需求资料根路径") })
        assertTrue(preview.changes.any { it.startsWith("需求资料子目录") })

        val imported = targetStore.importFrom(exported)

        assertEquals("D:/tasks", imported.taskRoot)
        assertEquals("D:/requirement-materials", imported.requirementMaterialsRoot)
        assertEquals("研发", imported.requirementMaterialsSubdirectory)
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
    fun `config from another patch release in the current minor line is read and saved as current patch`() {
        val paths = ApplicationPaths(temporary.resolve("home"))
        Files.createDirectories(paths.home)
        Files.writeString(
            paths.config,
            """{"schemaVersion":"0.12.9","groups":[{"id":"default","name":"默认组","services":[]}]}""",
        )

        val store = ConfigStore(paths)
        val compatible = store.load()
        assertEquals("0.12.9", compatible.schemaVersion)

        store.save(compatible)
        assertEquals(CURRENT_APP_CONFIG_SCHEMA_VERSION, store.load().schemaVersion)
    }

    @Test
    fun `removed requirement documentation root is rejected without rewriting config`() {
        val paths = ApplicationPaths(temporary.resolve("removed-documentation-root"))
        Files.createDirectories(paths.home)
        val original = """{
          "schemaVersion":"$CURRENT_APP_CONFIG_SCHEMA_VERSION",
          "requirementDocumentationRoot":"D:/docs",
          "groups":[{"id":"default","name":"默认组","services":[]}]
        }""".trimIndent()
        Files.writeString(paths.config, original)

        assertThrows(SerializationException::class.java) { ConfigStore(paths).load() }
        assertEquals(original, Files.readString(paths.config))
    }

    @Test
    fun `requirement materials settings round trip and blank settings stay disabled`() {
        val paths = ApplicationPaths(temporary.resolve("requirement-materials"))
        val store = ConfigStore(paths)
        val configured = AppConfig(
            requirementMaterialsRoot = "D:/research-materials",
            requirementMaterialsSubdirectory = " 研发 ",
        )

        store.save(configured)

        assertEquals("D:/research-materials", store.load().requirementMaterialsRoot)
        assertEquals(" 研发 ", store.load().requirementMaterialsSubdirectory)
        assertTrue(store.load().requirementMaterialsConfigured)
        assertFalse(AppConfig(requirementMaterialsRoot = "D:/research-materials").requirementMaterialsConfigured)
        assertFalse(AppConfig(requirementMaterialsSubdirectory = "研发").requirementMaterialsConfigured)
    }

    @Test
    fun `requirement materials subdirectory rejects unsafe Windows names`() {
        listOf(".", "..", "a/b", "a\\b", "bad:name", "name.", " name . ", "CON", "com1.txt").forEach { value ->
            assertFailsWith<IllegalArgumentException> {
                AppConfig(requirementMaterialsSubdirectory = value)
            }
        }
        assertEquals("研发", validateRequirementMaterialsSubdirectory(" 研发 "))
        assertEquals("", validateRequirementMaterialsSubdirectory("   "))
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
    fun `0 9 config is rejected without migration`() {
        val paths = ApplicationPaths(temporary.resolve("legacy-0-9"))
        Files.createDirectories(paths.home)
        val legacy = """{"schemaVersion":"0.9.11","groups":[{"id":"default","name":"默认组","services":[]}]}"""
        Files.writeString(paths.config, legacy)

        assertThrows(UnsupportedConfigVersionException::class.java) { ConfigStore(paths).load() }
        assertEquals(legacy, Files.readString(paths.config))
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
