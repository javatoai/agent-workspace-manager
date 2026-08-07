package com.snowball.taskwt.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TaskBranchNamingTest {
    @Test
    fun `single module keeps the requested branch`() {
        val modules = listOf(ServiceModuleConfig(id = "master", name = "主线", baseRef = "origin/master"))

        assertEquals(
            mapOf("master" to "feature/OBT-123"),
            TaskBranchNaming.derive("feature/OBT-123", modules),
        )
    }

    @Test
    fun `multiple modules append the normalized base branch`() {
        val modules = listOf(
            ServiceModuleConfig(id = "master", name = "主线", baseRef = "origin/master"),
            ServiceModuleConfig(id = "release", name = "测试线", baseRef = "origin/release/test"),
        )

        assertEquals(
            mapOf(
                "master" to "feature/OBT-123-master",
                "release" to "feature/OBT-123-release-test",
            ),
            TaskBranchNaming.derive("feature/OBT-123", modules),
        )
    }

    @Test
    fun `modules on the same base branch share the exact task branch`() {
        val modules = listOf(
            ServiceModuleConfig(id = "one", name = "一", baseRef = "origin/master"),
            ServiceModuleConfig(id = "two", name = "二", baseRef = "origin/master"),
        )

        assertEquals(
            mapOf("one" to "feature/OBT-123", "two" to "feature/OBT-123"),
            TaskBranchNaming.derive("feature/OBT-123", modules),
        )
    }

    @Test
    fun `bare and fully qualified base refs derive the same complete suffix`() {
        val modules = listOf(
            ServiceModuleConfig(id = "release", name = "release", baseRef = "release/test"),
            ServiceModuleConfig(id = "master", name = "master", baseRef = "refs/heads/master"),
        )

        assertEquals(
            mapOf(
                "release" to "feature/x-release-test",
                "master" to "feature/x-master",
            ),
            TaskBranchNaming.derive("feature/x", modules),
        )
    }
}
