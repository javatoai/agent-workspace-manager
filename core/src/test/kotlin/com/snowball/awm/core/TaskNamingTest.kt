package com.snowball.awm.core

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
    fun `rejects blank folder name`() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            TaskNaming.directoryName("  ")
        }
    }
}
