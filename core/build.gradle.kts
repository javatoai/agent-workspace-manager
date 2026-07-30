plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("com.google.jimfs:jimfs:1.3.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.13.4")
}
