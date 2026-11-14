package ru.adsrv.frpjump.ui.ssh

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.rememberUpdatedState
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

/** How long after [SettledSizeBox]'s `expectKey` changes an animation that has not started yet is waited for. */
internal const val EXPECT_GRACE_MS = 800L

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
 * The settle is also held while the keyboard inset is still animating (its current height differs
 * from its animation target), and for [EXPECT_GRACE_MS] after [expectKey] changes. The composer asks
 * for the keyboard some time after its own layout change, and the system starts that animation late
 * and in staggered frames; no timer shorter than that pause can tell "settled" from "not started
 * yet", so the grace window covers the request and the inset animation covers the rest. If the
 * keyboard never comes (hardware keyboard) the final height is applied when the grace window ends.
 *
 * Only a decrease of the height waits. When the slot grows (the keyboard, the panel or the composer
 * closing) the content follows it on every frame, so there is no wait and no gap above; the
 * keyboard and the grace window do not delay a growth. A change of direction in the middle of an
 * animation is handled the same way: a growing frame follows at once, a shrinking one waits, and the
 * settled height always ends equal to the slot. The SSH window-change is coalesced separately.
 *
 * The first layout and a change of width (rotation, multi-window) apply at once: those are not
 * animations, and one resize is enough.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettledSizeBox(modifier: Modifier = Modifier, expectKey: Any? = null, content: @Composable () -> Unit) {
    var live by remember { mutableStateOf(IntSize.Zero) }
    // Height the content has, and the last box size seen by the layout. Plain values: the layout
    // pass itself applies a growth, so the content follows the box in the same frame, not one later.
    val state = remember { IntArray(3) } // held height, last max height, last width
    var tick by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val imeMoving by rememberUpdatedState(
        WindowInsets.ime.getBottom(density) != WindowInsets.imeAnimationTarget.getBottom(density),
    )
    var graceUntil by remember { mutableStateOf(0L) }
    var seenKey by remember { mutableStateOf(expectKey) }
    if (seenKey != expectKey) {
        seenKey = expectKey
        graceUntil = System.nanoTime() + EXPECT_GRACE_MS * 1_000_000L
    }
    LaunchedEffect(live) {
        if (live != IntSize.Zero && state[0] != live.height) {
            var quiet = 0
            while (quiet < SETTLE_FRAMES) {
                withFrameNanos { }
                quiet = if (imeMoving || System.nanoTime() < graceUntil) 0 else quiet + 1
            }
            state[0] = live.height
            tick++
        }
    }
    Box(modifier.clipToBounds().onSizeChanged { live = it }) {
        Box(
            Modifier.layout { measurable, constraints ->
                tick // read: the settle invalidates the layout
                val width = constraints.maxWidth
                val max = constraints.maxHeight
                if (state[0] == 0 || width != state[2] || max > state[1]) state[0] = max
                state[1] = max
                state[2] = width
                val height = state[0]
                val placeable = measurable.measure(Constraints.fixed(width, height))
                layout(width, max) { placeable.place(0, max - height) }
            },
        ) { content() }
    }
}
