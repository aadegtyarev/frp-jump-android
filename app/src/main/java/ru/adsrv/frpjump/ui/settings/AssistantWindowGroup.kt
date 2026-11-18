package ru.adsrv.frpjump.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.settings.WindowSplit
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.FORM_BODY_PADDING_V

/**
 * «Окно модели»: the one group of the provider form that splits the model's context window. In order: [top] (the window's size), the bar of the
 * whole window, three steppers (history before shortening, the model's reply, the summary) and [bottom] (the summary model). Each stepper is one
 * piece of the bar; the margin is what is left and is not a setting. The bar (its pieces' labels) is pinned at the top of the form's view while the group's rows scroll
 * under it, until the steppers end, so the bar and the value being changed are in sight together at any font size. [window] is null while the size field holds no number.
 */
@Composable
internal fun AssistantWindowGroup(
    window: Int?, split: WindowSplit, limitedFrom: Int?, enabled: Boolean, scroll: ScrollState, onSplit: (WindowSplit) -> Unit,
    top: @Composable ColumnScope.() -> Unit, bottom: @Composable ColumnScope.() -> Unit,
) {
    val inset = with(LocalDensity.current) { FORM_BODY_PADDING_V.roundToPx() }
    var groupTop by remember { mutableIntStateOf(0) }
        var slotTop by remember { mutableIntStateOf(0) }
    var slotHeight by remember { mutableIntStateOf(0) }
    var cardBottom by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth().onGloballyPositioned { groupTop = it.positionInParent().y.toInt() },
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.window_group_title), style = MaterialTheme.typography.titleMedium, color = AppTheme.colors.ink, modifier = Modifier.semantics { heading() })
        top()
        // The bar keeps its place in the flow; once its top reaches the top of the view it is moved down by the scroll, to the end of the group.
        Box(Modifier.fillMaxWidth().zIndex(1f).onGloballyPositioned { slotTop = it.positionInParent().y.toInt(); slotHeight = it.size.height }) {
            Box(Modifier.fillMaxWidth().offset {
                val stuck = scroll.value - (inset + groupTop + slotTop)
                IntOffset(0, stuck.coerceIn(0, (cardBottom - slotTop - slotHeight).coerceAtLeast(0)))
            }) { WindowBar(window, split) }
        }
        WindowMarks(window, split)
        Surface(color = AppTheme.colors.card, shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().onGloballyPositioned { cardBottom = it.positionInParent().y.toInt() + it.size.height }) {
            Column {
                val historyAtLimit = split.history >= split.historyMax() && split.historyMax() < WindowSplit.HISTORY_RANGE.last
                val replyAtLimit = split.reply >= split.replyMax() && split.replyMax() < WindowSplit.REPLY_RANGE.last
                val summaryAtLimit = split.summary >= split.summaryMax() && split.summaryMax() < WindowSplit.SUMMARY_RANGE.last
                val ruleReply = stringResource(R.string.window_rule_reply, WindowSplit.SUM_MAX)
                val ruleSummary = stringResource(R.string.window_rule_summary, WindowSplit.SUM_MAX)
                val ruleBoth = stringResource(R.string.window_rule_both, WindowSplit.SUM_MAX)
                // Which of the two rules stops the history's plus: the one whose pair is at the whole (both, when both are).
                val historyRule = when {
                    !historyAtLimit -> null
                    split.history + split.reply >= WindowSplit.SUM_MAX && split.history + split.summary >= WindowSplit.SUM_MAX -> ruleBoth
                    split.history + split.reply >= WindowSplit.SUM_MAX -> ruleReply
                    else -> ruleSummary
                }
                LimitRow("window_history", stringResource(R.string.window_history), split.history, WindowSplit.HISTORY_RANGE.first..split.historyMax(), 5, historyRule) {
                    if (enabled) onSplit(split.copy(history = it))
                }
                RowDivider()
                val limited = limitedFrom?.let { stringResource(R.string.window_reply_limited, limitNumber(it), WindowSplit.REPLY_RANGE.last) }
                LimitRow("window_reply", stringResource(R.string.window_reply), split.reply, WindowSplit.REPLY_RANGE.first..split.replyMax(), 1,
                    listOfNotNull(limited, ruleReply.takeIf { replyAtLimit }).joinToString("\n").ifEmpty { null }) { if (enabled) onSplit(split.copy(reply = it)) }
                RowDivider()
                LimitRow("window_summary", stringResource(R.string.window_summary), split.summary, WindowSplit.SUMMARY_RANGE.first..split.summaryMax(), 5,
                    ruleSummary.takeIf { summaryAtLimit }) { if (enabled) onSplit(split.copy(summary = it)) }
            }
        }
        bottom()
    }
}

