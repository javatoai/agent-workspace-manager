package com.snowball.awm.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentOperationServiceTest {
    @Test
    fun `inspect reads compatible configuration with forward fields without allowing writes`() {
        val root = Files.createTempDirectory("agent-compatible-config-")
        try {
            val paths = ApplicationPaths(root.resolve("awm-home"))
            Files.createDirectories(paths.home)
            val original = """
                {"schemaVersion":"$CURRENT_APP_CONFIG_SCHEMA_VERSION","taskRoot":"D:/tasks","requirementMaterialsRoot":"D:/materials","requirementMaterialsSubdirectory":"研发","productionTagBuildEnabled":true}
            """.trimIndent()
            Files.writeString(paths.config, original)
            val configurations = AgentCompatibleConfigurationRepository(paths)
            val service = AgentOperationService(configurations = configurations)

            val inspection = service.inspect()

            assertTrue(inspection.canPlan)
            assertEquals("D:/tasks", inspection.taskRoot)
            assertEquals("D:/materials", inspection.requirementMaterialsRoot)
            assertEquals("研发", inspection.requirementMaterialsSubdirectory)
            assertFailsWith<UnsupportedOperationException> { configurations.save(AppConfig()) }
            assertEquals(original, Files.readString(paths.config))
        } finally {
            Files.walk(root).use { entries ->
                entries.sorted(java.util.Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    @Test
    fun `apply rechecks a fingerprint and passes the Agent-only context into task creation`() {
        val root = Files.createTempDirectory("agent-operation-")
        val taskRoot = Files.createDirectories(root.resolve("tasks"))
        val materialsRoot = Files.createDirectories(root.resolve("materials"))
        val config = AppConfig(
            taskRoot = taskRoot.toString(),
            requirementMaterialsRoot = materialsRoot.toString(),
            requirementMaterialsSubdirectory = "研发",
        )
        val taskOperations = RecordingTaskOperations(taskRoot)
        val paths = ApplicationPaths(root.resolve("awm-home"))
        val fixedClock = Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC)
        val service = AgentOperationService(
            configurations = object : ConfigurationRepository {
                override fun load(): AppConfig = config
                override fun save(config: AppConfig) = Unit
            },
            documentation = RequirementDocumentationService(
                iterations = RequirementIterationProvider { _, _ ->
                    listOf(RequirementSprint("sprint-1", "OBT-20260817--20260828", "进行中"))
                },
            ),
            tasks = taskOperations,
            store = AgentOperationStore(paths),
            clock = fixedClock,
        )
        val plan = service.plan(
            AgentCreateTaskRequest(
                folderName = "OBT-7064764629-登录优化",
                featureBranch = "feature/OBT-7064764629",
                groupId = DEFAULT_GROUP_ID,
                serviceIds = listOf("orders"),
                requirementLink = "https://project.feishu.cn/obt/userstory/detail/7064764629",
                requirementTitle = "登录优化",
                handoffMarkdown = "- api_key: raw-secret-must-not-reach-audit",
            ),
        )

        val applied = service.apply(plan.operationId, plan.nonce)

        assertEquals(AgentOperationState.APPLIED, applied.state)
        assertEquals("2026-08-23T00:00:00Z", applied.confirmedAt)
        assertNotNull(taskOperations.createdRequest)
        assertEquals(".awm/HANDOFF.md", taskOperations.createdRequest!!.agentContext!!.handoffRelativePath)
        val auditHandoff = requireNotNull(plan.request.handoffMarkdown)
        assertTrue(auditHandoff.contains("[REDACTED]"))
        assertTrue(!auditHandoff.contains("raw-secret-must-not-reach-audit"))
        assertTrue(Files.isRegularFile(Path.of(plan.documentation.documentationDirectory).resolve(".awm-requirement.json")))
        assertEquals(applied, service.status(plan.operationId))
        assertEquals(applied, service.apply(plan.operationId, plan.nonce))
    }

    @Test
    fun `apply accepts a newly created unique materials directory and lets the locked write reuse it`() {
        val root = Files.createTempDirectory("agent-operation-materials-race-")
        val taskRoot = Files.createDirectories(root.resolve("tasks"))
        val materialsRoot = Files.createDirectories(root.resolve("materials"))
        val config = AppConfig(
            taskRoot = taskRoot.toString(),
            requirementMaterialsRoot = materialsRoot.toString(),
            requirementMaterialsSubdirectory = "研发",
        )
        val taskOperations = RecordingTaskOperations(taskRoot)
        val service = AgentOperationService(
            configurations = object : ConfigurationRepository {
                override fun load(): AppConfig = config
                override fun save(config: AppConfig) = Unit
            },
            documentation = RequirementDocumentationService(
                iterations = RequirementIterationProvider { _, _ ->
                    listOf(RequirementSprint("sprint-1", "OBT-20260817--20260828", "进行中"))
                },
            ),
            tasks = taskOperations,
            store = AgentOperationStore(ApplicationPaths(root.resolve("awm-home"))),
        )
        val request = AgentCreateTaskRequest(
            folderName = "OBT-7064764629-登录优化",
            featureBranch = "feature/OBT-7064764629",
            groupId = DEFAULT_GROUP_ID,
            serviceIds = listOf("orders"),
            requirementLink = "https://project.feishu.cn/obt/userstory/detail/7064764629",
            requirementTitle = "登录优化",
        )

        val plan = service.plan(request)
        val competingWriteRoot = Files.createDirectories(
            materialsRoot.resolve("OBT-20260817--20260828").resolve("7064764629-已有资料").resolve("研发"),
        )

        val applied = service.apply(plan.operationId, plan.nonce)

        assertEquals(AgentOperationState.APPLIED, applied.state)
        assertEquals(competingWriteRoot.toString(), taskOperations.createdRequest!!.agentContext!!.documentationDirectory)
        assertTrue(!Files.exists(Path.of(plan.documentation.documentationDirectory).parent))
    }

    private class RecordingTaskOperations(private val taskRoot: Path) : AgentTaskOperations {
        var createdRequest: CreateGroupedTaskRequest? = null

        override fun inspectCreateBranchReuse(config: AppConfig, request: CreateGroupedTaskRequest): List<BranchReuseConflict> = emptyList()

        override fun create(config: AppConfig, request: CreateGroupedTaskRequest): TaskManifest {
            createdRequest = request
            return TaskManifest(
                folderName = request.folderName,
                taskDirectoryName = request.folderName,
                featureBranch = request.featureBranch,
                requirementLink = request.requirementLink,
                createdAt = "2026-08-23 08:00:00",
                updatedAt = "2026-08-23 08:00:00",
                services = emptyList(),
                agentContext = request.agentContext,
            )
        }
    }
}
