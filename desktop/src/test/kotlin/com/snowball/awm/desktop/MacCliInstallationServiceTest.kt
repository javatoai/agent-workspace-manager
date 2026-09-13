package com.snowball.awm.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MacCliInstallationServiceTest {
    @Test
    fun `install makes awm available from a managed zprofile without touching user content`() {
        val root = Files.createTempDirectory("awm-mac-cli-install")
        try {
            val home = root.resolve("home")
            val profile = home.resolve(".zprofile")
            Files.createDirectories(home)
            Files.writeString(profile, "export EDITOR=vim\n")
            val service = service(home, profile, bundledSource(root.resolve("bundle"), "0.9.10"))

            val installed = service.install()
            val commandDirectory = home.resolve("Library/Application Support/AgentWorkspaceManager/bin")
            val versionDirectory = home.resolve("Library/Application Support/AgentWorkspaceManager/cli/0.9.10")

            assertTrue(installed.installed)
            assertEquals(commandDirectory.resolve("awm"), installed.commandPath)
            assertTrue(Files.isExecutable(commandDirectory.resolve("awm")))
            assertTrue(Files.isExecutable(versionDirectory.resolve("cli/bin/awm")))
            assertTrue(Files.isExecutable(versionDirectory.resolve("runtime/bin/java")))
            assertContains(Files.readString(commandDirectory.resolve("awm")), "AWM-CLI-MANAGED: v1")
            assertContains(Files.readString(commandDirectory.resolve("awm")), "../cli/0.9.10/cli/bin/awm")

            val profileContent = Files.readString(profile)
            assertContains(profileContent, "export EDITOR=vim")
            assertContains(profileContent, "# >>> AWM CLI >>>")
            assertContains(profileContent, commandDirectory.toAbsolutePath().normalize().toString())

            service.install()
            assertEquals(1, Regex("# >>> AWM CLI >>>").findAll(Files.readString(profile)).count())
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun `uninstall removes only the managed command payload and profile block`() {
        val root = Files.createTempDirectory("awm-mac-cli-uninstall")
        try {
            val home = root.resolve("home")
            val profile = home.resolve(".zprofile")
            Files.createDirectories(home)
            Files.writeString(profile, "export EDITOR=vim\n")
            val service = service(home, profile, bundledSource(root.resolve("bundle"), "0.9.10"))
            service.install()

            val commandDirectory = home.resolve("Library/Application Support/AgentWorkspaceManager/bin")
            Files.writeString(commandDirectory.resolve("keep.txt"), "not managed by AWM CLI")
            val status = service.uninstall()

            assertFalse(status.installed)
            assertFalse(Files.exists(home.resolve("Library/Application Support/AgentWorkspaceManager/cli")))
            assertFalse(Files.exists(commandDirectory.resolve("awm")))
            assertFalse(Files.exists(commandDirectory.resolve("awm.version")))
            assertTrue(Files.isRegularFile(commandDirectory.resolve("keep.txt")))
            assertEquals("export EDITOR=vim\n", Files.readString(profile))
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun `custom awm command is never overwritten`() {
        val root = Files.createTempDirectory("awm-mac-cli-custom-command")
        try {
            val home = root.resolve("home")
            val profile = home.resolve(".zprofile")
            val command = home.resolve("Library/Application Support/AgentWorkspaceManager/bin/awm")
            Files.createDirectories(command.parent)
            Files.writeString(command, "#!/usr/bin/env sh\necho custom\n")
            val service = service(home, profile, bundledSource(root.resolve("bundle"), "0.9.10"))

            assertFailsWith<IllegalArgumentException> { service.install() }
            assertContains(Files.readString(command), "echo custom")
            assertFalse(Files.exists(home.resolve("Library/Application Support/AgentWorkspaceManager/cli")))
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun `non mac host does not write installation files`() {
        val root = Files.createTempDirectory("awm-mac-cli-unsupported")
        try {
            val home = root.resolve("home")
            val service = MacCliInstallationService(
                source = { bundledSource(root.resolve("bundle"), "0.9.10") },
                userHome = { home },
                profilePath = { home.resolve(".zprofile") },
                isMacOs = { false },
            )

            assertFalse(service.inspect().supported)
            assertFailsWith<IllegalArgumentException> { service.install() }
            assertFailsWith<IllegalArgumentException> { service.uninstall() }
            assertFalse(Files.exists(home))
        } finally {
            deleteTree(root)
        }
    }

    private fun service(home: Path, profile: Path, bundled: PortableCliSource): MacCliInstallationService =
        MacCliInstallationService(
            source = { bundled },
            userHome = { home },
            shellName = { "/bin/zsh" },
            profilePath = { profile },
            isMacOs = { true },
        )

    private fun bundledSource(root: Path, version: String): PortableCliSource {
        val cliHome = root.resolve("cli")
        val runtimeHome = root.resolve("runtime")
        Files.createDirectories(cliHome.resolve("bin"))
        Files.createDirectories(cliHome.resolve("lib"))
        Files.createDirectories(runtimeHome.resolve("bin"))
        Files.writeString(cliHome.resolve("bin/awm"), "#!/usr/bin/env sh\nexit 0\n")
        Files.writeString(cliHome.resolve("lib/awm.jar"), "jar")
        Files.writeString(runtimeHome.resolve("bin/java"), "runtime")
        return PortableCliSource(cliHome, runtimeHome, version)
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
