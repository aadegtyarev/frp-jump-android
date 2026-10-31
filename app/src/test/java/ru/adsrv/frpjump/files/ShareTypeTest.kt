package ru.adsrv.frpjump.files

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareTypeTest {
    @Test
    fun `one type is declared as it is`() {
        assertEquals("image/png", shareType(listOf("image/png", "image/png")))
    }

    @Test
    fun `one family gets a wildcard subtype`() {
        assertEquals("image/*", shareType(listOf("image/png", "image/jpeg")))
    }

    @Test
    fun `mixed families or nothing known share as any type`() {
        assertEquals("*/*", shareType(listOf("image/png", "text/plain")))
        assertEquals("*/*", shareType(emptyList()))
    }
}
