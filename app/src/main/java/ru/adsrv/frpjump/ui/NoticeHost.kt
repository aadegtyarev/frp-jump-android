package ru.adsrv.frpjump.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.notices.NoticeAction
import ru.adsrv.frpjump.notices.NoticeKind
import ru.adsrv.frpjump.notices.Notices
import ru.adsrv.frpjump.notices.TopNotice
import kotlin.math.roundToInt

/** The app's [Notices], from any screen's context. */
val Context.notices: Notices get() = (applicationContext as FrpJumpApp).notices

/** Shorthand for [Notices.show] from a screen. */
fun Context.notice(text: String, kind: NoticeKind = NoticeKind.INFO, long: Boolean = false, key: String? = null, action: NoticeAction? = null) =
    notices.show(text, kind, long, key, action)

/**
 * The notice shown now, at the top over the screens and their bars (under
 * the status bar and any cutout when [edge]): a card with a glyph of its
 * kind, the text and its button if any. It slides in, goes after its time,
 * and a tap on its text or a swipe up hides it at once. TalkBack reads it
 * out. Of the hosts drawn, only the innermost shows it ([NoticeLayer] in a
 * bottom sheet), so it is not left to run out under a scrim.
 */
@Composable
fun NoticeHost(notices: Notices, modifier: Modifier = Modifier, edge: Boolean = true) {
    val me = remember { Any() }
    DisposableEffect(notices) {
        notices.attach(me)
        onDispose { notices.detach(me) }
    }
    val hosts by notices.hosts.collectAsStateWithLifecycle()
    val queue by notices.queue.collectAsStateWithLifecycle()
    val shown = queue.shown.takeIf { hosts.lastOrNull() === me }
    // Kept while it slides out.
    var last by remember { mutableStateOf<TopNotice?>(null) }
    if (shown != null) last = shown
    LaunchedEffect(shown?.id) {
        val now = shown ?: return@LaunchedEffect
        delay(now.durationMs)
        notices.dismiss(now.id)
    }
    // Centred on a wide screen.
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = shown != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .then(
                    if (edge) {
                        Modifier.windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            last?.let { NoticeCard(it, onDismiss = { notices.dismiss(it.id) }) }
        }
    }
}

/** [content] with a notice host on top: a bottom sheet's, so a message posted while it is open shows in it. */
@Composable
fun NoticeLayer(content: @Composable () -> Unit) {
    val notices = LocalContext.current.notices
    Box {
        content()
        NoticeHost(notices, Modifier.align(Alignment.TopCenter), edge = false)
    }
}

@Composable
private fun NoticeCard(notice: TopNotice, onDismiss: () -> Unit) {
    val colors = AppTheme.colors
    val density = LocalDensity.current
    var drag by remember(notice.id) { mutableFloatStateOf(0f) }
    val away = with(density) { 32.dp.toPx() }
    val dismissLabel = stringResource(R.string.notice_dismiss)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.card,
        border = BorderStroke(1.dp, colors.outline),
        shadowElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(0, drag.roundToInt()) }
            // Up only: a swipe up past a little hides it.
            .draggable(
                state = rememberDraggableState { delta -> drag = (drag + delta).coerceAtMost(0f) },
                orientation = Orientation.Vertical,
                onDragStopped = { if (drag < -away) onDismiss() else drag = 0f },
            )
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The text side hides it on a tap; the button, if any, is a target of its own.
            Row(
                Modifier
                    .weight(1f)
                    .clickable(onClickLabel = dismissLabel, onClick = onDismiss)
                    .padding(start = 14.dp, end = if (notice.action != null) 4.dp else 14.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val (glyph, tint) = when (notice.kind) {
                    NoticeKind.DONE -> DoneGlyph to colors.accent
                    NoticeKind.WARN -> WarnGlyph to colors.warning
                    NoticeKind.INFO -> InfoGlyph to colors.muted
                }
                Icon(glyph, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                Text(notice.text, fontSize = 14.sp, color = colors.ink, modifier = Modifier.weight(1f))
            }
            notice.action?.let { action ->
                TextButton(
                    onClick = {
                        onDismiss()
                        action.run()
                    },
                    modifier = Modifier.padding(end = 4.dp),
                ) { Text(action.label, color = colors.accent, fontSize = 14.sp) }
            }
        }
    }
}