/** The pieces' colours: calm, from the theme (light and dark), none of them an alarm colour. */
private class BarColors(val history: Color, val reply: Color, val margin: Color, val line: Color, val after: Color, val frame: Color)

@Composable
private fun barColors(): BarColors {
    val c = AppTheme.colors
    return BarColors(history = c.accent, reply = Color(c.palette.profiles.getValue("blue")), margin = c.tile, line = c.ink, after = c.onAccent, frame = c.outline)
}

/**
 * The whole window as one bar: «История» | «Ответ» | «Запас», a solid line where the shortening starts (history + reply) and a dashed one at the size
 * of a summary inside the history (where the history lands after shortening). The labels below carry the shares and tokens; TalkBack gets the same in words.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WindowBar(window: Int?, split: WindowSplit, modifier: Modifier = Modifier) {
    val colors = barColors()
    val style = MaterialTheme.typography.bodyLarge
    @Composable fun tokens(percent: Int): String? = window?.let { limitNumber(WindowSplit.tokens(it, percent)) }
    @Composable fun piece(share: Int, name: Int) = stringResource(name, share) + (tokens(share)?.let { stringResource(R.string.window_bar_tokens, it) } ?: "")
    val history = piece(split.history, R.string.window_bar_history)
    val reply = piece(split.reply, R.string.window_bar_reply)
    val margin = piece(split.margin, R.string.window_bar_margin)
    @Composable fun part(share: Int) = tokens(share)?.let { stringResource(R.string.window_bar_piece_tokens, it) } ?: ""
    val description = stringResource(R.string.window_bar_description, window?.let { stringResource(R.string.window_bar_window_tokens, limitNumber(it)) } ?: "",
        split.history, part(split.history), split.reply, part(split.reply), split.margin, part(split.margin), split.compactAt, split.summary)
    Surface(color = AppTheme.colors.card, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, colors.frame), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp).testTag("window_bar").clearAndSetSemantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth().height(28.dp)) {
                Row(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).border(1.dp, colors.frame, RoundedCornerShape(8.dp))) {
                    Box(Modifier.weight(split.history.coerceAtLeast(1).toFloat()).fillMaxHeight().background(colors.history).testTag("window_bar_history"))
                    Box(Modifier.weight(split.reply.coerceAtLeast(1).toFloat()).fillMaxHeight().background(colors.reply).testTag("window_bar_reply"))
                    Box(Modifier.weight(split.margin.coerceAtLeast(1).toFloat()).fillMaxHeight().background(colors.margin).testTag("window_bar_margin"))
                }
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 2.dp.toPx()
                    val x = size.width * split.compactAt / 100f
                    drawLine(colors.line, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke * 1.5f)
                    val y = size.width * split.summary / 100f
                    drawLine(colors.after, Offset(y, 0f), Offset(y, size.height), strokeWidth = stroke,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Legend(colors.history, history, style, "window_label_history")
                Legend(colors.reply, reply, style, "window_label_reply")
                Legend(colors.margin, margin, style, "window_label_margin", frame = colors.frame)
            }
        }
    }
}

/** What the two marks on the bar mean. Not pinned with the bar (it would take the view at a large font); it scrolls with the group. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WindowMarks(window: Int?, split: WindowSplit) {
    val colors = barColors()
    val style = MaterialTheme.typography.bodyLarge
    val compact = stringResource(R.string.window_bar_compact, split.compactAt)
    val tokens = window?.let { stringResource(R.string.window_bar_tokens, limitNumber(WindowSplit.tokens(it, split.summary))) }.orEmpty()
    val after = stringResource(R.string.window_bar_after, split.summary) + tokens
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Mark(colors.line, null, compact, style, "window_label_compact")
        Mark(colors.after, colors.history, after, style, "window_label_after")
    }
}

@Composable
private fun Legend(color: Color, text: String, style: androidx.compose.ui.text.TextStyle, tag: String, frame: Color? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag(tag)) {
        Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(color).let { if (frame != null) it.border(1.dp, frame, RoundedCornerShape(4.dp)) else it })
        Text(text, style = style, color = AppTheme.colors.ink)
    }
}

@Composable
private fun Mark(color: Color, chip: Color?, text: String, style: androidx.compose.ui.text.TextStyle, tag: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag(tag)) {
        // [chip]: the dashed mark, drawn on the history's colour as it is on the bar; none: the solid line.
        Canvas(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).let { if (chip != null) it.background(chip) else it }) {
            val x = size.width / 2
            drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.5.dp.toPx(),
                pathEffect = if (chip != null) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())) else null)
        }
        Text(text, style = style, color = AppTheme.colors.muted)
    }
}
