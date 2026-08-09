import org.jetbrains.compose.desktop.application.dsl.TargetFormat

val macPackageVersion = providers.gradleProperty("macPackageVersion").orElse("0.5.2")

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
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg)
            packageName = "Agent Workspace Manager"
            // macOS jpackage rejects versions whose first component is zero.
            // CI overrides this with a compatible internal app version while the
            // project and release version remain 0.5.2.
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
