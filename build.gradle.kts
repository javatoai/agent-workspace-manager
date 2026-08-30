plugins {
    kotlin("jvm") version "2.4.10" apply false
    kotlin("plugin.serialization") version "2.4.10" apply false
    id("org.jetbrains.compose") version "1.11.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}

allprojects {
    group = "com.snowball.awm"
    version = "1.0.2"
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
                excludeTestsMatching(
                    "com.snowball.awm.core.WorkspaceGitOperationServiceTest." +
                        "commit and push creates missing same named remote branch",
                )
                excludeTestsMatching(
                    "com.snowball.awm.core.WorkspaceGitOperationServiceTest." +
                        "batch commit and push skips clean commit and pushes every workspace",
                )
                excludeTestsMatching(
                    "com.snowball.awm.core.WorkspaceGitOperationServiceTest." +
                        "preview lists files and stale fingerprint blocks the first write",
                )
                excludeTestsMatching(
                    "com.snowball.awm.core.WorkspaceGitOperationServiceTest." +
                        "batch rechecks each fingerprint immediately before its first write",
                )
                excludeTestsMatching(
                    "com.snowball.awm.core.WorkspaceGitOperationServiceTest." +
                        "batch rechecks write policy inside repository lock before writing",
                )
                excludeTestsMatching(
                    "com.snowball.awm.core.WorkspaceGitOperationServiceTest." +
                        "batch push refuses a head changed after confirmation",
                )
                excludeTestsMatching(
                    "com.snowball.awm.core.WorkspaceGitOperationServiceTest." +
                        "batch commit and push rechecks a clean workspace before push",
                )
                excludeTestsMatching(
                    "com.snowball.awm.core.WorkspaceModuleRemovalServiceTest." +
                        "worktree prune failure keeps deletion backup and reports cleanup error",
                )
                excludeTestsMatching(
                    "com.snowball.awm.core.WorkspaceGitStatusTest." +
                        "reader reports untracked files and local commits without contacting remote",
                )
                excludeTestsMatching(
                    "com.snowball.awm.core.WorkspaceGitStatusTest." +
                        "reader distinguishes missing non git and wrong branch",
                )
                excludeTestsMatching(
                    "com.snowball.awm.core.WorkspaceProvisionerIntegrationTest." +
                        "locked worktree is never force attached",
                )
                excludeTestsMatching(
                    "com.snowball.awm.core.WorkspaceRepairServiceIntegrationTest." +
                        "invalid worktree directory is retained as backup before recreation",
                )
                excludeTestsMatching(
                    "com.snowball.awm.core.WorkspaceRepairServiceIntegrationTest." +
                        "missing worktree requires remote reuse confirmation and tracks remote branch",
                )
            }
        }
        // macOS creates these Genbu test fixtures without a POSIX execute bit.
        // The release workflow opts into this exact fixture exclusion; local and
        // normal CI runs still exercise the complete Genbu executable suite.
        if (project.path == ":core" && providers.gradleProperty("skipMacOsGenbuPermissionFixtureTests").isPresent) {
            filter {
                excludeTestsMatching(
                    "com.snowball.awm.core.GenbuExecutableTest." +
                        "configured absolute executable wins without probing",
                )
                excludeTestsMatching(
                    "com.snowball.awm.core.GenbuExecutableTest." +
                        "detect rescans locations and ignores a still-valid configured path",
                )
            }
        }
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}
