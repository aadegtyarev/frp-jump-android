package ru.adsrv.frpjump.ssh

import android.content.ClipDescription
import org.junit.Assert.assertEquals
import org.junit.Test

class ClipKindTest {
    @Test fun textIsText() {
        assertEquals(ClipKind.TEXT, clipKind(listOf("text/plain")))
        assertEquals(ClipKind.TEXT, clipKind(listOf("text/html")))
        assertEquals(ClipKind.TEXT, clipKind(listOf("TEXT/PLAIN")))
    }

    @Test fun anImageWithoutTextIsAnImage() {
        assertEquals(ClipKind.IMAGE, clipKind(listOf("image/png")))
        assertEquals(ClipKind.IMAGE, clipKind(listOf("image/jpeg", "image/webp")))
    }

    @Test fun anyTextMakesItATextClipSoPasteIsUnchanged() {
        assertEquals(ClipKind.TEXT, clipKind(listOf("image/png", "text/plain")))
        assertEquals(ClipKind.TEXT, clipKind(listOf("text/html", "image/png")))
    }

    @Test fun otherThingsAreNothingToPaste() {
        assertEquals(ClipKind.NONE, clipKind(emptyList<String>()))
        assertEquals(ClipKind.NONE, clipKind(listOf("application/pdf")))
        assertEquals(ClipKind.NONE, clipKind(listOf("video/mp4")))
        assertEquals(ClipKind.NONE, clipKind(null as ClipDescription?))
    }
}
