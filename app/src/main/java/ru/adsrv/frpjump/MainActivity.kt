package ru.adsrv.frpjump

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
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
 * gives it the app's language (Android 13+ does that itself). A new language
 * does not recreate it (locale is in configChanges, so an open web page
 * stays): the screens are drawn again with [configuration].
 */
class MainActivity : AppCompatActivity() {
    /**
     * The activity's configuration as last changed. On Android 13+ Compose
     * hears of a new language from its view as well; on 10–12 only this
     * call from AppCompat says it, so the content reads it from here.
     */
    private val configuration = mutableStateOf<Configuration?>(null)

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
            val config = configuration.value ?: LocalConfiguration.current
            // stringResource() reads the activity's resources, which AppCompat has updated by then.
            CompositionLocalProvider(LocalConfiguration provides config) {
                FrpJumpTheme {
                    AppNav()
                }
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

    /**
     * Dark mode switched while open (uiMode is handled here, not by
     * recreating): status bar icons follow it. A new language: the content
     * is drawn again in it.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enableEdgeToEdge()
        configuration.value = Configuration(newConfig)
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
