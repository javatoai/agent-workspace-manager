package com.snowball.taskwt.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TaskNamingTest {
    @Test
    fun `keeps readable unicode and replaces unsafe characters`() {
        assertEquals("需求-123-支付改造", TaskNaming.directoryName("需求/123 支付改造"))
    }

    @Test
    fun `protects windows reserved file names`() {
        assertEquals("_CON", TaskNaming.directoryName("CON"))
    }

    @Test
    fun `long names are stable and bounded`() {
        val key = "REQ-" + "很长的需求".repeat(30)
        val first = TaskNaming.directoryName(key)
        assertEquals(first, TaskNaming.directoryName(key))
        assertTrue(first.length <= 80)
    }
}
