package com.snowball.awm.desktop

import com.snowball.awm.core.IndependentCloneModuleConfig
import com.snowball.awm.core.ServiceModuleConfig
import com.snowball.awm.core.WorkspaceStrategy
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

class ServiceWorkspaceModuleValidationTest {
    @Test
    fun `invalid branch input remains a draft until explicit save conversion`() {
        val cloneDraft = IndependentCloneModuleEditorDraft(id = "clone", branch = "")
        val tagDraft = ServiceModuleEditorDraft(id = "api", tagTargetRef = "")

        assertFailsWith<IllegalArgumentException> { cloneDraft.toConfig() }
        assertFailsWith<IllegalArgumentException> { tagDraft.toConfig() }
    }

    @Test
    fun `duplicate clone validation names the branch and modules`() {
        val error = assertFailsWith<IllegalArgumentException> {
            validateServiceWorkspaceModules(
                strategy = WorkspaceStrategy.INDEPENDENT_CLONE,
                modules = emptyList(),
                cloneModules = listOf(
                    IndependentCloneModuleConfig(id = "one", name = "api", branch = "origin/master"),
                    IndependentCloneModuleConfig(id = "two", name = "job", branch = "origin/master"),
                ),
            )
        }

        assertContains(error.message.orEmpty(), "origin/master")
        assertContains(error.message.orEmpty(), "api")
        assertContains(error.message.orEmpty(), "job")
    }

    @Test
    fun `standard module validation rejects directory collisions`() {
        val error = assertFailsWith<IllegalArgumentException> {
            validateServiceWorkspaceModules(
                strategy = WorkspaceStrategy.STANDARD_WORKTREE,
                modules = listOf(
                    ServiceModuleConfig(id = "one", name = "api/v1"),
                    ServiceModuleConfig(id = "two", name = "api-v1"),
                ),
                cloneModules = emptyList(),
            )
        }

        assertContains(error.message.orEmpty(), "目录")
    }
}
