package ru.adsrv.frpjump.ui.ssh

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import com.termux.terminal.KeyHandler
import com.termux.view.TerminalView
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ui.AppTheme

/** Sticky Ctrl / Alt from the extra-keys row: apply to the next key, then release. */
@Stable
class Modifiers {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)

    fun takeCtrl() = ctrl.also { ctrl = false }

    fun takeAlt() = alt.also { alt = false }
}

/** A key of the extra rows: an Android key code, or a character typed as is. */
enum class ExtraKey(val label: String, val keyCode: Int?) {
    ESC("Esc", KeyEvent.KEYCODE_ESCAPE),
    TAB("Tab", KeyEvent.KEYCODE_TAB),
    UP("↑", KeyEvent.KEYCODE_DPAD_UP),
    DOWN("↓", KeyEvent.KEYCODE_DPAD_DOWN),
    LEFT("←", KeyEvent.KEYCODE_DPAD_LEFT),
    RIGHT("→", KeyEvent.KEYCODE_DPAD_RIGHT),
    HOME("Home", KeyEvent.KEYCODE_MOVE_HOME),
    END("End", KeyEvent.KEYCODE_MOVE_END),
    PAGE_UP("PgUp", KeyEvent.KEYCODE_PAGE_UP),
    PAGE_DOWN("PgDn", KeyEvent.KEYCODE_PAGE_DOWN),
    PIPE("|", null),
    TILDE("~", null),
    SLASH("/", null),
    DASH("-", null),
}

/** "⋯" opens these above the main row. */
private val MORE_KEYS = listOf(
    ExtraKey.LEFT, ExtraKey.RIGHT, ExtraKey.HOME, ExtraKey.END, ExtraKey.PAGE_UP,
    ExtraKey.PAGE_DOWN, ExtraKey.PIPE, ExtraKey.TILDE, ExtraKey.SLASH, ExtraKey.DASH,
)

/**
 * The mockup's keys: Esc Tab Ctrl Alt ↑ ↓ ⋯. Ctrl and Alt stay held (lit)
 * until the next key; "⋯" shows a second row with the rest.
 */
@Composable
fun ExtraKeys(modifiers: Modifiers, onKey: (ExtraKey) -> Unit) {
    val colors = AppTheme.colors
    var more by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.termBar)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (more) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                MORE_KEYS.forEach { key -> Key(key.label, small = true) { onKey(key) } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Key(ExtraKey.ESC.label) { onKey(ExtraKey.ESC) }
            Key(ExtraKey.TAB.label) { onKey(ExtraKey.TAB) }
            Toggle("Ctrl", modifiers.ctrl) { modifiers.ctrl = it }
            Toggle("Alt", modifiers.alt) { modifiers.alt = it }
            Key(ExtraKey.UP.label) { onKey(ExtraKey.UP) }
            Key(ExtraKey.DOWN.label) { onKey(ExtraKey.DOWN) }
            val moreLabel = stringResource(R.string.terminal_more_keys)
            Toggle("⋯", more, Modifier.semantics { contentDescription = moreLabel }) { more = it }
        }
    }
}

@Composable
private fun RowScope.Key(label: String, small: Boolean = false, onClick: () -> Unit) {
    val colors = AppTheme.colors
    KeyBox(Modifier.clickable(role = Role.Button, onClick = onClick), colors.termKey) {
        Text(label, fontSize = if (small) 12.sp else 13.sp, fontWeight = FontWeight.SemiBold, color = colors.onTerm, maxLines = 1)
    }
}

/** A held key: lit in the accent while on. */
@Composable
private fun RowScope.Toggle(label: String, on: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    val colors = AppTheme.colors
    KeyBox(modifier.toggleable(value = on, role = Role.Switch, onValueChange = onChange), if (on) colors.accent else colors.termKey) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) colors.onAccent else colors.onTerm, maxLines = 1)
    }
}

@Composable
private fun RowScope.KeyBox(action: Modifier, background: androidx.compose.ui.graphics.Color, content: @Composable () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .then(action),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Sends [key] to the terminal, with Ctrl / Alt if held (and releases them). */
fun sendExtraKey(view: TerminalView, key: ExtraKey, modifiers: Modifiers) {
    if (view.currentSession == null) return
    val keyCode = key.keyCode
    if (keyCode != null) {
        var mod = 0
        if (modifiers.takeCtrl()) mod = mod or KeyHandler.KEYMOD_CTRL
        if (modifiers.takeAlt()) mod = mod or KeyHandler.KEYMOD_ALT
        view.handleKeyCode(keyCode, mod)
    } else {
        // inputCodePoint reads (and releases) the sticky Ctrl / Alt itself.
        view.inputCodePoint(key.label.codePointAt(0), false, false)
    }
}
