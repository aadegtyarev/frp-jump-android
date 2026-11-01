package ru.adsrv.frpjump.ui.files

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import ru.adsrv.frpjump.ui.Palette

/**
 * Sora's editor as the viewer uses it: monospace, no current-line or
 * block marks, ASCII control characters drawn as their boxed names
 * (`ESC`, `NUL`), no undo history, and read only ([setEditable], the one
 * switch an editor will turn). TalkBack gets the lines on screen, not a
 * 2 MB text, and no editing actions while it is read only.
 */
@SuppressLint("ViewConstructor")
internal class ViewerEditor(context: Context) : CodeEditor(context) {
    init {
        setEditable(false)
        setUndoEnabled(false)
        setTypefaceText(Typeface.MONOSPACE)
        setTypefaceLineNumber(Typeface.MONOSPACE)
        setRenderFunctionCharacters(true)
        setHighlightCurrentLine(false)
        setBlockLineEnabled(false)
        setHighlightBracketPair(false)
        setCursorAnimationEnabled(false)
        // Text size is the menu's (shared with the terminal), not a pinch.
        setScalable(false)
        setDisplayLnPanel(false)
        // Numbers stay put when unwrapped text scrolls sideways.
        setPinLineNumber(true)
        setNonPrintablePaintingFlags(0)
        setLineSpacing(0f, 1.2f)
    }

    private var fontSp = 0

    /** While searching, the selection is the match stepped to: no handles to drag. */
    var searching = false
        set(value) {
            field = value
            handlesFor(colorScheme)
            invalidate()
        }

    override fun setColorScheme(colors: EditorColorScheme) {
        super.setColorScheme(colors)
        handlesFor(colors)
    }

    private fun handlesFor(scheme: EditorColorScheme?) {
        (scheme as? ViewerColorScheme)?.hideHandles(searching)
    }

    /** The text size in sp, set again only when it changes (a change lays the text out anew). */
    fun setFontSp(sp: Int) {
        if (sp == fontSp) return
        fontSp = sp
        setTextSize(sp.toFloat())
    }

    /** The selection (a match stepped to) back to a bare cursor, where it ends, without scrolling. */
    fun collapseSelection() {
        if (!cursor.isSelected) return
        val end = cursor.right()
        setSelection(end.line, end.column, false, SelectionChangeEvent.CAUSE_UNKNOWN)
    }

    override fun createAccessibilityNodeInfo(): AccessibilityNodeInfo {
        val info = super.createAccessibilityNodeInfo()
        if (isEnabled && lineCount > 0) {
            val first = firstVisibleLine.coerceIn(0, lineCount - 1)
            val last = lastVisibleLine.coerceIn(first, lineCount - 1)
            info.text = text.subContent(first, 0, last, text.getColumnCount(last)).toString()
            info.setTextSelection(-1, -1)
        }
        if (!isEditable) {
            info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CUT)
            info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE)
            info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)
        }
        return info
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val edits = action == AccessibilityNodeInfo.ACTION_CUT || action == AccessibilityNodeInfo.ACTION_PASTE ||
            action == AccessibilityNodeInfo.ACTION_SET_TEXT
        if (edits && !isEditable) return false
        return super.performAccessibilityAction(action, arguments)
    }
}

/**
 * The editor's colours: the TextMate [model] for the text, the rest from
 * [palette]: the card's ground, muted line numbers, search matches on the
 * accent's container and the current one (the selection) as the accent
 * with its text, as everywhere in the app.
 */
internal class ViewerColorScheme(model: ThemeModel, private val palette: Palette) : TextMateColorScheme(ThemeRegistry.getInstance(), model) {
    // Set after the superclass's constructor, which already applies the defaults once.
    private var overrides: Map<Int, Int>? = null
    private var handlesHidden = false

    init {
        overrides = editorColours(palette)
        applyDefault()
    }

    override fun applyDefault() {
        super.applyDefault()
        overrides?.forEach { (id, colour) -> setColor(id, colour) }
        if (handlesHidden) setColor(SELECTION_HANDLE, 0)
    }

    /** Selection handles drawn ([hidden] false) or not. */
    fun hideHandles(hidden: Boolean) {
        handlesHidden = hidden
        setColor(SELECTION_HANDLE, if (hidden) 0 else palette.accent.toInt())
    }
}

private fun editorColours(p: Palette): Map<Int, Int> {
    val c = { v: Long -> v.toInt() }
    return mapOf(
        EditorColorScheme.WHOLE_BACKGROUND to c(p.card),
        EditorColorScheme.TEXT_NORMAL to c(p.ink),
        EditorColorScheme.LINE_NUMBER_BACKGROUND to c(p.card),
        EditorColorScheme.LINE_NUMBER to c(p.muted),
        EditorColorScheme.LINE_NUMBER_CURRENT to c(p.muted),
        EditorColorScheme.LINE_DIVIDER to c(p.divider),
        EditorColorScheme.CURRENT_LINE to 0,
        EditorColorScheme.MATCHED_TEXT_BACKGROUND to c(p.accentContainer),
        EditorColorScheme.SELECTED_TEXT_BACKGROUND to c(p.accent),
        EditorColorScheme.TEXT_SELECTED to c(p.onAccent),
        EditorColorScheme.SELECTION_HANDLE to c(p.accent),
        EditorColorScheme.SELECTION_INSERT to c(p.accent),
        EditorColorScheme.NON_PRINTABLE_CHAR to c(p.muted),
        EditorColorScheme.FUNCTION_CHAR_BACKGROUND_STROKE to c(p.muted),
        EditorColorScheme.SCROLL_BAR_THUMB to c(p.faint),
        EditorColorScheme.SCROLL_BAR_THUMB_PRESSED to c(p.muted),
        EditorColorScheme.SCROLL_BAR_TRACK to 0,
        EditorColorScheme.BLOCK_LINE to 0,
        EditorColorScheme.BLOCK_LINE_CURRENT to 0,
        EditorColorScheme.HIGHLIGHTED_DELIMITERS_FOREGROUND to c(p.ink),
        EditorColorScheme.HIGHLIGHTED_DELIMITERS_BACKGROUND to 0,
        EditorColorScheme.HIGHLIGHTED_DELIMITERS_UNDERLINE to 0,
        EditorColorScheme.TEXT_ACTION_WINDOW_BACKGROUND to c(p.raised),
        EditorColorScheme.TEXT_ACTION_WINDOW_ICON_COLOR to c(p.ink),
    )
}
