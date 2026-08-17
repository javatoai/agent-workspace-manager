package com.snowball.awm.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServiceEditorLayoutTest {
    @Test
    fun `service editor widens to host the section navigation`() {
        val policy = serviceEditorDialogWidthPolicy()

        assertEquals(0.72f, policy.fillFraction)
        assertEquals(860, policy.minWidthDp)
        assertEquals(1200, policy.maxWidthDp)
    }

    @Test
    fun `tag target and message fields use intrinsic single line height`() {
        val layout = tagConfigurationFieldLayout()

        assertNull(layout.heightDp)
        assertTrue(layout.singleLine)
    }

    @Test
    fun `tag target and message fields split the row at its center`() {
        val layout = tagConfigurationFieldLayout()

        assertEquals(layout.messageWeight, layout.targetWeight)
    }
}
