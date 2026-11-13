package ru.adsrv.frpjump.ui.ssh

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.withFrameNanos

/** Frames the box height must stay still before the content takes it (about 330 ms at 60 Hz). */
internal const val SETTLE_FRAMES = 20

/**
 * Gives [content] the box height only once it has stopped changing.
 *
 * The keyboard, the key panel and the composer change the height of the terminal's slot on every
 * frame while they animate. The Termux view re-computes its rows on every size change, so the text
 * stepped row by row and the cursor moved with the shrinking grid. Here the content keeps its last
 * settled size, bottom aligned: when the slot shrinks the top rows are clipped and the cursor line
 * stays just above the rising edge; when it grows the gap above is empty. After [SETTLE_FRAMES] frames without
 * a change the content takes the final height, so its grid is resized once.
 *
 * The first layout and a change of width (rotation, multi-window) apply at once: those are not
 * animations, and one resize is enough.
 */
@Composable
internal fun SettledSizeBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var live by remember { mutableStateOf(IntSize.Zero) }
    var held by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(live) {
        if (held == IntSize.Zero || live.width != held.width) {
            held = live
        } else {
            repeat(SETTLE_FRAMES) { withFrameNanos { } }
            held = live
        }
    }
    Box(modifier.clipToBounds().onSizeChanged { live = it }) {
        Box(
            Modifier.layout { measurable, constraints ->
                val width = constraints.maxWidth
                val height = if (held == IntSize.Zero) constraints.maxHeight else held.height
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(width, constraints.maxHeight) { placeable.place(0, constraints.maxHeight - height) }
            },
        ) { content() }
    }
}
