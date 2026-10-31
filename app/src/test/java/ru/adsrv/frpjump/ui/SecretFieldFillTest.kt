package ru.adsrv.frpjump.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Autofill and a field that shows a saved password. */
class SecretFieldFillTest {
    @Test
    fun `an empty field without a saved password takes a fill`() {
        assertTrue(offersFill(saved = false, focused = false, typed = false))
    }

    @Test
    fun `a field showing a saved password takes none until it is tapped or typed in`() {
        assertFalse(offersFill(saved = true, focused = false, typed = false))
        assertTrue(offersFill(saved = true, focused = true, typed = false))
        assertTrue(offersFill(saved = true, focused = false, typed = true))
    }
}
