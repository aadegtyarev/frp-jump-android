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
import ru.adsrv.frpjump.assistant.AssistantProviderSettings
import ru.adsrv.frpjump.assistant.ContextBudget
import ru.adsrv.frpjump.settings.AssistantLimits
import ru.adsrv.frpjump.settings.LIMITS
import ru.adsrv.frpjump.settings.LimitGroup
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
internal fun AssistantLimitsScreen(limits: AssistantLimits, onChange: (AssistantLimits) -> Unit, onBack: () -> Unit, provider: AssistantProviderSettings? = null) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(AppTheme.colors.ground).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row {
            IconButton(onClick = onBack, modifier = Modifier.testTag("limits_back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.assistant_limits_title), style = MaterialTheme.typography.titleLarge)
        }
        for (group in LimitGroup.entries) {
            Group(stringResource(limitGroupTitle(group))) {
                LIMITS.filter { it.group == group }.forEachIndexed { index, spec ->
                    if (index > 0) RowDivider()
                    // The whole reply is raised to one call plus one model answer when it is set lower: the row shows what is in force and says why.
                    val effective = if (spec.key == "reply_s") (limits.replyLimitMs() / 1000).toInt() else spec.get(limits)
                    val raised = if (spec.key == "reply_s" && effective > spec.get(limits)) stringResource(R.string.limit_reply_raised, effective) else null
                    // The whole reply cannot go below one call plus one model answer (live): its minus is off there, so it never looks dead above it.
                    // The fill that starts a summary and the summary's size share the window: each one's plus stops where the pair would pass the whole, and says so.
                    val range = when (spec.key) {
                        "reply_s" -> maxOf(spec.range.first, limits.toolTimeoutSeconds + limits.modelTimeoutSeconds)..spec.range.last
                        "summary_percent" -> spec.range.first..limits.summaryPercentMax()
                        "summary_size_percent" -> spec.range.first..limits.summarySizeMax()
                        else -> spec.range
                    }
                    val pair = if (range.last < spec.range.last) stringResource(R.string.limit_summary_pair, AssistantLimits.SUMMARY_PAIR_MAX) else null
                    // The summary is never bigger than the model's answer limit (Settings › Assistant provider): the row says when that is what limits it.
                    val capped = if (spec.key == "summary_size_percent" && provider != null) ContextBudget(provider.contextTokens, provider.outputTokens, limits).takeIf { it.summaryCapped }
                        ?.let { stringResource(R.string.limit_summary_capped, limitNumber(it.summaryTokens)) } else null
                    val hint = listOfNotNull(raised, pair, capped).joinToString("\n").ifEmpty { null }
                    LimitRow("limit_${spec.key}", stringResource(limitLabel(spec.key)), effective, range, spec.step, hint) { onChange(spec.set(limits, it)) }
                }
            }
        }
    }
}

private fun limitGroupTitle(group: LimitGroup) = when (group) {
    LimitGroup.ASSISTANT -> R.string.limits_group_assistant
    LimitGroup.WAITS -> R.string.limits_group_waits
    LimitGroup.TERMINAL -> R.string.limits_group_terminal
    LimitGroup.TUNNELS -> R.string.limits_group_tunnels
}

private fun limitLabel(key: String) = when (key) {
    "tool_timeout_s" -> R.string.limit_tool_timeout
    "model_timeout_s" -> R.string.limit_model_timeout
    "reply_s" -> R.string.limit_reply_seconds
    "steps_per_reply" -> R.string.limit_steps
    "calls_per_step" -> R.string.limit_calls
    "skills_max" -> R.string.limit_skills_max
    "skill_chars" -> R.string.limit_skill_chars
    "skills_list_chars" -> R.string.limit_skills_list
    "tool_output_chars" -> R.string.limit_tool_output
    "reply_chars" -> R.string.limit_reply_chars
    "summary_percent" -> R.string.limit_summary_percent
    "summary_size_percent" -> R.string.limit_summary_size
    "fresh_tool_percent" -> R.string.limit_fresh_tool
    "waits_at_once" -> R.string.limit_waits_at_once
    "wait_max_minutes" -> R.string.limit_wait_max_minutes
    "event_replies" -> R.string.limit_event_replies
    "sessions_at_once" -> R.string.limit_sessions_at_once
    "input_chars" -> R.string.limit_input_chars
    "keys_at_once" -> R.string.limit_keys
    "read_lines_max" -> R.string.limit_read_lines
    "tunnel_wait_s" -> R.string.limit_tunnel_wait
    else -> R.string.limit_reconnect
}

/** The label (the row reads it with its value), then "−" and "+" around the value, in the row's own text size. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun LimitRow(tag: String, label: String, value: Int, range: IntRange, step: Int, hint: String?, onChange: (Int) -> Unit) {
    val style = MaterialTheme.typography.bodyLarge
    val shown = limitNumber(value)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
    Row(Modifier.fillMaxWidth().testTag(tag), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = style, color = AppTheme.colors.ink, modifier = Modifier.weight(1f).semantics { contentDescription = "$label: $shown" })
        StepButton("−", stringResource(R.string.limit_decrease), value > range.first, "${tag}_minus") { onChange((value - step).coerceAtLeast(range.first)) }
        Text(shown, style = style, color = AppTheme.colors.ink, textAlign = TextAlign.Center,
            modifier = Modifier.defaultMinSize(minWidth = 56.dp).testTag("${tag}_value").semantics { invisibleToUser() })
        StepButton("+", stringResource(R.string.limit_increase), value < range.last, "${tag}_plus") { onChange((value + step).coerceAtMost(range.last)) }
    }
    if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = AppTheme.colors.muted, modifier = Modifier.testTag("${tag}_hint"))
    }
}

/** A limit's number with the locale's grouping (128,000 / 128 000). */
@Composable
internal fun limitNumber(value: Int): String {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    return java.text.NumberFormat.getIntegerInstance(locale).format(value)
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
