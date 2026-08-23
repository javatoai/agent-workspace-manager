package com.snowball.awm.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.WinUser
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.Comparator

/** One portable CLI payload bundled with the desktop green package. */
internal data class PortableCliSource(
    val cliHome: Path,
    val runtimeHome: Path,
    val version: String,
)

data class CliInstallationStatus(
    val supported: Boolean,
    val bundledPayloadAvailable: Boolean,
    val installed: Boolean,
    val commandPath: Path? = null,
    val version: String? = null,
    val message: String,
)

internal interface CliInstallationService {
    fun inspect(): CliInstallationStatus
    fun install(): CliInstallationStatus
    fun uninstall(): CliInstallationStatus
}

/**
 * Installs the CLI from a Windows green package into the current user's local
 * application-data directory. Its dedicated packaged jlink runtime is copied
 * with the CLI so `awm` never depends on a machine-wide JDK.
 */
internal class WindowsCliInstallationService(
    private val source: () -> PortableCliSource? = ::packagedSource,
    private val localApplicationData: () -> Path = ::defaultLocalApplicationData,
    private val userPath: UserPathStore = WindowsRegistryUserPathStore(),
    private val isWindows: () -> Boolean = { System.getProperty("os.name").startsWith("Windows", ignoreCase = true) },
    private val environmentChanged: () -> Unit = ::broadcastEnvironmentChange,
) : CliInstallationService {
    override fun inspect(): CliInstallationStatus {
        if (!isWindows()) {
            return CliInstallationStatus(
                supported = false,
                bundledPayloadAvailable = false,
                installed = false,
                message = "当前仅提供 Windows 绿色包的一键 CLI 安装；macOS/Linux 请使用包内 bin/awm。",
            )
        }
        val command = commandDirectory().resolve(COMMAND_FILE)
        val installedVersion = readInstalledVersion()
        val installed = Files.isRegularFile(command) && installedVersion != null &&
            validInstallation(cliVersionsDirectory().resolve(installedVersion))
        val bundled = source()
        return when {
            installed && bundled != null -> CliInstallationStatus(
                supported = true,
                bundledPayloadAvailable = true,
                installed = true,
                commandPath = command,
                version = installedVersion,
                message = "已安装 AWM CLI $installedVersion；新开的终端可直接运行 awm。",
            )
            installed -> CliInstallationStatus(
                supported = true,
                bundledPayloadAvailable = false,
                installed = true,
                commandPath = command,
                version = installedVersion,
                message = "已安装 AWM CLI $installedVersion；当前运行的不是可更新的绿色包。",
            )
            bundled != null -> CliInstallationStatus(
                supported = true,
                bundledPayloadAvailable = true,
                installed = false,
                commandPath = command,
                version = bundled.version,
                message = "绿色包内含 AWM CLI ${bundled.version}；安装后会写入当前用户 PATH。",
            )
            else -> CliInstallationStatus(
                supported = true,
                bundledPayloadAvailable = false,
                installed = false,
                commandPath = command,
                message = "当前应用未提供内置的 CLI 或运行时。",
            )
        }
    }

    override fun install(): CliInstallationStatus {
        require(isWindows()) { "一键 CLI 安装仅支持 Windows 绿色包" }
        val bundled = requireNotNull(source()) { "未找到绿色包内置的 CLI 或运行时；开发模式不会复制本机 JDK。" }
        validateSource(bundled)

        val versionDirectory = cliVersionsDirectory().resolve(bundled.version)
        if (Files.exists(versionDirectory)) {
            require(validInstallation(versionDirectory)) { "CLI 目标版本目录不完整：$versionDirectory；请先手工备份并删除该目录后重试" }
        } else {
            Files.createDirectories(cliVersionsDirectory())
            val staging = Files.createTempDirectory(cliVersionsDirectory(), ".${bundled.version}-")
            try {
                copyDirectory(bundled.cliHome, staging.resolve("cli"))
                copyDirectory(bundled.runtimeHome, staging.resolve("runtime"))
                require(validInstallation(staging)) { "打包的 CLI 文件不完整" }
                moveDirectory(staging, versionDirectory)
            } catch (error: Throwable) {
                deleteTree(staging)
                throw error
            }
        }

        Files.createDirectories(commandDirectory())
        writeStableCommand(bundled.version)
        addCommandDirectoryToUserPath()
        environmentChanged()
        return inspect()
    }

    override fun uninstall(): CliInstallationStatus {
        require(isWindows()) { "一键 CLI 卸载仅支持 Windows" }

        removeCommandDirectoryFromUserPath()
        environmentChanged()
        Files.deleteIfExists(commandDirectory().resolve(COMMAND_FILE))
        Files.deleteIfExists(commandDirectory().resolve(VERSION_FILE))
        deleteTree(cliVersionsDirectory())
        return inspect()
    }

    private fun validateSource(source: PortableCliSource) {
        require(source.version.matches(VERSION_PATTERN)) { "CLI 版本号不合法：${source.version}" }
        require(Files.isRegularFile(source.cliHome.resolve("bin").resolve(PACKAGED_COMMAND_FILE))) {
            "绿色包缺少 CLI 启动脚本"
        }
        require(hasCliLibraries(source.cliHome)) { "绿色包缺少 CLI 依赖库" }
        require(Files.isRegularFile(source.runtimeHome.resolve("bin").resolve("java.exe"))) {
            "绿色包缺少随附 Java 运行时"
        }
    }

    private fun validInstallation(root: Path): Boolean =
        Files.isRegularFile(root.resolve("cli/bin").resolve(PACKAGED_COMMAND_FILE)) &&
            hasCliLibraries(root.resolve("cli")) &&
            Files.isRegularFile(root.resolve("runtime/bin/java.exe"))

    private fun hasCliLibraries(cliHome: Path): Boolean {
        val libraries = cliHome.resolve("lib")
        if (!Files.isDirectory(libraries)) return false
        return Files.list(libraries).use { entries -> entries.anyMatch(Files::isRegularFile) }
    }

    private fun writeStableCommand(version: String) {
        val command = commandDirectory().resolve(COMMAND_FILE)
        val content = """
            @echo off
            setlocal
            call "%~dp0..\cli\$version\cli\bin\$PACKAGED_COMMAND_FILE" %*
            set "AWM_EXIT_CODE=%ERRORLEVEL%"
            endlocal & exit /b %AWM_EXIT_CODE%
        """.trimIndent() + "\r\n"
        writeAtomically(command, content)
        writeAtomically(commandDirectory().resolve(VERSION_FILE), "$version\r\n")
    }

    private fun addCommandDirectoryToUserPath() {
        val directory = commandDirectory().toAbsolutePath().normalize().toString()
        val current = userPath.read()
        val entries = current.split(';').map(String::trim).filter(String::isNotEmpty)
        if (entries.any { it.equals(directory, ignoreCase = true) }) return
        userPath.write((entries + directory).joinToString(";"))
    }

    private fun removeCommandDirectoryFromUserPath() {
        val directory = commandDirectory().toAbsolutePath().normalize().toString()
        val entries = userPath.read().split(';').map(String::trim).filter(String::isNotEmpty)
        val retained = entries.filterNot { it.equals(directory, ignoreCase = true) }
        if (retained.size != entries.size) userPath.write(retained.joinToString(";"))
    }

    private fun readInstalledVersion(): String? = runCatching {
        Files.readString(commandDirectory().resolve(VERSION_FILE), StandardCharsets.UTF_8).trim()
            .takeIf { it.matches(VERSION_PATTERN) }
    }.getOrNull()

    private fun applicationHome(): Path = localApplicationData().resolve("AgentWorkspaceManager")
    private fun cliVersionsDirectory(): Path = applicationHome().resolve("cli")
    private fun commandDirectory(): Path = applicationHome().resolve("bin")

    private fun moveDirectory(source: Path, target: Path) {
        try {
            Files.move(source, target, ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
    }

    private fun writeAtomically(target: Path, content: String) {
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".${target.fileName}-", ".tmp")
        Files.writeString(temporary, content, StandardCharsets.UTF_8)
        try {
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, target, REPLACE_EXISTING)
        }
    }

    private fun copyDirectory(source: Path, target: Path) {
        Files.walk(source).use { paths ->
            paths.forEach { entry ->
                val destination = target.resolve(source.relativize(entry).toString())
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(destination)
                } else {
                    Files.createDirectories(destination.parent)
                    Files.copy(entry, destination, REPLACE_EXISTING)
                }
            }
        }
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    companion object {
        private const val COMMAND_FILE = "awm.cmd"
        private const val PACKAGED_COMMAND_FILE = "awm.cmd"
        private const val VERSION_FILE = "awm.version"
        private val VERSION_PATTERN = Regex("[0-9A-Za-z][0-9A-Za-z.+-]{0,127}")

        private fun packagedSource(): PortableCliSource? {
            val resources = System.getProperty("compose.application.resources.dir") ?: return null
            val cliHome = Path.of(resources).resolve("cli")
            val runtimeHome = Path.of(resources).resolve("cli-runtime")
            val version = runCatching { Files.readString(cliHome.resolve("VERSION"), StandardCharsets.UTF_8).trim() }.getOrNull()
                ?: return null
            return PortableCliSource(cliHome, runtimeHome, version)
        }

        private fun defaultLocalApplicationData(): Path =
            System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"), "AppData", "Local")

        /**
         * Notifies Explorer and other Windows applications that the user PATH
         * changed. Existing shells still keep their inherited environments and
         * must be reopened by their host application.
         */
        private fun broadcastEnvironmentChange() {
            val message = Memory((ENVIRONMENT_VALUE.length + 1L) * Native.WCHAR_SIZE)
            message.setWideString(0, ENVIRONMENT_VALUE)
            runCatching {
                User32.INSTANCE.SendMessageTimeout(
                    WinUser.HWND_BROADCAST,
                    WM_SETTING_CHANGE,
                    WinDef.WPARAM(0),
                    WinDef.LPARAM(Pointer.nativeValue(message)),
                    WinUser.SMTO_ABORTIFHUNG,
                    ENVIRONMENT_CHANGE_TIMEOUT_MILLIS,
                    WinDef.DWORDByReference(),
                )
            }
        }

        private const val WM_SETTING_CHANGE = 0x001A
        private const val ENVIRONMENT_CHANGE_TIMEOUT_MILLIS = 5_000
        private const val ENVIRONMENT_VALUE = "Environment"
    }
}

internal interface UserPathStore {
    fun read(): String
    fun write(value: String)
}

private class WindowsRegistryUserPathStore : UserPathStore {
    override fun read(): String {
        if (!Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, ENVIRONMENT_KEY, PATH_VALUE)) return ""
        return runCatching {
            Advapi32Util.registryGetExpandableStringValue(WinReg.HKEY_CURRENT_USER, ENVIRONMENT_KEY, PATH_VALUE)
        }.getOrElse {
            Advapi32Util.registryGetStringValue(WinReg.HKEY_CURRENT_USER, ENVIRONMENT_KEY, PATH_VALUE)
        }
    }

    override fun write(value: String) {
        Advapi32Util.registrySetExpandableStringValue(WinReg.HKEY_CURRENT_USER, ENVIRONMENT_KEY, PATH_VALUE, value)
    }

    private companion object {
        const val ENVIRONMENT_KEY = "Environment"
        const val PATH_VALUE = "Path"
    }
}
