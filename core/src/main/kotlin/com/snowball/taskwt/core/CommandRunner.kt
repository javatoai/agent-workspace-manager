package com.snowball.taskwt.core

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
                process.destroy()
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
                CommandResult(
                    exitCode = TIMEOUT_EXIT_CODE,
                    stdout = stdoutFuture.get(2, TimeUnit.SECONDS),
                    stderr = "命令执行超时（${timeout.seconds} 秒）\n" +
                        stderrFuture.get(2, TimeUnit.SECONDS),
                )
            } else {
                CommandResult(
                    exitCode = process.exitValue(),
                    stdout = stdoutFuture.get(),
                    stderr = stderrFuture.get(),
                )
            }
        } finally {
            executor.shutdownNow()
        }
    }

    companion object {
        const val TIMEOUT_EXIT_CODE = 124
    }
}
