import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.util.Comparator

abstract class CreateJlinkRuntime : DefaultTask() {
    @get:InputFile
    abstract val jlinkExecutable: RegularFileProperty

    @get:Input
    abstract val modules: ListProperty<String>

    @get:OutputDirectory
    abstract val destination: DirectoryProperty

    @TaskAction
    fun create() {
        val output = destination.get().asFile.toPath()
        deleteTree(output)
        val process = ProcessBuilder(
            jlinkExecutable.get().asFile.absolutePath,
            "--add-modules", modules.get().joinToString(","),
            "--strip-debug",
            "--no-header-files",
            "--no-man-pages",
            "--compress=zip-6",
            "--output", output.toString(),
        ).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor() != 0) {
            throw GradleException("无法构建 AWM CLI 运行时：${log.ifBlank { "jlink 退出失败" }}")
        }
    }

    private fun deleteTree(path: java.nio.file.Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { entries ->
            entries.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}

val macPackageVersion = providers.gradleProperty("macPackageVersion").orElse("3.0.1")
val portableCliVersion = layout.buildDirectory.file("generated/portable-cli/VERSION")
val portableCliVersionText = version.toString()
val portableCliRuntime = layout.buildDirectory.dir("generated/portable-cli-runtime")
val jlinkFileName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "jlink.exe" else "jlink"
val portableCliJlink = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}.map { launcher ->
    launcher.metadata.installationPath.file("bin/$jlinkFileName")
}

val writePortableCliVersion = tasks.register<Copy>("writePortableCliVersion") {
    from(resources.text.fromString("$portableCliVersionText\n")) {
        rename(".*", "VERSION")
    }
    into(portableCliVersion.map { it.asFile.parentFile })
}

/** Creates a compact runtime with `java` retained specifically for the CLI. */
val preparePortableCliRuntime = tasks.register<CreateJlinkRuntime>("preparePortableCliRuntime") {
    jlinkExecutable.set(portableCliJlink)
    modules.set(listOf("java.base"))
    destination.set(portableCliRuntime)
}

/**
 * Adds a self-contained CLI payload to the desktop application's resources.
 *
 * The launchers deliberately differ from Gradle's normal `installDist`
 * launchers: in a green package they first use the jpackage runtime that ships
 * alongside the desktop application, so users do not need a separately
 * installed JDK.
 */
val preparePortableCli = tasks.register<Sync>("preparePortableCli") {
    dependsOn(":cli:installDist", writePortableCliVersion, preparePortableCliRuntime)
    from(project(":cli").layout.buildDirectory.dir("install/awm")) {
        include("lib/**")
        into("cli")
    }
    from(project(":cli").layout.projectDirectory.dir("src/main/portable")) {
        into("cli")
    }
    from(portableCliVersion) {
        into("cli")
    }
    from(portableCliRuntime) {
        into("cli-runtime")
    }
    into(layout.buildDirectory.dir("app-resources/common"))
}

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.components.resources)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("io.github.vinceglb:filekit-dialogs:0.14.1")
    implementation("net.java.dev.jna:jna:5.18.1")
    implementation("net.java.dev.jna:jna-platform:5.18.1")
    implementation("com.mikepenz:multiplatform-markdown-renderer:0.43.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.43.0")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.snowball.awm.desktop.generated.resources"
}

compose.desktop {
    application {
        mainClass = "com.snowball.awm.desktop.MainKt"

        nativeDistributions {
            appResourcesRootDir.set(layout.buildDirectory.dir("app-resources"))
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg)
            packageName = "Agent Workspace Manager"
            // macOS jpackage rejects versions whose first component is zero.
            // CI overrides this with a compatible internal app version while the
            // project and release version remain 0.12.0.
            packageVersion = macPackageVersion.get()
            description = "Task-level Agent development workspace orchestrator"
            vendor = "Snowball Technology"

            windows {
                iconFile.set(project.file("src/main/resources/app-icon.ico"))
                menuGroup = "Agent Workspace Manager"
                shortcut = true
                perUserInstall = true
                upgradeUuid = "60326f10-0981-4396-9cb6-a2d2ea1a62c4"
            }

            macOS {
                iconFile.set(project.file("src/main/resources/app-icon.icns"))
                bundleID = "com.snowball.awm"
                dockName = "Agent Workspace Manager"
            }
        }
    }
}

tasks.matching { it.name == "prepareAppResources" }.configureEach {
    dependsOn(preparePortableCli)
}
