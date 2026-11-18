package ru.adsrv.frpjump.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.settings.WindowSplit
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.FORM_BODY_PADDING_V

/**
 * Where the group's bar sits in the form's scroll, so the form can show the bar above the scroll while the steppers are being used: from the bar's place
 * ([slotY]) to the end of the steppers' card ([endY], measured as if the place were empty, so it does not move when the bar changes places).
 */
internal class WindowPin {
    var slotY by mutableIntStateOf(Int.MAX_VALUE)
    var endY by mutableIntStateOf(Int.MAX_VALUE)
    /** The bar's height in its place (last seen), and the strip's height (it can be smaller: see [level]). */
    var barHeight by mutableIntStateOf(0)
    var stripHeight by mutableIntStateOf(0)
    /** What the layout gains or loses when the bar goes to the strip and back: the strip's height (the place keeps the rest of the bar's height as an empty space). */
    val shift: Int get() = minOf(barHeight, stripHeight)
    /** What the strip shows when it has to be small: 0 the bar, its labels and the rule lines; 1 without the labels; 2 the bar alone (the rule lines go under their rows). */
    var level by mutableIntStateOf(0)
    val rulesUnderRows: Boolean get() = level >= 2
    fun active(scrollY: Int): Boolean = scrollY >= slotY && scrollY < endY
}

/** The one line each rule that stops a «+» or «−» says, shown with the bar so the reason is in sight with the disabled button; null when no rule stops that row. */
internal class WindowRules(val history: String?, val reply: String?, val summary: String?) {
    val lines: List<String> get() = listOfNotNull(history, reply, summary).distinct()
}

@Composable
internal fun windowRules(split: WindowSplit): WindowRules {
    val sums = WindowSplit.SUM_MAX
    val ruleReply = stringResource(R.string.window_rule_reply, sums)
    val ruleSummary = stringResource(R.string.window_rule_summary, sums)
    val ruleBoth = stringResource(R.string.window_rule_both, sums)
    val ruleGap = stringResource(R.string.window_rule_gap, WindowSplit.SUMMARY_GAP)
    val replyFull = split.history + split.reply >= sums
    val summaryFull = split.history + split.summary >= sums
    // The history's plus is stopped by the two sums (the one whose pair is at the whole, both when both are); its minus by the summary's gap.
    val historyAtMax = split.history >= split.historyMax() && split.historyMax() < WindowSplit.HISTORY_RANGE.last
    val historyAtMin = split.history <= split.historyMin() && split.historyMin() > WindowSplit.HISTORY_RANGE.first
    val replyAtMax = split.reply >= split.replyMax() && split.replyMax() < WindowSplit.REPLY_RANGE.last
    val summaryAtMax = split.summary >= split.summaryMax() && split.summaryMax() < WindowSplit.SUMMARY_RANGE.last
    return WindowRules(
        history = when {
            historyAtMax -> if (replyFull && summaryFull) ruleBoth else if (replyFull) ruleReply else ruleSummary
            historyAtMin -> ruleGap
            else -> null
        },
        reply = ruleReply.takeIf { replyAtMax },
        // The gap stops the summary when the history leaves less than the sum rule does.
        summary = (if (split.history - WindowSplit.SUMMARY_GAP <= WindowSplit.SUM_MAX - split.history) ruleGap else ruleSummary).takeIf { summaryAtMax },
    )
}

/**
 * «Окно модели»: the one group of the provider form that splits the model's context window. In order: [top] (the window's size), the bar of the
 * whole window, three steppers (history before shortening, the model's reply, the summary) and [bottom] (the summary model). Each stepper is one
 * piece of the bar; the margin is what is left and is not a setting. While the steppers are in use ([pinned], decided by the form) the form shows the bar
 * in its own strip above the scrolling part ([pin] tells where its place is) and the place is empty: the rows scroll below the bar and are never under
 * it, so touch, TalkBack focus and bring-into-view all stay clear of it. The rules that stop a button are said in the bar's card, not under the rows.
 * [window] is null while the size field holds no number.
 */
