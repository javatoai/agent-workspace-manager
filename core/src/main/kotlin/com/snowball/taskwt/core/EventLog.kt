package com.snowball.taskwt.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant
import kotlin.io.path.createDirectories

@Serializable
data class ApplicationEvent(
    val timestamp: String,
    val level: String,
    val event: String,
    val message: String,
    val metadata: Map<String, String> = emptyMap(),
)

fun interface EventSink {
    fun record(event: ApplicationEvent)
}

object NoOpEventSink : EventSink {
    override fun record(event: ApplicationEvent) = Unit
}

class JsonlEventSink(
    private val paths: ApplicationPaths = ApplicationPaths.systemDefault(),
    private val clock: Clock = Clock.systemUTC(),
) : EventSink {
    private val json = Json { encodeDefaults = true }
    private val monitor = Any()

    override fun record(event: ApplicationEvent) {
        // Logging must never change the outcome of a Git operation.
        runCatching {
            synchronized(monitor) {
                paths.logs.createDirectories()
                val date = TaskWtTime.localDate(Instant.now(clock))
                val bytes = (json.encodeToString(event) + System.lineSeparator())
                    .toByteArray(StandardCharsets.UTF_8)
                FileChannel.open(
                    paths.logs.resolve("application-$date.jsonl"),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND,
                ).use { channel ->
                    channel.lock().use {
                        val buffer = ByteBuffer.wrap(bytes)
                        while (buffer.hasRemaining()) channel.write(buffer)
                        channel.force(false)
                    }
                }
            }
        }
    }
}

fun EventSink.info(
    event: String,
    message: String,
    metadata: Map<String, String> = emptyMap(),
    clock: Clock = Clock.systemUTC(),
) {
    record(
        ApplicationEvent(
            timestamp = TaskWtTime.format(Instant.now(clock)),
            level = "INFO",
            event = event,
            message = message,
            metadata = metadata,
        ),
    )
}

fun EventSink.error(
    event: String,
    message: String,
    metadata: Map<String, String> = emptyMap(),
    clock: Clock = Clock.systemUTC(),
) {
    record(
        ApplicationEvent(
            timestamp = TaskWtTime.format(Instant.now(clock)),
            level = "ERROR",
            event = event,
            message = message,
            metadata = metadata,
        ),
    )
}
