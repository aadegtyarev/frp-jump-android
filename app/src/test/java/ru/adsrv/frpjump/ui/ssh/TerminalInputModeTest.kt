package ru.adsrv.frpjump.ui.ssh

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.adsrv.frpjump.settings.AppSettings

class TerminalInputModeTest {
    @Test
    fun `default mode allows ordinary text composition and multiline input`() {
        val type = terminalInputType(AppSettings().terminalDirectInput)
        assertEquals(InputType.TYPE_CLASS_TEXT, type and InputType.TYPE_MASK_CLASS)
        assertEquals(InputType.TYPE_TEXT_VARIATION_NORMAL, type and InputType.TYPE_MASK_VARIATION)
        assertEquals(0, type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        assertEquals(InputType.TYPE_TEXT_FLAG_MULTI_LINE, type and InputType.TYPE_TEXT_FLAG_MULTI_LINE)
    }

    @Test
    fun `direct mode requests characters without suggestions with a valid text class`() {
        val type = terminalInputType(true)
        assertEquals(InputType.TYPE_CLASS_TEXT, type and InputType.TYPE_MASK_CLASS)
        assertEquals(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, type and InputType.TYPE_MASK_VARIATION)
        assertEquals(InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
    }
}
