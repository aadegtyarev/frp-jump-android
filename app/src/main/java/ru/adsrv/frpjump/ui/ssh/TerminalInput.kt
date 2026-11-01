package ru.adsrv.frpjump.ui.ssh

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import com.termux.view.TerminalView
import kotlin.math.abs

/**
 * Holds a Termux view and owns its keyboard focus. Normally the keyboard
 * composes text and Termux sends it on commit. Settings › Terminal › Direct
 * input opts into sending composing changes immediately, useful for shell
 * commands and sticky Ctrl/Alt. That mode also asks for no suggestions.
 * [TerminalView] is final, so this parent supplies EditorInfo and optionally
 * wraps its connection. Hardware key events pass through in either mode.
 * The terminal stays focusable for selection but hands focus straight back.
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
     * Offered each tap (a short touch that did not move) before the view
     * acts on it; true takes it: the view gets a cancel instead of the
     * lift, so it neither clicks (mouse reporting) nor counts a tap; the
     * taker shows the keyboard. See [TerminalTap].
     */
    var onTap: ((MotionEvent) -> Boolean)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var tapX = 0f
    private var tapY = 0f
    private var tapping = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapX = event.x
                tapY = event.y
                // A tap that ends a selection, or a real mouse, stays the view's.
                tapping = !terminal.isSelectingText && !event.isFromSource(InputDevice.SOURCE_MOUSE)
            }
            MotionEvent.ACTION_MOVE ->
                if (abs(event.x - tapX) > touchSlop || abs(event.y - tapY) > touchSlop) tapping = false
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> tapping = false
            MotionEvent.ACTION_UP -> {
                val tap = tapping && !terminal.isSelectingText &&
                    event.eventTime - event.downTime < ViewConfiguration.getLongPressTimeout()
                tapping = false
                if (tap && onTap?.invoke(event) == true) return true
            }
        }
        return false
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

    private var activeConnection: InputConnection? = null

    /** Switching mode finishes the old composition once, then asks the IME for a fresh connection. */
    var directInput: Boolean = false
        set(value) {
            if (field == value) return
            activeConnection?.finishComposingText()
            activeConnection = null
            field = value
            if (isFocused) context.getSystemService(InputMethodManager::class.java).restartInput(this)
        }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val connection = terminal.onCreateInputConnection(outAttrs) ?: return null
        outAttrs.inputType = terminalInputType(directInput)
        return (if (directInput) ComposingForwarder(connection) else connection).also { activeConnection = it }
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
