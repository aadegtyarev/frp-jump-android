package ru.adsrv.frpjump.ui.ssh

import android.view.KeyEvent
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.terminal.KeyHandler
import com.termux.view.TerminalView
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.PasteGlyph

/** Sticky Ctrl / Alt from the extra rows: apply to the next key, then release. */
@Stable
class Modifiers {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)

    fun takeCtrl() = ctrl.also { ctrl = false }

    fun takeAlt() = alt.also { alt = false }
}

/**
 * A key of the row or the extra rows: an Android key code, a control byte sent as
 * is ([control]), or else its label typed as a character. [description] is
 * what TalkBack reads instead of a glyph; [repeats] keys fire again while held
 * (see [repeatWhileHeld]).
 */
enum class ExtraKey(
    val label: String,
    val keyCode: Int?,
    @StringRes val description: Int? = null,
    val repeats: Boolean = false,
    val control: Int? = null,
) {
    ESC("Esc", KeyEvent.KEYCODE_ESCAPE),
    TAB("Tab", KeyEvent.KEYCODE_TAB),
    UP("↑", KeyEvent.KEYCODE_DPAD_UP, R.string.terminal_key_up, repeats = true),
    DOWN("↓", KeyEvent.KEYCODE_DPAD_DOWN, R.string.terminal_key_down, repeats = true),
    LEFT("←", KeyEvent.KEYCODE_DPAD_LEFT, R.string.terminal_key_left, repeats = true),
    RIGHT("→", KeyEvent.KEYCODE_DPAD_RIGHT, R.string.terminal_key_right, repeats = true),
    // The emulator sends DEL (0x7f) for KEYCODE_DEL (Ctrl: BS 0x08, Alt: ESC prefix)
    // and CR for KEYCODE_ENTER (Alt: ESC CR), as a hardware keyboard would.
    BACKSPACE("⌫", KeyEvent.KEYCODE_DEL, R.string.terminal_key_backspace, repeats = true),
    ENTER("⏎", KeyEvent.KEYCODE_ENTER, R.string.terminal_key_enter),
    CTRL_C("^C", null, R.string.terminal_key_ctrl_c, control = 0x03),
    CTRL_D("^D", null, R.string.terminal_key_ctrl_d, control = 0x04),
    CTRL_Z("^Z", null, R.string.terminal_key_ctrl_z, control = 0x1a),
    CTRL_R("^R", null, R.string.terminal_key_ctrl_r, control = 0x12),
    CTRL_L("^L", null, R.string.terminal_key_ctrl_l, control = 0x0c),
    HOME("Home", KeyEvent.KEYCODE_MOVE_HOME),
    END("End", KeyEvent.KEYCODE_MOVE_END),
    PAGE_UP("PgUp", KeyEvent.KEYCODE_PAGE_UP),
    PAGE_DOWN("PgDn", KeyEvent.KEYCODE_PAGE_DOWN),
    PIPE("|", null),
    TILDE("~", null),
    SLASH("/", null),
    DASH("-", null),
}

/** The row under the terminal after ^C and Paste: the keys used all the time, then ⌨. */
private val ROW_KEYS = listOf(
    ExtraKey.LEFT, ExtraKey.UP, ExtraKey.DOWN, ExtraKey.RIGHT,
    ExtraKey.BACKSPACE, ExtraKey.ENTER,
)

/** A place in the extra rows: a key to send, or the Ctrl / Alt toggle. */
private sealed interface Slot {
    data class Send(val key: ExtraKey) : Slot
    data object Ctrl : Slot
    data object Alt : Slot
}

/** The extra rows' first group: Esc, Tab, the modifiers and the ready combos. */
private val COMBO_SLOTS: List<Slot> = listOf(Slot.Send(ExtraKey.ESC), Slot.Send(ExtraKey.TAB), Slot.Ctrl, Slot.Alt) +
    listOf(ExtraKey.CTRL_D, ExtraKey.CTRL_Z, ExtraKey.CTRL_R, ExtraKey.CTRL_L).map(Slot::Send)

/** The extra rows' second group: navigation keys and symbols. */
private val NAV_SLOTS: List<Slot> = listOf(
    ExtraKey.HOME, ExtraKey.END, ExtraKey.PAGE_UP, ExtraKey.PAGE_DOWN,
    ExtraKey.PIPE, ExtraKey.TILDE, ExtraKey.SLASH, ExtraKey.DASH,
).map(Slot::Send)

/** The narrowest an extra key gets before its group wraps onto another line. */
private val EXTRA_KEY_MIN = 36.dp
private val ROW_GAP = 3.dp

