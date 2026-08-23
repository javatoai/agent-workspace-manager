package com.snowball.awm.cli

import com.snowball.awm.core.AgentCreateTaskRequest
import com.snowball.awm.core.AgentOperationService
import com.snowball.awm.core.HandoffDocumentWriter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = false }

@Serializable
private data class Failure(val ok: Boolean = false, val error: String)

/**
 * Intentionally small, machine-readable CLI surface. All mutations go through
 * AgentOperationService; this executable never writes task files on its own.
 */
fun main(arguments: Array<String>) {
    // Windows' default console code page can otherwise corrupt JSON string values.
    System.setOut(PrintStream(System.out, true, StandardCharsets.UTF_8))
    System.setErr(PrintStream(System.err, true, StandardCharsets.UTF_8))
    val args = arguments.toList()
    try {
        val result = execute(args)
        println("{\"ok\":true,\"result\":$result}")
    } catch (error: Throwable) {
        System.err.println(json.encodeToString(Failure(error = error.message ?: error::class.simpleName ?: "执行失败")))
        exitProcess(1)
    }
}

private fun execute(args: List<String>): String {
    if (args.isEmpty() || args.first() in setOf("--help", "-h", "help")) return json.encodeToString(help())
    require(args.first() == "agent") { "仅支持 `awm agent ...`；运行 `awm --help` 查看受支持命令" }
    val service = AgentOperationService()
    return when (args.getOrNull(1)) {
        "inspect" -> json.encodeToString(service.inspect())
        "plan" -> json.encodeToString(service.plan(readRequest(args.valueAfter("--request"))))
        "apply" -> json.encodeToString(service.apply(args.valueAfter("--operation"), args.valueAfter("--nonce")))
        "status" -> json.encodeToString(service.status(args.valueAfter("--operation")))
        "handoff-template" -> json.encodeToString(HandoffTemplate(HandoffDocumentWriter.template()))
        else -> throw IllegalArgumentException("不支持的 agent 子命令；支持 inspect、plan、apply、status、handoff-template")
    }
}

@Serializable
private data class HandoffTemplate(val markdown: String)

@Serializable
private data class Help(val usage: List<String>)

private fun help(): Help = Help(
    listOf(
        "awm agent inspect --json",
        "awm agent handoff-template --json",
        "awm agent plan --request <request.json> --json",
        "awm agent apply --operation <operation-id> --nonce <nonce> --json",
        "awm agent status --operation <operation-id> --json",
    ),
)

private fun readRequest(value: String): AgentCreateTaskRequest {
    val path = Path.of(value).toAbsolutePath().normalize()
    require(Files.isRegularFile(path)) { "请求 JSON 文件不存在：$path" }
    return json.decodeFromString(Files.readString(path))
}

private fun List<String>.valueAfter(flag: String): String {
    val index = indexOf(flag)
    require(index >= 0 && index + 1 < size) { "缺少参数：$flag" }
    return this[index + 1]
}
