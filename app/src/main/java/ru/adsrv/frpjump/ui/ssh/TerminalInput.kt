package ru.adsrv.frpjump.ui.ssh

import android.content.Context
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import com.termux.view.TerminalView

/**
 * Holds a Termux view and talks to the keyboard for it, so every keystroke
 * reaches the shell at once, whatever the keyboard.
 *
 * Termux v0.118.3 asks for char-based input with an input type that has no
 * class (VISIBLE_PASSWORD | NO_SUGGESTIONS alone), which some keyboards
 * (Yandex) read as ordinary text: they show suggestions and hold the word as
 * composing text, which Termux sends only on commit — so `ls /mnt/` showed
 * `ls /` and a sticky Ctrl waited for a word that never came. [TerminalView]
 * is final, so this parent takes the input focus instead: it adds the text
 * class to Termux's EditorInfo, forwards composing text as it changes, and
 * passes key events through. The terminal stays focusable (its long-press
 * selection needs that) but hands the focus straight back.
 */
class TerminalInput(context: Context, val terminal: TerminalView) : FrameLayout(context) {
    init {
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = FOCUS_BEFORE_DESCENDANTS
        addView(terminal, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        terminal.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) requestFocus() }
    }

    /**
     * Shows the soft keyboard: implicitly for a tap on the terminal; [explicit]
     * for ⌨, so it shows after Back hid it and with a hardware keyboard too.
     */
    fun showKeyboard(explicit: Boolean = false) {
        requestFocus()
        val flags = if (explicit) 0 else InputMethodManager.SHOW_IMPLICIT
        context.getSystemService(InputMethodManager::class.java).showSoftInput(this, flags)
    }

    fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = terminal.onCreateInputConnection(outAttrs) ?: return null
        if (outAttrs.inputType and InputType.TYPE_MASK_CLASS == 0 &&
            outAttrs.inputType and InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD != 0
        ) {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        return ComposingForwarder(connection)
    }

    override fun onKeyPreIme(keyCode: Int, event: KeyEvent) = terminal.onKeyPreIme(keyCode, event)

    override fun onKeyDown(keyCode: Int, event: KeyEvent) = terminal.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent) = terminal.onKeyUp(keyCode, event)

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent) =
        terminal.onKeyMultiple(keyCode, repeatCount, event)

    /**
     * Keeps Termux's editable empty: composing text goes to the terminal as
     * it changes, and a later change or the commit sends only the difference.
     * Termux sends each character through its key path, which applies the
     * sticky Ctrl / Alt from the extra keys.
     */
    private inner class ComposingForwarder(target: InputConnection) : InputConnectionWrapper(target, false) {
        private var sent = ""

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            if (terminal.currentSession == null) return true
            sendDelta(text.toString())
            sent = text.toString()
            return true
        }

        override fun finishComposingText(): Boolean {
            sent = ""
            return super.finishComposingText()
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            if (sent.isEmpty()) return super.commitText(text, newCursorPosition)
            sendDelta(text.toString())
            sent = ""
            return true
        }

        private fun sendDelta(next: String) {
            val delta = composingDelta(sent, next)
            // Termux turns each deleted character into a DEL key.
            if (delta.erase > 0) super.deleteSurroundingText(delta.erase, 0)
            if (delta.append.isNotEmpty()) super.commitText(delta.append, 1)
        }
    }
}
