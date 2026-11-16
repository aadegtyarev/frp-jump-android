package ru.adsrv.frpjump.assistant

import android.view.KeyEvent
import com.termux.terminal.KeyHandler

/** How the emulator of a tab sends the cursor and keypad keys right now (the remote program chooses). Read where the emulator takes input. */
internal class KeyModes(val cursorApplication: Boolean, val keypadApplication: Boolean)

/**
 * The keys `terminal_input` may press, in the order the model is told them. A key is its bytes as a hardware keyboard sends them:
 * the fixed ones here, the cursor, Home, End and page keys from the emulator's own table ([KeyHandler]) in the tab's current mode.
 */
internal enum class TerminalKey(val wire: String, val label: String, private val fixed: String? = null, private val keyCode: Int = 0) {
    ENTER("enter", "Enter", "\r"),
    TAB("tab", "Tab", "\t"),
    ESCAPE("escape", "Esc", "\u001b"),
    BACKSPACE("backspace", "Backspace", "\u007f"),
    UP("up", "Up", keyCode = KeyEvent.KEYCODE_DPAD_UP),
    DOWN("down", "Down", keyCode = KeyEvent.KEYCODE_DPAD_DOWN),
    LEFT("left", "Left", keyCode = KeyEvent.KEYCODE_DPAD_LEFT),
    RIGHT("right", "Right", keyCode = KeyEvent.KEYCODE_DPAD_RIGHT),
    HOME("home", "Home", keyCode = KeyEvent.KEYCODE_MOVE_HOME),
    END("end", "End", keyCode = KeyEvent.KEYCODE_MOVE_END),
    PAGE_UP("page_up", "PgUp", keyCode = KeyEvent.KEYCODE_PAGE_UP),
    PAGE_DOWN("page_down", "PgDn", keyCode = KeyEvent.KEYCODE_PAGE_DOWN),
    CTRL_C("ctrl_c", "Ctrl+C", "\u0003"),
    CTRL_D("ctrl_d", "Ctrl+D", "\u0004"),
    CTRL_Z("ctrl_z", "Ctrl+Z", "\u001a"),
    CTRL_L("ctrl_l", "Ctrl+L", "\u000c");

    /** The key's bytes in [modes]; null when the emulator has none for it. */
    fun bytes(modes: KeyModes): ByteArray? =
        (fixed ?: KeyHandler.getCode(keyCode, 0, modes.cursorApplication, modes.keypadApplication))?.toByteArray(Charsets.UTF_8)

    companion object {
        fun fromWire(name: String): TerminalKey? = entries.firstOrNull { it.wire == name }

        /** The key names as the owner reads them (`Enter, Ctrl+C`), for the card and the receipt. */
        fun labels(keys: List<TerminalKey>): List<String> = keys.map { it.label }
    }
}

/** One valid `terminal_input` call: the tab, the text typed first and the keys pressed after it. At least one of text and keys. */
internal class TypedInput(val tabId: Int, val text: String?, val keys: List<TerminalKey>) {
    /** Never prints what would be typed. */
    override fun toString() = "TypedInput(tab=$tabId)"

    /** The text as the terminal gets it: a line break of any kind is one Enter (CR), a tab is a tab, UTF-8. Not a paste: no bracketed paste. */
    fun textBytes(): ByteArray = text?.let(::typedText)?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)

    /** All bytes, text first and then the keys in order; null when a key has no bytes in [modes] (nothing is then typed). */
    fun bytes(modes: KeyModes): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        out.write(textBytes())
        for (key in keys) out.write(key.bytes(modes) ?: return null)
        return out.toByteArray()
    }
}

/** [raw] as typed: `\r\n`, `\n` and `\r` each become one CR (Enter). */
internal fun typedText(raw: String): String = raw.replace("\r\n", "\r").replace('\n', '\r')

/**
 * Whether [text] holds only what can be typed: printable characters, newline (any kind) and tab. Every other C0 control, DEL and every C1
 * control (U+0080..U+009F) is refused, so escape sequences and terminal commands cannot ride in the text; keys are for those.
 */
internal fun typeable(text: String): Boolean = text.none { c ->
    c != '\n' && c != '\r' && c != '\t' && (c < ' ' || c == '\u007f' || c in '\u0080'..'\u009f')
}
