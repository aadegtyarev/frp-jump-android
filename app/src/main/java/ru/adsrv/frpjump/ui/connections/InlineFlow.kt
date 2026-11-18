package ru.adsrv.frpjump.ui.connections

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

private const val SEPARATOR_ID = "inline-flow-separator"

/** Marks a child of [InlineFlow] as the separator that belongs to the next child ([InlineFlow] explains). */
fun Modifier.flowSeparator(): Modifier = layoutId(SEPARATOR_ID)

/** A separator for [InlineFlow]: the dot between a state and an address, hidden from TalkBack by the caller. */
@Composable
fun FlowSeparator(content: @Composable () -> Unit) {
    Box(Modifier.flowSeparator()) { content() }
}

/**
 * A wrapping row whose items break only between items. A child built with [FlowSeparator] belongs to the child after it: the two sit on one
 * line as "separator, item", or, where they do not fit after the previous item, the item goes to the start of the next line and the
 * separator is left out, so a separator never ends a line and never starts one. The layout measures; no font-scale threshold.
 */
@Composable
fun InlineFlow(
    modifier: Modifier = Modifier,
    horizontalSpacing: Dp = 6.dp,
    verticalSpacing: Dp = 2.dp,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = horizontalSpacing.roundToPx()
        val lineGap = verticalSpacing.roundToPx()
        val maxWidth = constraints.maxWidth
        val loose = Constraints(maxWidth = maxWidth)

        data class Item(val item: Placeable, val separator: Placeable?)
        val items = mutableListOf<Item>()
        var pending: Placeable? = null
        for (m in measurables) {
            val p = m.measure(loose)
            if (m.layoutId == SEPARATOR_ID) pending = p else { items += Item(p, pending); pending = null }
        }

        class Put(val placeable: Placeable, val x: Int)
        val lines = mutableListOf<MutableList<Put>>()
        var line = mutableListOf<Put>()
        var x = 0
        for ((p, sep) in items) {
            if (line.isNotEmpty()) {
                val joined = if (sep != null) gap + sep.width + gap + p.width else gap + p.width
                if (x + joined <= maxWidth) {
                    var at = x + gap
                    if (sep != null) { line += Put(sep, at); at += sep.width + gap }
                    line += Put(p, at)
                    x = at + p.width
                    continue
                }
                lines += line
                line = mutableListOf()
            }
            line += Put(p, 0)
            x = p.width
        }
        if (line.isNotEmpty()) lines += line

        val heights = lines.map { l -> l.maxOf { it.placeable.height } }
        val width = lines.maxOfOrNull { l -> l.maxOf { it.x + it.placeable.width } } ?: 0
        val height = heights.sum() + max(0, lines.size - 1) * lineGap
        layout(width.coerceIn(constraints.minWidth, max(constraints.minWidth, maxWidth)), height.coerceIn(constraints.minHeight, max(constraints.minHeight, constraints.maxHeight))) {
            var y = 0
            for ((i, l) in lines.withIndex()) {
                for (put in l) put.placeable.placeRelative(put.x, y + (heights[i] - put.placeable.height) / 2)
                y += heights[i] + lineGap
            }
        }
    }
}
