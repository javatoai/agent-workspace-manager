plugins {
    kotlin("jvm") version "2.4.10" apply false
    kotlin("plugin.serialization") version "2.4.10" apply false
    id("org.jetbrains.compose") version "1.11.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}

allprojects {
    group = "com.snowball.awm"
    version = "0.4.2"
}

subprojects {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // The release workflow opts into this narrowly scoped exclusion while the
        // hosted Git integration fixtures are being made portable. Normal local
        // and CI test runs still execute the complete suite.
        if (project.path == ":core" && providers.gradleProperty("skipHostedGitIntegrationTests").isPresent) {
            filter {
                excludeTestsMatching("com.snowball.awm.core.TagBuildServiceIntegrationTest")
                excludeTestsMatching("com.snowball.awm.core.TagConflictIntegrationTest")
                excludeTestsMatching("com.snowball.awm.core.WorkspaceLifecycleIntegrationTest")
            }
        }
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}
