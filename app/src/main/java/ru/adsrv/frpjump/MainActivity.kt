package ru.adsrv.frpjump

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Build
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import ru.adsrv.frpjump.lock.AppLock
import ru.adsrv.frpjump.lock.AuthResult
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.LockScreen
import ru.adsrv.frpjump.ui.isDark
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
    private val app get() = application as FrpJumpApp

    /**
     * The activity's configuration as last changed. On Android 13+ Compose
     * hears of a new language from its view as well; on 10–12 only this
     * call from AppCompat says it, so the content reads it from here.
     */
    private val configuration = mutableStateOf<Configuration?>(null)

    /** The theme the content is drawn in: the system bars' icons follow it. */
    private var darkTheme = false

    /** The unlock prompt is up: the lock screen's button shows progress. */
    private val authenticating = mutableStateOf(false)

    /** Why the last prompt did not unlock, on the lock screen. */
    private val lockMessage = mutableStateOf<String?>(null)

    /** The prompt came up by itself for the current lock; after that only "Unlock" brings it back. */
    private var promptedForLock = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        edgeToEdge()
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
        lifecycleScope.launch {
            app.settings.settings.map { it.appLock }.distinctUntilChanged().collect(::hideFromRecents)
        }
        lifecycleScope.launch {
            app.lock.locked.collect { locked ->
                if (!locked) {
                    promptedForLock = false
                    lockMessage.value = null
                }
            }
        }
        takeOpenDevice(intent)
        setContent {
            val config = configuration.value ?: LocalConfiguration.current
            val settings by app.settings.settings.collectAsState()
            val theme = settings.theme
            val locked by app.lock.locked.collectAsState()
            // stringResource() reads the activity's resources, which AppCompat has updated by then.
            CompositionLocalProvider(LocalConfiguration provides config) {
                FrpJumpTheme(theme) {
                    val dark = isDark(theme)
                    LaunchedEffect(dark) {
                        darkTheme = dark
                        edgeToEdge()
                    }
                    // The theme's ground and ink under every screen, whatever the window's (the system's) are.
                    Surface(Modifier.fillMaxSize(), color = AppTheme.colors.ground, contentColor = AppTheme.colors.ink) {
                        // Kept while locked (back stack, open pages, polling); hidden and silent.
                        Box(if (locked) Modifier.clearAndSetSemantics { } else Modifier) { AppNav() }
                        if (locked) {
                            LockScreen(
                                busy = authenticating.value,
                                message = lockMessage.value,
                                onUnlock = ::promptUnlock,
                                onLeave = { moveTaskToBack(true) },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        app.lock.onStart(app.settings.settings.value.appLock)
    }

    override fun onResume() {
        super.onResume()
        if (app.lock.locked.value && !promptedForLock && !authenticating.value) promptUnlock()
    }

    override fun onStop() {
        app.lock.onStop()
        // Back after leaving the lock screen: ask again by itself. Not when the prompt's own screen hid this one.
        if (!authenticating.value) promptedForLock = false
        super.onStop()
    }

    /**
     * Asks to unlock. A phone whose screen lock was removed since has
     * nothing to ask for: the setting goes off rather than locking the
     * owner out, and a message says so.
     */
    private fun promptUnlock() {
        if (!AppLock.hasScreenLock(this)) {
            app.lock.unlock()
            app.appScope.launch(Dispatchers.IO) { app.settings.update { it.copy(appLock = false) } }
            Toast.makeText(this, getString(R.string.lock_no_screen_lock_off), Toast.LENGTH_LONG).show()
            return
        }
        promptedForLock = true
        authenticating.value = true
        lockMessage.value = null
        app.lock.authenticate(this, getString(R.string.lock_prompt_title), getString(R.string.lock_prompt_subtitle)) { result ->
            authenticating.value = false
            lockMessage.value = when (result) {
                AuthResult.Success -> null
                AuthResult.Cancelled -> getString(R.string.lock_cancelled)
                is AuthResult.Failed -> getString(R.string.lock_failed, result.message)
            }
        }
    }

    /**
     * With the lock on, Recents shows no picture of the app. Android 13+
     * can leave just the thumbnail out (screenshots still work); before
     * that only FLAG_SECURE hides it, which also blocks screenshots. Not
     * "only while locked": the thumbnail is taken when the app is left,
     * unlocked, so that would show the screen anyway.
     */
    private fun hideFromRecents(hide: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(!hide)
        } else if (hide) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /** Edge to edge, the bars' icons dark on the light theme and light on the dark one, whatever the system's. */
    private fun edgeToEdge() {
        val dark = darkTheme
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
        )
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
        edgeToEdge()
        configuration.value = Configuration(newConfig)
    }

    companion object {
        private const val ACTION_OPEN_DEVICE = "ru.adsrv.frpjump.OPEN_DEVICE"
        private const val EXTRA_PROFILE = "profile"
        private const val EXTRA_DEVICE = "device"

        /** androidx.activity's own scrims for the three-button navigation bar. */
        private val LIGHT_SCRIM = android.graphics.Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
        private val DARK_SCRIM = android.graphics.Color.argb(0x80, 0x1B, 0x1B, 0x1B)

        /** Opens [device]'s card in [profile], over whatever the app shows. */
        fun openDevice(context: Context, profile: String, device: String): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_OPEN_DEVICE)
                .putExtra(EXTRA_PROFILE, profile)
                .putExtra(EXTRA_DEVICE, device)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
