package com.snowball.awm.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServicesPresentationTest {
    @Test
    fun `single group does not render group navigation`() {
        assertFalse(serviceGroupNavigationVisible(1))
        assertTrue(serviceGroupNavigationVisible(2))
    }

    @Test
    fun `invalid selected service group falls back to first available group`() {
        assertEquals("first", resolveServiceGroupSelection("removed", listOf("first", "second")))
        assertEquals("second", resolveServiceGroupSelection("second", listOf("first", "second")))
        assertEquals(null, resolveServiceGroupSelection("removed", emptyList()))
    }
}
