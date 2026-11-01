package ru.adsrv.frpjump.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.adsrv.frpjump.devices.DeviceIcon
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.glyph

/**
 * A device's icon on a tile ([icon] null: the generic glyph), its state as
 * a [dot] in the corner (null: no dot), ringed in [ring], the colour around
 * the tile. The Devices list draws it at 40 dp, the Connections tab's
 * headers smaller. Decorative: the row or header around it says the icon.
 */
@Composable
fun DeviceTile(icon: DeviceIcon?, dot: Color?, size: Dp = 40.dp, ring: Color = AppTheme.colors.card) {
    val colors = AppTheme.colors
    val scale = size.value / 40f
    Box(Modifier.size(size)) {
        Box(
            Modifier
                .size(size)
                .background(colors.tile, RoundedCornerShape(12.dp * scale)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon.glyph, contentDescription = null, tint = colors.ink, modifier = Modifier.size(22.dp * scale))
        }
        if (dot != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 2.dp * scale, y = 2.dp * scale)
                    .size(14.dp * scale)
                    .border(2.dp * scale, ring, CircleShape)
                    .padding(2.dp * scale)
                    .background(dot, CircleShape),
            )
        }
    }
}

/**
 * A row's second line: [label] in front of [state] («Щиток, гараж · Онлайн
 * · ⛓ 22»). The label is never cut: when both do not fit on one line, it
 * takes its own line or lines, wrapped, and the state goes below. Without a
 * label, only the state.
 */
@Composable
fun LabelAndState(label: String?, state: @Composable () -> Unit, modifier: Modifier = Modifier) {
    if (label == null) {
        Box(modifier) { state() }
        return
    }
    val colors = AppTheme.colors
    Layout(
        content = {
            Text(label, fontSize = 13.sp, color = colors.muted)
            Text(" · ", fontSize = 13.sp, color = colors.muted)
            Box { state() }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val (labelM, dotM, stateM) = measurables
        val max = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val labelWide = labelM.maxIntrinsicWidth(constraints.maxHeight)
        val dot = dotM.measure(loose)
        val stateWide = stateM.maxIntrinsicWidth(constraints.maxHeight)
        if (labelWide + dot.width + stateWide <= max) {
            // One line: label · state, centred on each other.
            val labelP = labelM.measure(loose.copy(maxWidth = labelWide))
            val stateP = stateM.measure(loose.copy(maxWidth = max - labelP.width - dot.width))
            val height = maxOf(labelP.height, dot.height, stateP.height)
            layout(labelP.width + dot.width + stateP.width, height) {
                labelP.placeRelative(0, (height - labelP.height) / 2)
                dot.placeRelative(labelP.width, (height - dot.height) / 2)
                stateP.placeRelative(labelP.width + dot.width, (height - stateP.height) / 2)
            }
        } else {
            // The label wrapped on its own lines, the state under it.
            val labelP = labelM.measure(loose)
            val stateP = stateM.measure(loose)
            layout(maxOf(labelP.width, stateP.width), labelP.height + stateP.height) {
                labelP.placeRelative(0, 0)
                stateP.placeRelative(0, labelP.height)
            }
        }
    }
}