@Composable
internal fun AssistantWindowGroup(
    window: Int?, split: WindowSplit, rules: WindowRules, limitedFrom: Int?, enabled: Boolean, pin: WindowPin, pinned: Boolean, onSplit: (WindowSplit) -> Unit,
    top: @Composable ColumnScope.() -> Unit, bottom: @Composable ColumnScope.() -> Unit,
) {
    val inset = with(LocalDensity.current) { FORM_BODY_PADDING_V.roundToPx() }
    var groupTop by remember { mutableIntStateOf(0) }
    val currentPinned by rememberUpdatedState(pinned)
    Column(Modifier.fillMaxWidth().onGloballyPositioned { groupTop = it.positionInParent().y.toInt() },
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.window_group_title), style = MaterialTheme.typography.titleMedium, color = AppTheme.colors.ink, modifier = Modifier.semantics { heading() })
        top()
        // The bar's place: the bar itself, or nothing while the form shows it above the scroll (the place keeps its top, so the form knows where to switch back).
        Box(Modifier.fillMaxWidth().onGloballyPositioned {
            pin.slotY = inset + groupTop + it.positionInParent().y.toInt()
            if (!currentPinned) pin.barHeight = it.size.height
        }) {
            if (!pinned) WindowBar(window, split, rules.lines)
            // The strip may be smaller than the bar (a small view at a large font): the place keeps the difference, so the rest of the form does not move when the bar changes places.
            else Spacer(Modifier.height(with(LocalDensity.current) { (pin.barHeight - pin.stripHeight).coerceAtLeast(0).toDp() }))
        }
        WindowMarks(window, split)
        Surface(color = AppTheme.colors.card, shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth().onGloballyPositioned {
                // The end of the steppers in the layout with the bar IN ITS PLACE (the layout the form scrolls through before the strip appears): while the bar is in the strip, add what it took out.
                pin.endY = inset + groupTop + it.positionInParent().y.toInt() + it.size.height + (if (currentPinned) pin.shift else 0)
            }) {
            Column {
                // The rule lines are in the bar's card; only when the strip had to drop them (a small view at a large font) they are said under their rows.
                val under = pinned && pin.rulesUnderRows
                LimitRow("window_history", stringResource(R.string.window_history), split.history, split.historyMin()..split.historyMax(), 5, rules.history.takeIf { under }) {
                    if (enabled) onSplit(split.copy(history = it))
                }
                RowDivider()
                val limited = limitedFrom?.let { stringResource(R.string.window_reply_limited, limitNumber(it), WindowSplit.REPLY_RANGE.last) }
                LimitRow("window_reply", stringResource(R.string.window_reply), split.reply, WindowSplit.REPLY_RANGE.first..split.replyMax(), 1,
                    listOfNotNull(limited, rules.reply.takeIf { under }).joinToString("\n").ifEmpty { null }) {
                    if (enabled) onSplit(split.copy(reply = it))
                }
                RowDivider()
                LimitRow("window_summary", stringResource(R.string.window_summary), split.summary, WindowSplit.SUMMARY_RANGE.first..split.summaryMax(), 5, rules.summary.takeIf { under }) {
                    if (enabled) onSplit(split.copy(summary = it))
                }
            }
        }
        bottom()
    }
}

/**
 * The bar in the form's strip above the scroll. At a large font in a small view (a phone on its side) the whole card (bar, labels, rule lines) would take
 * the view the rows need, so it gives up first the labels (level 1), then the rule lines too (level 2: said under their rows): the first that fits in
 * [maxHeight] (the form's room for the strip), from the texts' measured heights at the strip's width [width].
 */
