package com.snowball.awm.core

import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Duration
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

    fun <T> withExclusiveLockWaiting(
        lockPath: Path,
        timeout: Duration,
        failureMessage: String,
        block: () -> T,
    ): T {
        require(!timeout.isNegative && !timeout.isZero) { "lock timeout must be greater than zero" }
        lockPath.parent.createDirectories()
        FileChannel.open(
            lockPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            val startedAt = System.nanoTime()
            val timeoutNanos = timeout.toNanos()
            while (true) {
                val lock = try {
                    channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                if (lock != null) lock.use { return block() }
                val elapsed = System.nanoTime() - startedAt
                if (elapsed >= timeoutNanos) throw IllegalStateException(failureMessage)
                val remainingNanos = timeoutNanos - elapsed
                val waitMillis = minOf(50L, maxOf(1L, remainingNanos / 1_000_000L))
                try {
                    Thread.sleep(waitMillis)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw interrupted
                }
            }
        }
    }

    fun stablePathHash(path: Path, length: Int = 16): String =
        stableTextHash(path.toAbsolutePath().normalize().toString().lowercase(Locale.ROOT), length)

    fun stableTextHash(value: String, length: Int = 16): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .take(length / 2)
            .joinToString("") { "%02x".format(Locale.ROOT, it) }
}
