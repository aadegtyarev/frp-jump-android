package ru.adsrv.frpjump

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.ui.AppNav
import ru.adsrv.frpjump.ui.FrpJumpTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Heartbeat + desired state every 2 s while the app is visible. Closed,
        // nothing polls (the background setting comes later); frpc keeps running.
        val tunnels = (application as FrpJumpApp).tunnels
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { tunnels.pollEvery(FOREGROUND_POLL_MS) }
        }
        setContent {
            FrpJumpTheme {
                AppNav()
            }
        }
    }

    private companion object {
        const val FOREGROUND_POLL_MS = 2_000L
    }
}
