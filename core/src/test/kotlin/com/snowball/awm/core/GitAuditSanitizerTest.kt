package com.snowball.awm.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitAuditSanitizerTest {
    @Test
    fun `remote display removes userinfo query and fragment`() {
        assertEquals(
            "https://git.example.test/team/repo.git",
            GitAuditSanitizer.remoteDisplay("https://oauth2:secret-token@git.example.test/team/repo.git?access_token=secret#fragment"),
        )
    }

    @Test
    fun `command summary redacts common credential shapes`() {
        val summary = GitAuditSanitizer.summary(CommandResult(
            exitCode = 1,
            stdout = "",
            stderr = "remote https://user:secret@git.example/repo token=abc Authorization: Bearer xyz",
        ))

        assertFalse(summary.contains("secret"))
        assertFalse(summary.contains("abc"))
        assertFalse(summary.contains("xyz"))
        assertTrue(summary.contains("token=<redacted>"))
    }
}
