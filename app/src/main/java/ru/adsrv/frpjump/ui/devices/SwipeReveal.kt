package ru.adsrv.frpjump.ui.devices

import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

private enum class Reveal { CLOSED, OPEN }

/**
 * [content] that a swipe to the left slides aside by [actionWidth],
 * showing [action] behind its end; only while [enabled]. [open] is whether
 * it should stay open: it slides back once that turns false (another row
 * opened, the list scrolled, a tap elsewhere). [onOpenChange] says where
 * the finger left it. Horizontal only: the list's scroll and pull to
 * refresh pass by.
 */
@Composable
fun SwipeReveal(
    open: Boolean,
    enabled: Boolean,
    onOpenChange: (Boolean) -> Unit,
    actionWidth: Dp,
    action: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val widthPx = with(LocalDensity.current) { actionWidth.toPx() }
    val state = remember { AnchoredDraggableState(Reveal.CLOSED) }
    // Without tunnels there is nothing to reveal: only the closed place is left, and it snaps there.
    LaunchedEffect(widthPx, enabled) {
        state.updateAnchors(
            DraggableAnchors {
                Reveal.CLOSED at 0f
                if (enabled) Reveal.OPEN at -widthPx
            },
        )
    }
    LaunchedEffect(open) {
        if (!open && state.targetValue != Reveal.CLOSED) state.animateTo(Reveal.CLOSED)
    }
    val report by rememberUpdatedState(onOpenChange)
    LaunchedEffect(state) {
        snapshotFlow { state.settledValue }.collect { report(it == Reveal.OPEN) }
    }
    // Drawn only while the row is aside, fading in as it goes: nothing shows through the card's rounded corners.
    val aside by remember { derivedStateOf { state.offset.let { !it.isNaN() && it < 0f } } }
    Box(modifier) {
        if (aside) {
            Box(
                Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = (-state.offset / widthPx).coerceIn(0f, 1f) },
                contentAlignment = Alignment.CenterEnd,
                content = action,
            )
        }
        Box(
            Modifier
                .absoluteOffset { IntOffset(state.offset.takeIf { !it.isNaN() }?.roundToInt() ?: 0, 0) }
                .anchoredDraggable(state, Orientation.Horizontal, enabled = enabled),
        ) { content() }
    }
}

/** Which row of a list is swiped open, one at a time, by its key; and the row a finger last came down on. */
@Stable
class OpenRow {
    var name by mutableStateOf<String?>(null)
        private set
    internal var touched: String? = null

    fun close() {
        name = null
    }

    /** Where the finger left row [key]: open, or closed (which closes nothing else). */
    fun reported(key: String, open: Boolean) {
        if (open) name = key else if (name == key) name = null
    }
}

/** On a row: a finger came down on [key] (first, before the screen around it sees it). */
fun Modifier.touchesRow(open: OpenRow, key: String) = pointerInput(open, key) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        open.touched = key
    }
}

/** Around the list: a finger coming down anywhere but on the open row closes it (last, after the rows saw it). */
fun Modifier.closesOpenRow(open: OpenRow) = pointerInput(open) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
        if (open.touched != open.name) open.close()
        open.touched = null
    }
}
