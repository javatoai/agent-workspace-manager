package com.snowball.awm.core

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

interface CommandRunner {
    fun run(
        command: List<String>,
        workingDirectory: Path? = null,
        timeout: Duration = Duration.ofMinutes(10),
        environment: Map<String, String> = emptyMap(),
    ): CommandResult
}

class ProcessCommandRunner : CommandRunner {
    override fun run(
        command: List<String>,
        workingDirectory: Path?,
        timeout: Duration,
        environment: Map<String, String>,
    ): CommandResult {
        require(command.isNotEmpty()) { "命令不能为空" }
        require(!timeout.isNegative && !timeout.isZero) { "timeout must be greater than zero" }
        val process = ProcessBuilder(command)
            .directory(workingDirectory?.toFile())
            .apply {
                environment().putAll(environment)
                redirectInput(ProcessBuilder.Redirect.PIPE)
            }
            .start()
        process.outputStream.close()

        val executor = Executors.newFixedThreadPool(2)
        return try {
            val stdoutFuture = executor.submit<String> {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            }
            val stderrFuture = executor.submit<String> {
                process.errorStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            }
            val finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
            if (!finished) {
                destroyProcessTree(process)
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                process.inputStream.close()
                process.errorStream.close()
                CommandResult(
                    exitCode = TIMEOUT_EXIT_CODE,
                    stdout = readAfterTermination(stdoutFuture),
                    stderr = "命令执行超时（${timeout.seconds} 秒）\n" +
                        readAfterTermination(stderrFuture),
                )
            } else {
                CommandResult(
                    exitCode = process.exitValue(),
                    stdout = stdoutFuture.get(),
                    stderr = stderrFuture.get(),
                )
            }
        } catch (error: InterruptedException) {
            destroyProcessTree(process)
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
            throw error
        } finally {
            executor.shutdownNow()
        }
    }

    private fun destroyProcessTree(process: Process) {
        process.toHandle().descendants().toList().asReversed().forEach { descendant ->
            descendant.destroy()
        }
        process.destroy()
    }

    private fun readAfterTermination(future: Future<String>): String =
        try {
            future.get(2, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            ""
        } catch (_: Exception) {
            ""
        }

    companion object {
        const val TIMEOUT_EXIT_CODE = 124
    }
}