@Composable
internal fun WindowStrip(window: Int?, split: WindowSplit, rules: List<String>, pin: WindowPin, maxHeight: androidx.compose.ui.unit.Dp, width: androidx.compose.ui.unit.Dp) {
    val density = LocalDensity.current
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val style = MaterialTheme.typography.bodyLarge
    val pieces = windowPieces(window, split)
    val level = if (maxHeight == androidx.compose.ui.unit.Dp.Infinity || width <= 0.dp) 0 else with(density) {
        val inner = (width - 22.dp).roundToPx().coerceAtLeast(1)
        fun textH(text: String, maxW: Int) = measurer.measure(text, style, constraints = androidx.compose.ui.unit.Constraints(maxWidth = maxW)).size.height
        val bar = (28.dp + 22.dp).roundToPx() + 8.dp.roundToPx()           // the bar, the card's padding and border, the gap under the title bar
        val gap = 10.dp.roundToPx()
        val rulesH = rules.sumOf { gap + textH(it, inner) }
        // The legend: its items in a row that wraps (each: a swatch, a gap and the text).
        val swatch = (14.dp + 6.dp).roundToPx(); val between = 16.dp.roundToPx(); val rowGap = 4.dp.roundToPx()
        var rowsH = 0; var rowW = 0; var rowH = 0
        for (text in pieces) {
            val w = measurer.measure(text, style, softWrap = false, maxLines = 1).size.width + swatch
            val h = textH(text, (inner - swatch).coerceAtLeast(1))
            if (rowW > 0 && rowW + between + w > inner) { rowsH += rowH + rowGap; rowW = 0; rowH = 0 }
            rowW += (if (rowW > 0) between else 0) + w; rowH = maxOf(rowH, h)
        }
        rowsH += rowH
        val max = maxHeight.roundToPx()
        when { bar + gap + rowsH + rulesH <= max -> 0; bar + rulesH <= max -> 1; else -> 2 }
    }
    androidx.compose.runtime.SideEffect { pin.level = level }
    val topGap = with(density) { 8.dp.roundToPx() }
    // Level 0 shows the labels and the rules; 1 the rules; 2 the bar alone.
    WindowBar(window, split, rules, labels = level == 0, ruleLines = level <= 1, modifier = Modifier.onSizeChanged { pin.stripHeight = it.height + topGap })
}

/** The three labels of the bar's pieces («История · 75 % · 197K»). */
@Composable
internal fun windowPieces(window: Int?, split: WindowSplit): List<String> {
    val decimal = java.text.DecimalFormatSymbols.getInstance(androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).decimalSeparator
    // Labels carry the tokens short (197K) so that each fits one line at a large font; the description in words keeps the whole numbers.
    fun compact(percent: Int): String? = window?.let { WindowSplit.compactTokens(WindowSplit.tokens(it, percent), decimal) }
    @Composable fun piece(share: Int, name: Int) = stringResource(name, share) + (compact(share)?.let { stringResource(R.string.window_bar_tokens, it) } ?: "")
    return listOf(piece(split.history, R.string.window_bar_history), piece(split.reply, R.string.window_bar_reply), piece(split.margin, R.string.window_bar_margin))
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
internal fun WindowBar(window: Int?, split: WindowSplit, rules: List<String> = emptyList(), modifier: Modifier = Modifier, labels: Boolean = true, ruleLines: Boolean = true) {
    val colors = barColors()
    val style = MaterialTheme.typography.bodyLarge
    @Composable fun tokens(percent: Int): String? = window?.let { limitNumber(WindowSplit.tokens(it, percent)) }
    val (history, reply, margin) = windowPieces(window, split)
    @Composable fun part(share: Int) = tokens(share)?.let { stringResource(R.string.window_bar_piece_tokens, it) } ?: ""
    val description = stringResource(R.string.window_bar_description, window?.let { stringResource(R.string.window_bar_window_tokens, limitNumber(it)) } ?: "",
        split.history, part(split.history), split.reply, part(split.reply), split.margin, part(split.margin), split.compactAt, split.summary)
    Surface(color = AppTheme.colors.card, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, colors.frame), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 10.dp).testTag("window_bar").clearAndSetSemantics { contentDescription = (listOf(description) + rules).joinToString(" ") }, verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            if (labels) FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Legend(colors.history, history, style, "window_label_history")
                Legend(colors.reply, reply, style, "window_label_reply")
                Legend(colors.margin, margin, style, "window_label_margin", frame = colors.frame)
            }
            // Why a «+» or «−» is off, in sight with the buttons.
            if (ruleLines) rules.forEach { Text(it, style = style, color = AppTheme.colors.ink, modifier = Modifier.testTag("window_rule_line")) }
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
    val decimal = java.text.DecimalFormatSymbols.getInstance(androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).decimalSeparator
    val tokens = window?.let { stringResource(R.string.window_bar_tokens, WindowSplit.compactTokens(WindowSplit.tokens(it, split.summary), decimal)) }.orEmpty()
    val after = stringResource(R.string.window_bar_after, split.summary) + tokens
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Mark(colors.line, null, compact, style, "window_label_compact")
        Mark(colors.after, colors.history, after, style, "window_label_after")
    }
}

@Composable
private fun Legend(color: Color, text: String, style: androidx.compose.ui.text.TextStyle, tag: String, frame: Color? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag(tag)) {
        Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(color).let { if (frame != null) it.border(1.dp, frame, RoundedCornerShape(4.dp)) else it })
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
