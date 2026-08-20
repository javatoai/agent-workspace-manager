package com.snowball.awm.core

import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith

class AgentTaskTemplateStoreTest {
    @TempDir
    lateinit var temporary: Path

    private fun store(): AgentTaskTemplateStore =
        AgentTaskTemplateStore(ApplicationPaths(temporary.resolve("home")))

    private fun template(id: String, name: String, content: String = "$name 内容") =
        AgentTaskTemplate(id, name, content, "2026-08-19 16:00:00")

    @Test
    fun `missing template file returns an empty list`() {
        assertEquals(emptyList<AgentTaskTemplate>(), store().list())
    }

    @Test
    fun `saved templates roundtrip sorted by name`() {
        val store = store()
        store.saveAll(listOf(template("b", "beta"), template("a", "alpha")))

        val loaded = store.list()

        assertEquals(listOf("alpha", "beta"), loaded.map { it.name })
        assertEquals("alpha 内容", loaded.first().content)
        assertTrue(Files.exists(ApplicationPaths(temporary.resolve("home")).agentTaskTemplates))
    }

    @Test
    fun `saveAll replaces the whole library atomically`() {
        val store = store()
        store.saveAll(listOf(template("a", "旧模板")))
        store.saveAll(listOf(template("b", "新模板")))

        assertEquals(listOf("新模板"), store().list().map { it.name })
    }

    @Test
    fun `duplicate template names are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            store().saveAll(listOf(template("a", "重复"), template("b", "重复")))
        }
    }

    @Test
    fun `reserved AWM markers are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            store().saveAll(listOf(template("a", "恶意", "包含 ${AgentDocumentService.TASK_NOTES_BEGIN} 标记")))
        }
    }

    @Test
    fun `blank template name or content is rejected by the model`() {
        assertFailsWith<IllegalArgumentException> { template("a", " ") }
        assertFailsWith<IllegalArgumentException> { template("a", "名称", " ") }
    }

    @Test
    fun `corrupted template file surfaces a serialization error`() {
        val paths = ApplicationPaths(temporary.resolve("home"))
        Files.createDirectories(paths.agents)
        Files.writeString(paths.agentTaskTemplates, "{ not json")

        assertFailsWith<SerializationException> { AgentTaskTemplateStore(paths).list() }
    }
}
