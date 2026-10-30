package ru.adsrv.frpjump

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.ui.AppNav
import ru.adsrv.frpjump.ui.FrpJumpTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The app's poller runs every 2 s while this is visible (see Poller).
        val poller = (application as FrpJumpApp).poller
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                poller.visible.value = true
                try {
                    awaitCancellation()
                } finally {
                    poller.visible.value = false
                }
            }
        }
        setContent {
            FrpJumpTheme {
                AppNav()
            }
        }
    }
}
