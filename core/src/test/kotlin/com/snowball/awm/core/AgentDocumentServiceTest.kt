package com.snowball.awm.core

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class AgentDocumentServiceTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `new global file is empty and existing content is never overwritten`() {
        val paths = ApplicationPaths(temporary.resolve("defaults-home"))
        val service = AgentDocumentService(paths)
        service.ensureGlobalFile()
        assertEquals("", Files.readString(paths.globalAgents))

        Files.writeString(paths.globalAgents, "用户自己的规则")
        service.ensureGlobalFile()
        assertEquals("用户自己的规则", Files.readString(paths.globalAgents))
    }

    @Test
    fun `task document combines three levels and preserves the direct edit area`() {
        val paths = ApplicationPaths(temporary.resolve("home"))
        val service = AgentDocumentService(paths)
        service.saveGlobal("全局约定")
        service.saveGroup("growth", "增长组约定")
        val taskDirectory = temporary.resolve("tasks").resolve("OBT-123")
        val manifest = manifest(groupId = "growth")

        service.writeTaskDocument(taskDirectory, manifest, emptyList(), "任务第一次说明")
        val first = Files.readString(taskDirectory.resolve("AGENTS.md"))
        assertTrue(first.indexOf("全局约定") < first.indexOf("增长组约定"))
        assertTrue(first.indexOf("增长组约定") < first.indexOf("任务第一次说明"))

        val directlyEdited = first.replace("任务第一次说明", "模型直接追加的任务说明")
        Files.writeString(taskDirectory.resolve("AGENTS.md"), directlyEdited)
        service.saveGlobal("更新后的全局约定")
        service.writeTaskDocument(taskDirectory, manifest, emptyList())

        val refreshed = Files.readString(taskDirectory.resolve("AGENTS.md"))
        assertTrue(refreshed.contains("更新后的全局约定"))
        assertTrue(refreshed.contains("模型直接追加的任务说明"))
    }

    @Test
    fun `malformed task markers stop regeneration without changing the file`() {
        val paths = ApplicationPaths(temporary.resolve("home"))
        val service = AgentDocumentService(paths)
        val taskDirectory = temporary.resolve("tasks").resolve("broken")
        Files.createDirectories(taskDirectory)
        val malformed = "人工文件，没有 AWM 标记"
        Files.writeString(taskDirectory.resolve("AGENTS.md"), malformed)

        assertFailsWith<AgentDocumentFormatException> {
            service.writeTaskDocument(taskDirectory, manifest(), emptyList())
        }
        assertEquals(malformed, Files.readString(taskDirectory.resolve("AGENTS.md")))
    }

    @Test
    fun `reserved markers are rejected in user maintained content`() {
        val service = AgentDocumentService(ApplicationPaths(temporary.resolve("home-reserved")))
        assertFailsWith<IllegalArgumentException> {
            service.saveGlobal("do not write ${AgentDocumentService.TASK_NOTES_BEGIN}")
        }
    }

    @Test
    fun `ensuring editable instruction files creates the real disk targets`() {
        val paths = ApplicationPaths(temporary.resolve("home-open"))
        val service = AgentDocumentService(paths)

        assertEquals(paths.globalAgents, service.ensureGlobalFile())
        assertEquals(paths.groupAgents("growth"), service.ensureGroupFile("growth"))
        assertTrue(Files.isRegularFile(paths.globalAgents))
        assertTrue(Files.isRegularFile(paths.groupAgents("growth")))
    }

    @Test
    fun `generated workspace table keeps the edit boundary without redundant task facts`() {
        val workspace = ServiceWorkspace(
            repositoryId = "repo",
            serviceName = "订单服务",
            repositoryPath = "C:/source/orders",
            worktreePath = "C:/tasks/table/orders",
            developmentTool = DevelopmentToolType.INTELLIJ_IDEA,
            branch = "feature/orders",
            health = WorkspaceHealth.READY,
            baseRef = "origin/master",
        )
        val rendered = AgentsMdWriter.render(
            temporary.resolve("tasks").resolve("table"),
            manifest().copy(
                services = listOf(workspace),
                requirementMaterials = RequirementMaterialsDirectory(
                    status = RequirementMaterialsStatus.READY,
                    writeRoot = "D:/requirements/Sprint/OBT-123/研发资料",
                ),
            ),
            emptyList(),
            "人工说明",
        )

        assertTrue("需求链接" in rendered)
        assertTrue("需求资料目录" in rendered)
        assertTrue("D:/requirements/Sprint/OBT-123/研发资料" in rendered)
        assertTrue("需求辅助 Markdown、SQL 和脚本" in rendered)
        assertTrue("origin/master" in rendered)
        assertTrue("STANDARD_WORKTREE" in rendered)
        assertTrue("C:/tasks/table/orders" in rendered)
        assertTrue("人工说明" in rendered)
        assertFalse("C:/source/orders" in rendered)
        assertFalse("## 基本信息" in rendered)
        assertFalse("feature/orders" in rendered)
        assertFalse("READY" in rendered)
    }

    private fun manifest(groupId: String = DEFAULT_GROUP_ID) = TaskManifest(
        folderName = "OBT-123",
        taskDirectoryName = "OBT-123",
        featureBranch = "feature/OBT-123",
        requirementLink = "https://project.feishu.cn/obt/userstory/detail/123",
        createdAt = Instant.EPOCH.toString(),
        updatedAt = Instant.EPOCH.toString(),
        lifecycleStatus = TaskLifecycleStatus.ACTIVE,
        services = emptyList(),
        groupId = groupId,
    )
}
