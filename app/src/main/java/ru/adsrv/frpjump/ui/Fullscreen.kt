package ru.adsrv.frpjump.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.locale.Texts
import ru.adsrv.frpjump.settings.AppSettings
import kotlin.math.roundToInt

/** Which screen's full-screen choice: each is remembered on its own. */
enum class FullscreenOf { WEB, TERMINAL }

/**
 * Whether [of] is full screen, from the settings, and a setter that keeps
 * the choice; a write that fails says so and leaves the screen as it was.
 */
@Composable
fun rememberFullscreen(of: FullscreenOf): Pair<Boolean, (Boolean) -> Unit> {
    val app = LocalContext.current.applicationContext as FrpJumpApp
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val on = when (of) {
        FullscreenOf.WEB -> settings.webFullscreen
        FullscreenOf.TERMINAL -> settings.terminalFullscreen
    }
    return on to { value: Boolean ->
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching { app.settings.update { it.withFullscreen(of, value) } }.exceptionOrNull()
            }
            if (failure != null) Toast.makeText(app, Texts.get(R.string.settings_save_failed, failure.userMessage()), Toast.LENGTH_LONG).show()
        }
        Unit
    }
}

private fun AppSettings.withFullscreen(of: FullscreenOf, on: Boolean) = when (of) {
    FullscreenOf.WEB -> copy(webFullscreen = on)
    FullscreenOf.TERMINAL -> copy(terminalFullscreen = on)
}

/**
 * Hides the status and navigation bars while [hidden]; a swipe from an
 * edge shows them for a moment. They come back when the screen goes.
 */
@Composable
fun SystemBarsHidden(hidden: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, hidden) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (controller != null && hidden) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { if (hidden) controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/**
 * The small round "Exit full screen" over a full-screen page or terminal,
 * at the top end; drag it away from whatever it covers. Where it was
 * dragged to holds while the screen is open.
 */
@Composable
fun BoxScope.CollapseButton(onClick: () -> Unit) {
    var dx by rememberSaveable { mutableStateOf(0f) }
    var dy by rememberSaveable { mutableStateOf(0f) }
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .systemBarsPadding()
            .padding(8.dp)
            .offset { IntOffset(dx.roundToInt(), dy.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    dx += drag.x
                    dy += drag.y
                }
            }
            .size(40.dp)
            .background(Color.Black.copy(alpha = 0.35f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick) {
            Icon(FullscreenExitGlyph, contentDescription = stringResource(R.string.fullscreen_exit), tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}
