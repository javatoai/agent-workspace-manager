package com.snowball.awm.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class RequirementDocumentationServiceTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `creates deterministic iteration and requirement documents from the unique active Sprint`() {
        val root = Files.createDirectories(temporary.resolve("docs"))
        val service = RequirementDocumentationService(iterations = FixedIterations(singleActive))
        val config = AppConfig(requirementDocumentationRoot = root.toString())

        val plan = service.plan(config, link, "登录优化")
        val materialized = service.materialize(config, plan)

        assertEquals("OBT-20260817--20260828", plan.sprint.label)
        assertEquals(false, plan.reusedHistoricalDirectory)
        assertTrue(Files.isRegularFile(Path.of(plan.documentationDirectory).resolve(".awm-requirement.json")))
        assertTrue(Files.isRegularFile(root.resolve(".awm-requirement-index.jsonl")))
        assertTrue(Files.readString(Path.of(plan.iterationDirectory).resolve("00-迭代任务总览.md")).contains("7064764629-登录优化"))
        assertEquals(plan.documentationDirectory, materialized.agentContext.documentationDirectory)
    }

    @Test
    fun `reuses exactly one historical manifest without querying Meegle again`() {
        val root = Files.createDirectories(temporary.resolve("docs-history"))
        val config = AppConfig(requirementDocumentationRoot = root.toString())
        val initial = RequirementDocumentationService(iterations = FixedIterations(singleActive))
        val created = initial.plan(config, link, "登录优化")
        initial.materialize(config, created)
        val offline = RequirementDocumentationService(iterations = RequirementIterationProvider { _, _ ->
            error("historical reuse must not call Meegle")
        })

        val reused = offline.plan(config, link, "不同的标题不会改变历史目录")

        assertEquals(true, reused.reusedHistoricalDirectory)
        assertEquals(created.documentationDirectory, reused.documentationDirectory)
        assertEquals("登录优化", reused.requirementTitle)
    }

    @Test
    fun `blocks an ambiguous active Sprint selection`() {
        val root = Files.createDirectories(temporary.resolve("docs-ambiguous"))
        val service = RequirementDocumentationService(
            iterations = FixedIterations(
                listOf(
                    singleActive.single(),
                    RequirementSprint("2", "OBT-20260901--20260914", "进行中"),
                ),
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            service.plan(AppConfig(requirementDocumentationRoot = root.toString()), link, "登录优化")
        }
        assertTrue(error.message.orEmpty().contains("多个进行中 Sprint"))
    }

    @Test
    fun `does not let an index hide a second historical manifest`() {
        val root = Files.createDirectories(temporary.resolve("docs-duplicate"))
        val config = AppConfig(requirementDocumentationRoot = root.toString())
        val service = RequirementDocumentationService(iterations = FixedIterations(singleActive))
        val first = service.plan(config, link, "登录优化")
        service.materialize(config, first)
        val duplicate = Path.of(first.iterationDirectory).resolve("7064764629-重复目录")
        Files.createDirectory(duplicate)
        Files.copy(Path.of(first.documentationDirectory).resolve(".awm-requirement.json"), duplicate.resolve(".awm-requirement.json"))

        val error = assertFailsWith<IllegalArgumentException> { service.plan(config, link, "登录优化") }

        assertTrue(error.message.orEmpty().contains("多个历史目录"))
    }

    private class FixedIterations(private val values: List<RequirementSprint>) : RequirementIterationProvider {
        override fun resolve(requirementLink: String, projectKey: String): List<RequirementSprint> = values
    }

    private companion object {
        const val link = "https://project.feishu.cn/obt/userstory/detail/7064764629"
        val singleActive = listOf(RequirementSprint("7070412889", "OBT-20260817--20260828", "进行中"))
    }
}
