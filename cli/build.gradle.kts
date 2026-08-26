plugins {
    application
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

application {
    applicationName = "awm"
    mainClass = "com.snowball.awm.cli.MainKt"
}
