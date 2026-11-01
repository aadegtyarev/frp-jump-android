package ru.adsrv.frpjump.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.locale.Texts
import ru.adsrv.frpjump.notices.NoticeKind
import ru.adsrv.frpjump.settings.AppSettings

/** Which screen's full-screen choice: each is remembered on its own. */
enum class FullscreenOf { WEB, TERMINAL }

/**
 * Whether [of] is full screen, from the settings, and a setter that keeps
 * the choice; a write that fails says so and leaves the screen as it was.
 * Written in the app's scope: leaving the screen right after the tap must
 * not cancel the write into a false "not saved".
 */
@Composable
fun rememberFullscreen(of: FullscreenOf): Pair<Boolean, (Boolean) -> Unit> {
    val app = LocalContext.current.applicationContext as FrpJumpApp
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val on = when (of) {
        FullscreenOf.WEB -> settings.webFullscreen
        FullscreenOf.TERMINAL -> settings.terminalFullscreen
    }
    return on to { value: Boolean ->
        app.appScope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching { app.settings.update { it.withFullscreen(of, value) } }.exceptionOrNull()
            }
            if (failure != null) app.notices.show(Texts.get(R.string.settings_save_failed, failure.userMessage()), NoticeKind.WARN, long = true)
        }
        Unit
    }
}

private fun AppSettings.withFullscreen(of: FullscreenOf, on: Boolean) = when (of) {
    FullscreenOf.WEB -> copy(webFullscreen = on)
    FullscreenOf.TERMINAL -> copy(terminalFullscreen = on)
}

/**
 * Hides the status and navigation bars while [hidden] and the app is not
 * locked (the lock screen and its prompt keep them); a swipe from an edge
 * shows them for a moment. Hidden again on every resume, as a system
 * dialog or the app going away brings them back; shown when the screen goes.
 */
@Composable
fun SystemBarsHidden(hidden: Boolean) {
    val app = LocalContext.current.applicationContext as FrpJumpApp
    val locked by app.lock.locked.collectAsStateWithLifecycle()
    val hide = hidden && !locked
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(view, lifecycle, hide) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val behavior = controller?.systemBarsBehavior
        val apply = {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) apply() }
        if (hide) {
            apply()
            lifecycle.addObserver(observer)
        }
        onDispose {
            if (hide) {
                lifecycle.removeObserver(observer)
                controller?.show(WindowInsetsCompat.Type.systemBars())
                if (behavior != null) controller.systemBarsBehavior = behavior
            }
        }
    }
}

/**
 * The small round "Exit full screen" over a full-screen page or terminal,
 * at the top end, clear of a display cutout; drag it away from whatever it
 * covers. It stays on the screen, whatever the drag or a rotation.
 */
@Composable
fun BoxScope.CollapseButton(onClick: () -> Unit) {
    var dx by rememberSaveable { mutableStateOf(0f) }
    var dy by rememberSaveable { mutableStateOf(0f) }
    BoxWithConstraints(Modifier.matchParentSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(4.dp)) {
        val target = with(LocalDensity.current) { TARGET.toPx() }
        // Starts at the top end: it may move left by the width and down by the height, less itself.
        val maxLeft = (constraints.maxWidth - target).coerceAtLeast(0f)
        val maxDown = (constraints.maxHeight - target).coerceAtLeast(0f)
        dx = dx.coerceIn(-maxLeft, 0f)
        dy = dy.coerceIn(0f, maxDown)
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(dx.roundToInt(), dy.roundToInt()) }
                .size(TARGET)
                .pointerInput(maxLeft, maxDown) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        dx = (dx + drag.x).coerceIn(-maxLeft, 0f)
                        dy = (dy + drag.y).coerceIn(0f, maxDown)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                onClick = onClick,
                modifier = Modifier
                    .size(40.dp)
                    // Dark with a light ring: seen on a white page and on a black terminal.
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape),
            ) {
                Icon(FullscreenExitGlyph, contentDescription = stringResource(R.string.fullscreen_exit), tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

private val TARGET = 48.dp
