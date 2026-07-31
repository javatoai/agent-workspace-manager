package com.snowball.taskwt.core

import java.security.MessageDigest
import java.text.Normalizer

object TaskNaming {
    private const val MAX_DIRECTORY_NAME_LENGTH = 80

    fun directoryName(folderName: String): String {
        require(folderName.isNotBlank()) { "文件夹名不能为空" }
        val normalized = Normalizer.normalize(folderName.trim(), Normalizer.Form.NFKC)
        val safe = normalized
            .replace(Regex("""[<>:"/\\|?*\p{Cc}]"""), "-")
            .replace(Regex("""\s+"""), "-")
            .replace(Regex("""-+"""), "-")
            .trim(' ', '.', '-')
            .ifBlank { "task" }
        val reservedSafe = if (isWindowsReservedName(safe)) "_$safe" else safe
        if (reservedSafe.length <= MAX_DIRECTORY_NAME_LENGTH) return reservedSafe
        val suffix = shortHash(folderName)
        return "${reservedSafe.take(MAX_DIRECTORY_NAME_LENGTH - suffix.length - 1)}-$suffix"
    }

    private fun isWindowsReservedName(value: String): Boolean {
        val stem = value.substringBefore('.').uppercase()
        return stem in setOf(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
        )
    }

    private fun shortHash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .take(4)
            .joinToString("") { "%02x".format(it) }
}
