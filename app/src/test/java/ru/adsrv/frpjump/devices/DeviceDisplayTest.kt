package ru.adsrv.frpjump.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.api.ApiException
import ru.adsrv.frpjump.api.NetworkException

class DeviceDisplayTest {
    private val decomposed = "e\u0301" // é as two code points, one after NFC

    @Test
    fun `the counter counts code points after NFC`() {
        assertEquals(1, labelLength(decomposed))
        assertEquals(2, labelLength("\uD83D\uDDA5\ufe0f")) // a desktop-computer emoji: a surrogate pair and a selector
        assertEquals(0, labelLength(""))
    }

    @Test
    fun `line breaks and tabs become spaces`() {
        assertEquals("a b c d e", oneLine("a\r\nb\nc\td\u2028e"))
        assertEquals(LabelEdit("Щиток гараж", 11), limitLabel("", "Щиток\nгараж", 11))
    }

    @Test
    fun `typing at the cap changes nothing, wherever the cursor is`() {
        val full = "a".repeat(LABEL_MAX)
        assertEquals(LabelEdit(full, LABEL_MAX), limitLabel(full, full + "b", LABEL_MAX + 1))
        assertEquals(LabelEdit(full, 10), limitLabel(full, "a".repeat(10) + "b" + "a".repeat(LABEL_MAX - 10), 11))
    }

    @Test
    fun `a longer paste is cut at the cap and the rest is kept`() {
        val edit = limitLabel("ab", "a" + "x".repeat(100) + "b", 101)
        assertEquals("a" + "x".repeat(LABEL_MAX - 2) + "b", edit.text)
        assertEquals(LABEL_MAX - 1, edit.cursor)
        assertEquals(LABEL_MAX, labelLength(edit.text))
    }

    @Test
    fun `a cut counts after NFC and never splits a surrogate pair`() {
        val decomposedPaste = decomposed.repeat(70)
        assertEquals(LABEL_MAX, labelLength(limitLabel("", decomposedPaste, decomposedPaste.length).text))
        val emoji = "\uD83D\uDE00".repeat(70)
        val cut = limitLabel("", emoji, emoji.length).text
        assertEquals("\uD83D\uDE00".repeat(LABEL_MAX), cut)
    }

    @Test
    fun `save is enabled by a real change only`() {
        assertFalse(labelChanged("Щиток ", "Щиток"))
        assertFalse(labelChanged("  ", null))
        assertFalse(labelChanged("caf$decomposed", "caf\u00e9"))
        assertTrue(labelChanged("Щиток, гараж", "Щиток"))
        assertTrue(labelChanged("", "Щиток"))
    }

    @Test
    fun `only the changed fields are sent`() {
        assertTrue(displayChange("Щиток", IconPick.Auto, "Щиток", null).empty)
        assertEquals(DisplayChange(DisplayValue("Гараж"), null), displayChange(" Гараж ", IconPick.Auto, "Щиток", null))
        assertEquals(DisplayChange(null, DisplayValue("camera")), displayChange("Щиток", IconPick.Known(DeviceIcon.CAMERA), "Щиток", null))
        assertTrue(displayChange("", IconPick.Known(DeviceIcon.CAMERA), null, "camera").empty)
    }

    @Test
    fun `an empty field clears the label and Auto drops the choice`() {
        assertEquals(DisplayChange(DisplayValue(null), DisplayValue(null)), displayChange(" ", IconPick.Auto, "Щиток", "camera"))
    }

    @Test
    fun `an unknown choice is shown as Other and never sent`() {
        val pick = IconPick.of("toaster")
        assertEquals(IconPick.Other("toaster"), pick)
        assertTrue(displayChange("", pick, null, "toaster").empty)
        assertEquals(IconPick.Auto, IconPick.of(null))
        assertEquals(IconPick.Known(DeviceIcon.WIRENBOARD), IconPick.of("wirenboard"))
    }

    @Test
    fun `the five known reasons are matched whole`() {
        assertEquals(LabelRefusal.TOO_LONG_RAW, LabelRefusal.of("label is too long"))
        assertEquals(LabelRefusal.TOO_LONG, LabelRefusal.of("label is longer than 64 characters"))
        assertEquals(LabelRefusal.CONTROL, LabelRefusal.of("label contains a line break or control character"))
        assertEquals(LabelRefusal.INVISIBLE, LabelRefusal.of("label contains an invisible or formatting character"))
        assertEquals(LabelRefusal.NOT_VISIBLE, LabelRefusal.of("label has no visible characters"))
        assertNull(LabelRefusal.of("label is too long!"))
        assertNull(LabelRefusal.of("Label is too long"))
    }

    private val labelOnly = DisplayChange(DisplayValue("x"), null)
    private val iconOnly = DisplayChange(null, DisplayValue("pc"))
    private val text: (Throwable) -> String = { "said: ${it.message}" }

    @Test
    fun `each failure is reported where it belongs`() {
        assertEquals(
            DisplayFailure.Label(LabelRefusal.NOT_VISIBLE),
            displayFailure(ApiException(400, "label has no visible characters"), labelOnly, text),
        )
        assertEquals(DisplayFailure.LabelText("label is odd"), displayFailure(ApiException(400, "label is odd"), labelOnly, text))
        assertEquals(DisplayFailure.Other("unknown icon"), displayFailure(ApiException(400, "unknown icon"), iconOnly, text))
        assertEquals(DisplayFailure.Disabled, displayFailure(ApiException(403, "this device is disabled"), labelOnly, text))
        val missing = ApiException(404, "no such device 'wb'")
        assertEquals(DisplayFailure.Other(text(missing)), displayFailure(missing, labelOnly, text))
        assertEquals(
            DisplayFailure.Unreachable,
            displayFailure(NetworkException(NetworkException.Reason.UNREACHABLE, "way.example", "timeout"), labelOnly, text),
        )
        val broke = NetworkException(NetworkException.Reason.BROKE, "way.example", "reset")
        assertEquals(DisplayFailure.Other(text(broke)), displayFailure(broke, labelOnly, text))
    }
}
