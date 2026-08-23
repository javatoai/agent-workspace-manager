package com.snowball.awm.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ProductionTagVersioningTest {
    @Test
    fun `next base increments the highest formal patch and ignores beta and numbered builds`() {
        assertEquals(
            "3.11.71",
            ProductionTagVersioning.nextBase(
                listOf("3.11.69", "3.11.70.beta-14", "3.11.70", "3.11.70.4", "other"),
            ),
        )
    }

    @Test
    fun `first tag is the base version and later release changes use the next numbered build`() {
        val first = ProductionTagVersioning.expectation(
            baseVersion = "3.11.71",
            releaseSha = "release-a",
            tags = emptyList(),
        )
        val changed = ProductionTagVersioning.expectation(
            baseVersion = "3.11.71",
            releaseSha = "release-b",
            tags = listOf(
                ProductionRemoteTag("3.11.71", "release-a"),
                ProductionRemoteTag("3.11.71.1", "release-c"),
                ProductionRemoteTag("3.11.71.beta-9", "ignored"),
            ),
        )

        assertEquals(ProductionTagExpectation.Create("3.11.71"), first)
        assertEquals(ProductionTagExpectation.Create("3.11.71.2"), changed)
    }

    @Test
    fun `same release sha never receives a second production tag`() {
        assertEquals(
            ProductionTagExpectation.AlreadyBuilt("3.11.71.1"),
            ProductionTagVersioning.expectation(
                baseVersion = "3.11.71",
                releaseSha = "release-b",
                tags = listOf(
                    ProductionRemoteTag("3.11.71", "release-a"),
                    ProductionRemoteTag("3.11.71.1", "release-b"),
                ),
            ),
        )
    }
}
