package com.snowball.awm.core

import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs

class CommandRunnerInterruptionTest {
    @Test
    fun `interruption terminates the child and leaves compensation thread usable`() {
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            runCatching {
                ProcessCommandRunner().run(
                    command = listOf(
                        Path.of(System.getProperty("java.home"), "bin", executable("java")).toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        SleepingProcessMain::class.java.name,
                    ),
                    timeout = Duration.ofMinutes(1),
                )
            }.onFailure(failure::set)
        }

        worker.start()
        Thread.sleep(500)
        worker.interrupt()
        worker.join(5_000)

        assertFalse(worker.isAlive, "interrupted command worker should terminate promptly")
        assertIs<InterruptedException>(failure.get())
        assertFalse(worker.isInterrupted, "caller must be able to run compensation commands on the same thread")
    }

    private fun executable(name: String): String =
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "$name.exe" else name
}

object SleepingProcessMain {
    @JvmStatic
    fun main(args: Array<String>) {
        Thread.sleep(60_000)
    }
}
