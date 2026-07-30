import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.snowball.taskwt.desktop.generated.resources"
}

compose.desktop {
    application {
        mainClass = "com.snowball.taskwt.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg)
            packageName = "Task Worktree Manager"
            packageVersion = "0.1.1"
            description = "Multi-repository Git worktree and UAT tag manager"
            vendor = "Snowball Technology"

            windows {
                iconFile.set(project.file("src/main/resources/app-icon.ico"))
                menuGroup = "Task Worktree Manager"
                shortcut = true
                perUserInstall = true
                upgradeUuid = "7bc32ed2-b7bf-49ea-af10-2b6d5b965ec0"
            }

            macOS {
                iconFile.set(project.file("src/main/resources/app-icon.icns"))
                bundleID = "com.snowball.taskwt"
                dockName = "Task Worktree Manager"
            }
        }
    }
}
