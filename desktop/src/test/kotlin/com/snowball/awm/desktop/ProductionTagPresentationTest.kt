package com.snowball.awm.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class ProductionTagPresentationTest {
    @Test
    fun `production tag navigation is immediately above settings`() {
        val entries = NavigationItem.entries

        assertEquals(entries.indexOf(NavigationItem.SETTINGS) - 1, entries.indexOf(NavigationItem.PRODUCTION_TAG))
    }
}
