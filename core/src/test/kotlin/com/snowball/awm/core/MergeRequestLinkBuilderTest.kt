package com.snowball.awm.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MergeRequestLinkBuilderTest {
    @Test
    fun `gitlab ssh remote produces a prefilled merge request link`() {
        assertEquals(
            "https://gitlab.snowballtech.com/fp/android-transit-service/-/merge_requests/new?" +
                "merge_request%5Bsource_branch%5D=awm%2Ffeatures&merge_request%5Btarget_branch%5D=release%2F3.11.71",
            MergeRequestLinkBuilder.build(
                "git@gitlab.snowballtech.com:fp/android-transit-service.git",
                "awm/features",
                "release/3.11.71",
            )?.url,
        )
    }

    @Test
    fun `github https remote produces a prefilled pull request link`() {
        assertEquals(
            "https://github.com/javatoai/compass/compare/master...awm%2Fsync?expand=1",
            MergeRequestLinkBuilder.build(
                "https://github.com/javatoai/compass.git",
                "awm/sync",
                "master",
            )?.url,
        )
    }

    @Test
    fun `unknown remote does not produce a guessed link`() {
        assertNull(MergeRequestLinkBuilder.build("file:///Q:/repo", "source", "master"))
    }
}
