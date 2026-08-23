package com.snowball.awm.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentOperationServiceTest {
    @Test
    fun `apply rechecks a fingerprint and passes the Agent-only context into task creation`() {
        val root = Files.createTempDirectory("agent-operation-")
        val taskRoot = Files.createDirectories(root.resolve("tasks"))
        val documentationRoot = Files.createDirectories(root.resolve("docs"))
        val config = AppConfig(
            taskRoot = taskRoot.toString(),
            requirementDocumentationRoot = documentationRoot.toString(),
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