/** The main row's keys; the extra rows' are lower, so all of it stays small above the keyboard. */
private val KEY_HEIGHT = 44.dp
private val EXTRA_KEY_HEIGHT = 36.dp

/**
 * [count] keys in lines of at most [perLine], as even as possible: 7 by 6
 * gives 4 + 3 rather than 6 + 1. The sizes of the lines, in order.
 */
fun balancedLines(count: Int, perLine: Int): List<Int> {
    if (count <= 0) return emptyList()
    val per = perLine.coerceAtLeast(1)
    val lines = (count + per - 1) / per
    val base = count / lines
    val longer = count % lines
    return List(lines) { if (it < longer) base + 1 else base }
}

/**
 * One row: ^C, Paste, ← ↑ ↓ → ⌫ ⏎ ⌨, where ⌨ shows and hides the keyboard.
 * While the keyboard is up the extra rows slide in above it: Esc, Tab, Ctrl, Alt,
 * ^D ^Z ^R ^L, Home End PgUp PgDn, | ~ / -. Ctrl and Alt stay held, lit,
 * until the next key.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExtraKeys(modifiers: Modifiers, onKeyboard: (show: Boolean) -> Unit, onPaste: () -> Unit, onKey: (ExtraKey) -> Unit) {
    val colors = AppTheme.colors
    val keyboard = WindowInsets.isImeVisible
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.termBar)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        AnimatedVisibility(
            visible = keyboard,
            enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
        ) {
            ExtraRows(modifiers, onKey, Modifier.padding(bottom = ROW_GAP))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(ROW_GAP)) {
            Key(ExtraKey.CTRL_C, modifiers, onKey)
            PasteKey(onPaste)
            ROW_KEYS.forEach { key -> Key(key, modifiers, onKey) }
            val label = stringResource(R.string.terminal_keyboard)
            Toggle("⌨", keyboard, Modifier.semantics { contentDescription = label }, onChange = onKeyboard)
        }
    }
}

/**
 * The rows shown with the keyboard: on one line when all fit (landscape), else
 * each group on its own, wrapped into even lines when narrow.
 */
@Composable
private fun ExtraRows(modifiers: Modifiers, onKey: (ExtraKey) -> Unit, modifier: Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val perLine = ((maxWidth + ROW_GAP) / (EXTRA_KEY_MIN + ROW_GAP)).toInt()
        val all = COMBO_SLOTS + NAV_SLOTS
        val groups = if (all.size <= perLine) listOf(all) else listOf(COMBO_SLOTS, NAV_SLOTS)
        Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
            groups.forEach { group ->
                var from = 0
                balancedLines(group.size, perLine).forEach { size ->
                    Row(horizontalArrangement = Arrangement.spacedBy(ROW_GAP)) {
                        group.subList(from, from + size).forEach { slot ->
                            when (slot) {
                                is Slot.Send -> Key(slot.key, modifiers, onKey, small = true)
                                Slot.Ctrl -> Toggle("Ctrl", modifiers.ctrl, small = true) { modifiers.ctrl = it }
                                Slot.Alt -> Toggle("Alt", modifiers.alt, small = true) { modifiers.alt = it }
                            }
                        }
                    }
                    from += size
                }
            }
        }
    }
}

/** Pastes the clipboard's text; dimmed, saying why, while there is none. */
@Composable
private fun RowScope.PasteKey(onPaste: () -> Unit) {
    val colors = AppTheme.colors
    val canPaste = clipboardHasText()
    val label = stringResource(R.string.terminal_paste)
    val empty = stringResource(R.string.terminal_clipboard_empty)
    KeyBox(
        Modifier
            .semantics {
                contentDescription = label
                if (!canPaste) stateDescription = empty
            }
            .clickable(enabled = canPaste, role = Role.Button, onClick = onPaste),
        colors.termKey,
    ) {
        Icon(PasteGlyph, contentDescription = null, tint = if (canPaste) colors.onTerm else colors.termMuted, modifier = Modifier.size(18.dp))
    }
}

/** [key] as a button: a tap, or for [ExtraKey.repeats] keys a hold that repeats. */
@Composable
private fun RowScope.Key(key: ExtraKey, modifiers: Modifiers, onKey: (ExtraKey) -> Unit, small: Boolean = false) {
    val description = key.description?.let { stringResource(it) }
    val described = if (description != null) Modifier.semantics { contentDescription = description } else Modifier
    if (key.repeats) {
        RepeatKey(key.label, described, small) {
            // Ctrl / Alt held when the key went down apply to every repeat of it.
            val ctrl = modifiers.ctrl
            val alt = modifiers.alt
            return@RepeatKey {
                modifiers.ctrl = ctrl
                modifiers.alt = alt
                onKey(key)
            }
        }
    } else {
        val colors = AppTheme.colors
        KeyBox(described.clickable(role = Role.Button) { onKey(key) }, colors.termKey, small) {
            KeyLabel(key.label, small)
        }
    }
}

