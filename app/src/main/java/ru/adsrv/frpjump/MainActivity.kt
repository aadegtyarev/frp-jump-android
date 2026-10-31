package ru.adsrv.frpjump

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.ui.AppNav
import ru.adsrv.frpjump.ui.FrpJumpTheme

/**
 * An AppCompat activity for Settings › Language: on Android 10–12 AppCompat
 * gives it the app's language and recreates it when that changes (Android
 * 13+ does both itself). Locale is not in configChanges, so a new language
 * recreates the activity; Compose keeps its saved state across it.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The current profile polls every 2 s while this is visible (see Poller).
        val visible = (application as FrpJumpApp).visible
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                visible.value = true
                try {
                    awaitCancellation()
                } finally {
                    visible.value = false
                }
            }
        }
        takeOpenDevice(intent)
        setContent {
            FrpJumpTheme {
                AppNav()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeOpenDevice(intent)
    }

    /** A notification asked for a device card: the navigation shows it ([ru.adsrv.frpjump.ui.AppNav]). */
    private fun takeOpenDevice(intent: Intent?) {
        if (intent?.action != ACTION_OPEN_DEVICE) return
        val profile = intent.getStringExtra(EXTRA_PROFILE) ?: return
        val device = intent.getStringExtra(EXTRA_DEVICE) ?: return
        (application as FrpJumpApp).openDevice.value = OpenDevice(profile, device)
        // Once: not again when the activity is recreated from this intent.
        intent.action = null
    }

    /** Dark mode switched while open (uiMode is handled here, not by recreating): status bar icons follow it. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enableEdgeToEdge()
    }

    companion object {
        private const val ACTION_OPEN_DEVICE = "ru.adsrv.frpjump.OPEN_DEVICE"
        private const val EXTRA_PROFILE = "profile"
        private const val EXTRA_DEVICE = "device"

        /** Opens [device]'s card in [profile], over whatever the app shows. */
        fun openDevice(context: Context, profile: String, device: String): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_DEVICE)
                .putExtra(EXTRA_PROFILE, profile)
                .putExtra(EXTRA_DEVICE, device)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
