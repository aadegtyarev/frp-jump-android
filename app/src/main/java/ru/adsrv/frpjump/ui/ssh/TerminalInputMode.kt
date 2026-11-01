package ru.adsrv.frpjump.ui.ssh

import android.text.InputType

/** Normal text input allows IME composition; direct input requests characters without suggestions. */
internal fun terminalInputType(direct: Boolean): Int = if (direct) {
    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
} else {
    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
}
