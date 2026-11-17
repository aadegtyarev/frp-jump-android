package ru.adsrv.frpjump.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.settings.AssistantLimits
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.ClockGlyph

/** The entry row of the Limits screen in Settings › Assistant. */
@Composable
internal fun AssistantLimitsEntry(onLimits: () -> Unit) {
    SettingRow(tile = { IconTile(ClockGlyph) }, label = stringResource(R.string.assistant_limits_title), hint = stringResource(R.string.assistant_limits_hint),
        modifier = Modifier.testTag("assistant_limits_entry"), onClick = onLimits) { Chevron() }
}

/** Settings › Assistant › Limits: each limit is a row "label  −  value  +" over its range, saved at once; a button is off at its end of the range. */
@Composable
internal fun AssistantLimitsScreen(limits: AssistantLimits, onChange: (AssistantLimits) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(AppTheme.colors.ground).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row {
            IconButton(onClick = onBack, modifier = Modifier.testTag("limits_back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.assistant_limits_title), style = MaterialTheme.typography.titleLarge)
        }
        Group(null) {
            LimitRow("limit_waits_at_once", stringResource(R.string.limit_waits_at_once), limits.waitsAtOnce, AssistantLimits.WAITS_AT_ONCE_RANGE) { onChange(limits.copy(waitsAtOnce = it)) }
            RowDivider()
            LimitRow("limit_wait_max_minutes", stringResource(R.string.limit_wait_max_minutes), limits.waitMaxMinutes, AssistantLimits.WAIT_MAX_MINUTES_RANGE) { onChange(limits.copy(waitMaxMinutes = it)) }
            RowDivider()
            LimitRow("limit_event_replies", stringResource(R.string.limit_event_replies), limits.eventReplies, AssistantLimits.EVENT_REPLIES_RANGE) { onChange(limits.copy(eventReplies = it)) }
            RowDivider()
            LimitRow("limit_sessions_at_once", stringResource(R.string.limit_sessions_at_once), limits.sessionsAtOnce, AssistantLimits.SESSIONS_AT_ONCE_RANGE) { onChange(limits.copy(sessionsAtOnce = it)) }
        }
    }
}

/** The label (the row reads it with its value), then "−" and "+" around the value, in the row's own text size. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun LimitRow(tag: String, label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    val style = MaterialTheme.typography.bodyLarge
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = style, color = AppTheme.colors.ink, modifier = Modifier.weight(1f).semantics { contentDescription = "$label: $value" })
        StepButton("−", stringResource(R.string.limit_decrease), value > range.first, "${tag}_minus") { onChange((value - 1).coerceAtLeast(range.first)) }
        Text(value.toString(), style = style, color = AppTheme.colors.ink, textAlign = TextAlign.Center,
            modifier = Modifier.defaultMinSize(minWidth = 40.dp).testTag("${tag}_value").semantics { invisibleToUser() })
        StepButton("+", stringResource(R.string.limit_increase), value < range.last, "${tag}_plus") { onChange((value + 1).coerceAtMost(range.last)) }
    }
}

/**
 * A 48 dp round button: a tap steps once; held, it steps again after a moment and then every fraction of a second until let go. TalkBack reads
 * [description] and gets the plain click action; at the end of the range it is dimmed and off.
 */
@Composable
private fun StepButton(glyph: String, description: String, enabled: Boolean, tag: String, onStep: () -> Unit) {
    val step by rememberUpdatedState(onStep)
    val scope = rememberCoroutineScope()
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(AppTheme.colors.tile).alpha(if (enabled) 1f else 0.38f).testTag(tag)
            .pointerInput(enabled) {
                if (enabled) detectTapGestures(onPress = {
                    step()
                    val repeat = scope.launch { delay(REPEAT_START_MS); while (true) { step(); delay(REPEAT_MS) } }
                    try { tryAwaitRelease() } finally { repeat.cancel() }
                })
            }
            .semantics { role = Role.Button; contentDescription = description; if (!enabled) disabled() else onClick(description) { step(); true } },
        contentAlignment = Alignment.Center,
    ) { Text(glyph, style = MaterialTheme.typography.titleLarge, color = AppTheme.colors.ink, modifier = Modifier.clearAndSetSemantics {}) }
}

/** How long a held button waits before it repeats, and the pause between repeats. */
private const val REPEAT_START_MS = 450L
private const val REPEAT_MS = 90L
