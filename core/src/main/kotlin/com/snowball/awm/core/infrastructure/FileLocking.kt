package com.snowball.awm.core

import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import kotlin.io.path.createDirectories

internal object FileLocking {
    fun <T> withExclusiveLock(lockPath: Path, failureMessage: String, block: () -> T): T {
        lockPath.parent.createDirectories()
        FileChannel.open(
            lockPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } ?: throw IllegalStateException(failureMessage)
            lock.use { return block() }
        }
    }

    fun stablePathHash(path: Path, length: Int = 16): String =
        MessageDigest.getInstance("SHA-256")
            .digest(path.toAbsolutePath().normalize().toString().lowercase(Locale.ROOT).toByteArray(StandardCharsets.UTF_8))
            .take(length / 2)
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
}
