package com.snowball.awm.desktop

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Comparator

/** Status for the bundled, Tag-only AWM Skill installed into the local Skill host. */
data class TagSkillInstallationStatus(
    val bundledPayloadAvailable: Boolean,
    val installed: Boolean,
    val destinationOccupied: Boolean,
    val uninstallAvailable: Boolean,
    val destination: Path,
    val message: String,
)

/** Installs the Tag-only AWM Skill without depending on the CLI installation. */
internal interface TagSkillInstallationService {
    fun inspect(): TagSkillInstallationStatus
    fun install(): TagSkillInstallationStatus
    fun uninstall(): TagSkillInstallationStatus
}

/**
 * Copies the packaged Skill to the current user's `.agent/skills` directory.
 *
 * The destination is deliberately replaced as a complete directory: updates
 * first remove the exact target and then copy the packaged Skill directly, so
 * stale references cannot survive an update. A failed copy removes only its
 * new partial target; it never restores an older directory.
 */
internal class PackagedTagSkillInstallationService(
    private val source: () -> Path? = ::packagedTagSkillSource,
    private val userHome: () -> Path = { Path.of(System.getProperty("user.home")) },
) : TagSkillInstallationService {
    override fun inspect(): TagSkillInstallationStatus {
        val destination = destination()
        val bundled = bundledSource()
        val occupied = Files.exists(destination, NOFOLLOW_LINKS)
        val installed = Files.isRegularFile(destination.resolve(SKILL_FILE))
        val message = when {
            bundled == null -> "当前应用未提供内置 AWM Tag Skill。"
            installed -> "已检测到 AWM Tag Skill；可点击“更新 Tag Skill”覆盖为当前应用内置版本。"
            occupied -> "目标目录已存在，但不是完整的 AWM Tag Skill；安装时会覆盖该目录。"
            else -> "应用内含 AWM Tag Skill；安装后可从 ${destination.parent} 发现。"
        }
        return TagSkillInstallationStatus(
            bundledPayloadAvailable = bundled != null,
            installed = installed,
            destinationOccupied = occupied,
            uninstallAvailable = occupied,
            destination = destination,
            message = message,
        )
    }

    override fun install(): TagSkillInstallationStatus {
        val payload = requireNotNull(bundledSource()) { "未找到完整的应用内 AWM Tag Skill。" }
        validateSkill(payload)

        val destination = destination()
        Files.createDirectories(checkNotNull(destination.parent))
        if (Files.exists(destination, NOFOLLOW_LINKS)) deleteTree(destination)
        try {
            copyDirectory(payload, destination)
            validateSkill(destination)
        } catch (error: Throwable) {
            runCatching { deleteTree(destination) }
            throw error
        }
        return inspect()
    }

    override fun uninstall(): TagSkillInstallationStatus {
        val destination = destination()
        if (!Files.exists(destination, NOFOLLOW_LINKS)) return inspect()
        deleteTree(destination)
        return inspect()
    }

    private fun bundledSource(): Path? = source()?.takeIf(::isValidSkill)

    private fun isValidSkill(root: Path): Boolean = runCatching {
        Files.isDirectory(root) && Files.isRegularFile(root.resolve(SKILL_FILE)) &&
            SKILL_NAME_PATTERN.containsMatchIn(Files.readString(root.resolve(SKILL_FILE)))
    }.getOrDefault(false)

    private fun validateSkill(root: Path) {
        require(isValidSkill(root)) { "AWM Tag Skill 文件不完整：$root" }
    }

    private fun destination(): Path = userHome().resolve(".agent").resolve("skills").resolve(SKILL_NAME)

    private fun copyDirectory(source: Path, target: Path) {
        Files.walk(source).use { paths ->
            paths.forEach { entry ->
                val destination = target.resolve(source.relativize(entry).toString())
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(destination)
                } else {
                    Files.createDirectories(checkNotNull(destination.parent))
                    Files.copy(entry, destination, REPLACE_EXISTING)
                }
            }
        }
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, NOFOLLOW_LINKS)) {
            Files.deleteIfExists(path)
            return
        }
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private companion object {
        private const val SKILL_NAME = "awm"
        private const val SKILL_FILE = "SKILL.md"
        private val SKILL_NAME_PATTERN = Regex("(?m)^name:\\s*awm\\s*$")

        private fun packagedTagSkillSource(): Path? {
            val resources = System.getProperty("compose.application.resources.dir") ?: return null
            return Path.of(resources).resolve("skills").resolve(SKILL_NAME)
        }
    }
}
