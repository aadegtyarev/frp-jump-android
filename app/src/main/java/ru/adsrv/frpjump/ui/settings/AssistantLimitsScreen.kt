package ru.adsrv.frpjump.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.settings.AssistantLimits
import ru.adsrv.frpjump.ui.AppTheme

/** The entry row of the Limits screen in Settings › Assistant. */
@Composable
internal fun AssistantLimitsEntry(onLimits: () -> Unit) {
    SettingRow(tile = { GlyphTile("#") }, label = stringResource(R.string.assistant_limits_title), hint = stringResource(R.string.assistant_limits_hint),
        modifier = Modifier.testTag("assistant_limits_entry"), onClick = onLimits) { Chevron() }
}

/** Settings › Assistant › Limits: each limit is a stepped slider over its range with the value shown; every change is saved at once. */
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
            LimitRow("limit_after_input_seconds", stringResource(R.string.limit_after_input_seconds), limits.afterInputSeconds, AssistantLimits.AFTER_INPUT_SECONDS_RANGE) { onChange(limits.copy(afterInputSeconds = it)) }
        }
    }
}

@Composable
private fun LimitRow(tag: String, label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag(tag), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = AppTheme.colors.ink, modifier = Modifier.weight(1f))
            Text(value.toString(), color = AppTheme.colors.muted, modifier = Modifier.testTag("${tag}_value"))
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            Slider(
                value = value.toFloat(), onValueChange = { onChange(it.toInt().coerceIn(range.first, range.last)) },
                valueRange = range.first.toFloat()..range.last.toFloat(), steps = (range.last - range.first - 1).coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth().testTag("${tag}_slider").semantics { contentDescription = label; stateDescription = value.toString() },
            )
        }
    }
}
