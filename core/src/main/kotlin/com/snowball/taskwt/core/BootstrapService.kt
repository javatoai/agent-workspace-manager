package com.snowball.taskwt.core

import java.nio.file.FileVisitResult
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isSymbolicLink

data class BootstrapStepResult(
    val name: String,
    val succeeded: Boolean,
    val message: String,
)

data class BootstrapResult(
    val steps: List<BootstrapStepResult>,
    val warnings: List<String>,
) {
    val succeeded: Boolean get() = steps.all { it.succeeded }
}

class BootstrapService(
    private val runner: CommandRunner = ProcessCommandRunner(),
    private val git: GitClient = GitClient(runner),
) {
    fun initialize(
        sourceRepository: Path,
        worktree: Path,
        config: BootstrapConfig,
    ): BootstrapResult {
        val steps = mutableListOf<BootstrapStepResult>()
        val warnings = mutableListOf<String>()

        config.commands
            .filter { it.enabled && supportsCurrentPlatform(it.platforms) }
            .forEach { command ->
                require(command.executable.isNotBlank()) { "command executable must not be blank" }
                require(command.timeoutSeconds > 0) { "command timeoutSeconds must be greater than zero" }
            }

        config.copyRules.forEach { rule ->
            val stepName = "复制 ${rule.source} → ${rule.target}"
            runCatching {
                copyRule(sourceRepository, worktree, rule, warnings)
            }.onSuccess {
                steps += BootstrapStepResult(stepName, true, "复制完成")
            }.onFailure {
                steps += BootstrapStepResult(stepName, false, it.message ?: "复制失败")
                warnings += "$stepName：${it.message}"
            }
        }

        config.commands
            .filter { it.enabled && supportsCurrentPlatform(it.platforms) }
            .forEach { command ->
                val stepName = "执行 ${command.name}"
                runCatching {
                    val workingDirectory = resolveSafe(worktree, command.workingDirectory, "命令工作目录")
                    require(workingDirectory.exists() && workingDirectory.isDirectory()) {
                        "命令工作目录不存在：$workingDirectory"
                    }
                    runner.run(
                        command = listOf(command.executable) + command.arguments,
                        workingDirectory = workingDirectory,
                        timeout = Duration.ofSeconds(command.timeoutSeconds),
                    )
                }.onSuccess { result ->
                    if (result.succeeded) {
                        steps += BootstrapStepResult(stepName, true, result.stdout.trim().takeLast(2_000))
                    } else {
                        val detail = result.stderr.ifBlank { result.stdout }.trim().takeLast(2_000)
                        steps += BootstrapStepResult(stepName, false, detail)
                        warnings += "$stepName 失败（退出码 ${result.exitCode}）"
                    }
                }.onFailure {
                    steps += BootstrapStepResult(stepName, false, it.message ?: "执行失败")
                    warnings += "$stepName：${it.message}"
                }
            }

        return BootstrapResult(steps, warnings)
    }

    private fun copyRule(
        sourceRepository: Path,
        worktree: Path,
        rule: BootstrapCopyRule,
        warnings: MutableList<String>,
    ) {
        val source = resolveSafe(sourceRepository, rule.source, "复制源")
        val target = resolveSafe(worktree, rule.target, "复制目标")
        require(source.exists()) { "复制源不存在：$source" }
        require(source.fileName.toString() != ".git" && !source.startsWith(sourceRepository.resolve(".git"))) {
            "禁止复制 .git 内容"
        }
        require(!Files.isSymbolicLink(source)) { "copy source must not be a symbolic link: $source" }
        require(!Files.isSymbolicLink(target)) { "copy target must not be a symbolic link: $target" }
        if (target.exists() && !rule.overwrite) {
            throw IllegalStateException("目标已存在且规则禁止覆盖：$target")
        }
        if (target.exists() && isTracked(worktree, worktree.relativize(target))) {
            warnings += "初始化覆盖了 Git 已跟踪路径：${worktree.relativize(target)}"
        }

        if (source.isDirectory()) {
            copyDirectory(source, target, rule.overwrite)
        } else {
            copyFileAtomically(source, target, rule.overwrite)
        }
    }

    private fun copyDirectory(source: Path, target: Path, overwrite: Boolean) {
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(
                directory: Path,
                attributes: BasicFileAttributes,
            ): FileVisitResult {
                require(!directory.isSymbolicLink()) { "不支持复制符号链接目录：$directory" }
                target.resolve(source.relativize(directory)).createDirectories()
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                require(!file.isSymbolicLink()) { "不支持复制符号链接文件：$file" }
                copyFileAtomically(file, target.resolve(source.relativize(file)), overwrite)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun copyFileAtomically(source: Path, target: Path, overwrite: Boolean) {
        target.parent.createDirectories()
        if (target.exists() && !overwrite) {
            throw IllegalStateException("目标已存在且规则禁止覆盖：$target")
        }
        val temporary = Files.createTempFile(target.parent, ".${target.fileName}.taskwt-", ".tmp")
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun isTracked(worktree: Path, relative: Path): Boolean {
        if (relative.toString().isBlank()) return false
        return git.run(
            worktree,
            "ls-files",
            "--error-unmatch",
            "--",
            relative.toString(),
            check = false,
        ).succeeded
    }

    private fun resolveSafe(root: Path, relativeValue: String, label: String): Path {
        val relative = Path.of(relativeValue)
        require(!relative.isAbsolute) { "$label 必须是相对路径：$relativeValue" }
        require(relative.none { it.toString() == ".." }) { "$label 禁止包含 ..：$relativeValue" }
        val normalizedRoot = root.toAbsolutePath().normalize()
        val resolved = normalizedRoot.resolve(relative).normalize()
        require(resolved.startsWith(normalizedRoot)) { "$label 超出仓库范围：$relativeValue" }
        require(resolved.none { it.toString() == ".git" }) { "$label 禁止访问 .git：$relativeValue" }
        val relativeResolved = normalizedRoot.relativize(resolved)
        require(relativeResolved.none { it.toString().equals(".git", ignoreCase = true) }) {
            "$label must not access .git: $relativeValue"
        }
        var current = normalizedRoot
        relativeResolved.forEach { component ->
            current = current.resolve(component)
            require(!Files.isSymbolicLink(current)) {
                "$label must not traverse symbolic links: $relativeValue"
            }
        }
        return resolved
    }

    private fun supportsCurrentPlatform(platforms: Set<String>): Boolean {
        if (platforms.isEmpty()) return true
        val current = when {
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "windows"
            System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "macos"
            else -> "linux"
        }
        return platforms.any { it.equals(current, ignoreCase = true) }
    }
}