/**
 * A key that fires while held: at once, then after a pause about 15 times a
 * second, until the finger lifts, the touch is cancelled or slides off the
 * key. Lit while held. [press] runs at touch-down and returns what each fire
 * does. TalkBack activates it once, like a tap.
 */
@Composable
private fun RowScope.RepeatKey(label: String, modifier: Modifier, small: Boolean, press: () -> () -> Unit) {
    val colors = AppTheme.colors
    val scope = rememberCoroutineScope()
    var held by remember { mutableStateOf(false) }
    val currentPress by rememberUpdatedState(press)
    val action = modifier
        .semantics(mergeDescendants = true) {
            role = Role.Button
            onClick { currentPress()(); true }
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown()
                held = true
                val repeat = scope.launch { repeatWhileHeld(currentPress()) }
                try {
                    // Null when the touch is cancelled or slides off the key.
                    waitForUpOrCancellation()
                } finally {
                    repeat.cancel()
                    held = false
                }
            }
        }
    KeyBox(action, if (held) colors.termLine else colors.termKey, small) {
        KeyLabel(label, small)
    }
}

@Composable
private fun KeyLabel(label: String, small: Boolean) {
    Text(label, fontSize = if (small) 12.sp else 13.sp, fontWeight = FontWeight.SemiBold, color = AppTheme.colors.onTerm, maxLines = 1)
}

/** A held key: lit in the accent while on. */
@Composable
private fun RowScope.Toggle(label: String, on: Boolean, modifier: Modifier = Modifier, small: Boolean = false, onChange: (Boolean) -> Unit) {
    val colors = AppTheme.colors
    KeyBox(modifier.toggleable(value = on, role = Role.Switch, onValueChange = onChange), if (on) colors.accent else colors.termKey, small) {
        Text(label, fontSize = if (small) 12.sp else 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) colors.onAccent else colors.onTerm, maxLines = 1)
    }
}

@Composable
private fun RowScope.KeyBox(action: Modifier, background: Color, small: Boolean = false, content: @Composable () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .height(if (small) EXTRA_KEY_HEIGHT else KEY_HEIGHT)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .then(action),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** What an extra key does to the terminal, worked out before it is sent. */
sealed interface KeyPress {
    /** A control byte (^C ...) written as is. */
    data class Control(val byte: Int) : KeyPress

    /** A key code with its Ctrl / Alt ([KeyHandler] KEYMOD_*), turned into bytes by the emulator. */
    data class Code(val keyCode: Int, val keyMod: Int) : KeyPress

    /** A character typed as is; the view applies a held Ctrl / Alt itself. */
    data class Typed(val codePoint: Int) : KeyPress
}

/**
 * The press for [key], taking up the held Ctrl / Alt where it applies them
 * (key codes) or drops them (ready combos, like a snippet). A character key
 * leaves them for the view, which reads them as it types.
 */
fun pressFor(key: ExtraKey, modifiers: Modifiers): KeyPress {
    val control = key.control
    val keyCode = key.keyCode
    return when {
        control != null -> {
            modifiers.ctrl = false
            modifiers.alt = false
            KeyPress.Control(control)
        }
        keyCode != null -> {
            var mod = 0
            if (modifiers.takeCtrl()) mod = mod or KeyHandler.KEYMOD_CTRL
            if (modifiers.takeAlt()) mod = mod or KeyHandler.KEYMOD_ALT
            KeyPress.Code(keyCode, mod)
        }
        else -> KeyPress.Typed(key.label.codePointAt(0))
    }
}

/** Sends [key] to the terminal, with Ctrl / Alt if held (and releases them); see [pressFor]. */
fun sendExtraKey(view: TerminalView, key: ExtraKey, modifiers: Modifiers) {
    val session = view.currentSession ?: return
    when (val press = pressFor(key, modifiers)) {
        is KeyPress.Control -> session.writeCodePoint(false, press.byte)
        is KeyPress.Code -> view.handleKeyCode(press.keyCode, press.keyMod)
        // inputCodePoint reads (and releases) the sticky Ctrl / Alt itself.
        is KeyPress.Typed -> view.inputCodePoint(press.codePoint, false, false)
    }
}
