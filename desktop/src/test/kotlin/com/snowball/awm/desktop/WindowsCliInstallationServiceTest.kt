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

class WindowsCliInstallationServiceTest {
    @Test
    fun `install copies bundled cli and runtime then adds one user path entry`() {
        val root = Files.createTempDirectory("awm-cli-install")
        try {
            val localApplicationData = root.resolve("local-app-data")
            val bundled = bundledSource(root.resolve("bundle"), version = "0.9.10")
            val pathStore = InMemoryUserPathStore("C:\\Tools")
            var environmentNotifications = 0
            val service = WindowsCliInstallationService(
                source = { bundled },
                localApplicationData = { localApplicationData },
                userPath = pathStore,
                isWindows = { true },
                environmentChanged = { environmentNotifications += 1 },
            )

            val installed = service.install()

            val commandDirectory = localApplicationData.resolve("AgentWorkspaceManager/bin")
            val versionDirectory = localApplicationData.resolve("AgentWorkspaceManager/cli/0.9.10")
            assertTrue(installed.installed)
            assertEquals("0.9.10", installed.version)
            assertEquals(commandDirectory.resolve("awm.cmd"), installed.commandPath)
            assertTrue(Files.isRegularFile(versionDirectory.resolve("cli/bin/awm.cmd")))
            assertTrue(Files.isRegularFile(versionDirectory.resolve("cli/lib/awm.jar")))
            assertTrue(Files.isRegularFile(versionDirectory.resolve("runtime/bin/java.exe")))
            assertContains(Files.readString(commandDirectory.resolve("awm.cmd")), "..\\cli\\0.9.10\\cli\\bin\\awm.cmd")
            assertEquals("C:\\Tools;${commandDirectory.toAbsolutePath().normalize()}", pathStore.value)
            assertEquals(1, environmentNotifications)

            service.install()
            assertEquals("C:\\Tools;${commandDirectory.toAbsolutePath().normalize()}", pathStore.value)
            assertEquals(2, environmentNotifications)
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun `development mode without bundled payload does not offer installation`() {
        val root = Files.createTempDirectory("awm-cli-missing")
        try {
            val service = WindowsCliInstallationService(
                source = { null },
                localApplicationData = { root.resolve("local-app-data") },
                userPath = InMemoryUserPathStore(),
                isWindows = { true },
            )

            val status = service.inspect()

            assertTrue(status.supported)
            assertFalse(status.bundledPayloadAvailable)
            assertFalse(status.installed)
            assertFailsWith<IllegalArgumentException> { service.install() }
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun `empty bundled library directory is rejected before any installation write`() {
        val root = Files.createTempDirectory("awm-cli-empty-library")
        try {
            val bundled = bundledSource(root.resolve("bundle"), version = "0.9.10")
            Files.delete(bundled.cliHome.resolve("lib/awm.jar"))
            val localApplicationData = root.resolve("local-app-data")
            val service = WindowsCliInstallationService(
                source = { bundled },
                localApplicationData = { localApplicationData },
                userPath = InMemoryUserPathStore(),
                isWindows = { true },
            )

            assertFailsWith<IllegalArgumentException> { service.install() }
            assertFalse(Files.exists(localApplicationData))
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun `installing a newer bundled version switches the stable command without duplicating path`() {
        val root = Files.createTempDirectory("awm-cli-upgrade")
        try {
            val localApplicationData = root.resolve("local-app-data")
            val pathStore = InMemoryUserPathStore()
            fun serviceFor(version: String) = WindowsCliInstallationService(
                source = { bundledSource(root.resolve("bundle-$version"), version) },
                localApplicationData = { localApplicationData },
                userPath = pathStore,
                isWindows = { true },
            )

            serviceFor("0.9.10").install()
            val upgraded = serviceFor("0.9.11").install()

            val commandDirectory = localApplicationData.resolve("AgentWorkspaceManager/bin")
            assertTrue(upgraded.installed)
            assertEquals("0.9.11", upgraded.version)
            assertTrue(Files.isDirectory(localApplicationData.resolve("AgentWorkspaceManager/cli/0.9.10")))
            assertTrue(Files.isDirectory(localApplicationData.resolve("AgentWorkspaceManager/cli/0.9.11")))
            assertContains(Files.readString(commandDirectory.resolve("awm.cmd")), "..\\cli\\0.9.11\\cli\\bin\\awm.cmd")
            assertEquals(commandDirectory.toAbsolutePath().normalize().toString(), pathStore.value)
        } finally {
            deleteTree(root)
        }
    }

    @Test
    fun `non Windows host never writes installation files`() {
        val root = Files.createTempDirectory("awm-cli-non-windows")
        try {
            val service = WindowsCliInstallationService(
                source = { bundledSource(root.resolve("bundle"), version = "0.9.10") },
                localApplicationData = { root.resolve("local-app-data") },
                userPath = InMemoryUserPathStore(),
                isWindows = { false },
            )

            assertFalse(service.inspect().supported)
            assertFailsWith<IllegalArgumentException> { service.install() }
            assertFalse(Files.exists(root.resolve("local-app-data")))
        } finally {
            deleteTree(root)
        }
    }

    private fun bundledSource(root: Path, version: String): PortableCliSource {
        val cliHome = root.resolve("cli")
        val runtimeHome = root.resolve("runtime")
        Files.createDirectories(cliHome.resolve("bin"))
        Files.createDirectories(cliHome.resolve("lib"))
        Files.createDirectories(runtimeHome.resolve("bin"))
        Files.writeString(cliHome.resolve("bin/awm.cmd"), "@echo off\r\n")
        Files.writeString(cliHome.resolve("lib/awm.jar"), "jar")
        Files.writeString(runtimeHome.resolve("bin/java.exe"), "runtime")
        return PortableCliSource(cliHome, runtimeHome, version)
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private class InMemoryUserPathStore(initial: String = "") : UserPathStore {
        var value: String = initial

        override fun read(): String = value
        override fun write(value: String) {
            this.value = value
        }
    }
}
