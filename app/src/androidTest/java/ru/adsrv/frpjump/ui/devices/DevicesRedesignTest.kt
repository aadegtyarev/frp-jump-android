package ru.adsrv.frpjump.ui.devices

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.api.Age
import ru.adsrv.frpjump.api.AgeUnit
import ru.adsrv.frpjump.devices.DeviceIcon
import ru.adsrv.frpjump.devices.DeviceRow
import ru.adsrv.frpjump.settings.ThemeMode
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.FrpJumpTheme
import ru.adsrv.frpjump.ui.profiles.ProfileItem
import java.io.File
import java.util.Locale

class DevicesRedesignTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val names = listOf("beelink", "archive", "backup", "camera-entry", "controller", "desktop", "gateway", "home-lab", "media", "office-pc", "printer", "router-home", "sensor-hub", "storage", "workbench")
    private val offline = setOf("archive", "camera-entry", "desktop", "media", "printer")
    private var state by mutableStateOf(DevicesUiState(profile = "work", devices = names.map { DeviceRow(it, it !in offline, if (it in offline) Age(AgeUnit.MINUTES, 3) else null, false, label = if (it == "beelink") "красный" else null, icon = if (it == "beelink") DeviceIcon.LINUX else null) }, favorites = setOf("beelink", "router-home"), display = true))
    private var opened: Pair<String, String>? = null
    private val profile = ProfileItem("work", "Работа", "crimson", null, false, 0, current = true, removed = false)

    private lateinit var originalConfiguration: Configuration

    @org.junit.Before fun useFixtureLocale() {
        val landscape = InstrumentationRegistry.getArguments().getString("composerOrientation") == "landscape"
        compose.activityRule.scenario.onActivity {
            it.enableEdgeToEdge()
            it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        val until = android.os.SystemClock.uptimeMillis() + 5000
        while (compose.activity.resources.configuration.orientation != expected && android.os.SystemClock.uptimeMillis() < until) android.os.SystemClock.sleep(50)
        assertEquals(expected, compose.activity.resources.configuration.orientation)
        val resources = compose.activity.resources
        InstrumentationRegistry.getArguments().getString("composerFontScale")?.toFloat()?.let { scale ->
            assertEquals("actual font scale", scale, resources.configuration.fontScale, 0.01f)
        }
        originalConfiguration = Configuration(resources.configuration)
        val language = InstrumentationRegistry.getArguments().getString("chatLocale") ?: "en"
        @Suppress("DEPRECATION")
        resources.updateConfiguration(Configuration(originalConfiguration).apply { setLocale(Locale.forLanguageTag(language)) }, resources.displayMetrics)
    }

    @org.junit.After fun restoreLocale() {
        val resources = compose.activity.resources
        @Suppress("DEPRECATION")
        resources.updateConfiguration(originalConfiguration, resources.displayMetrics)
    }

    private val localized by lazy {
        val language = InstrumentationRegistry.getArguments().getString("chatLocale") ?: "en"
        val config = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }
        compose.activity.createConfigurationContext(config)
    }

    private fun host() = compose.setContent {
        CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration, LocalActivityResultRegistryOwner provides compose.activity) {
        FrpJumpTheme(mode = ThemeMode.DARK) {
            Box(Modifier.fillMaxSize().background(AppTheme.colors.ground)) {
                DevicesContent(state, listOf(profile), {}, { p, d -> opened = p to d }, {}, {}, { _, _, _, done -> done(null) }, { p, d, done ->
                    if (p == state.profile) state = state.copy(favorites = if (d in state.favorites) state.favorites - d else state.favorites + d)
                    done(null)
                })
            }
        }
        }
    }

    private fun shot(name: String) {
        compose.waitForIdle(); android.os.SystemClock.sleep(500)
        val a = InstrumentationRegistry.getArguments()
        val suffix = "${a.getString("chatLocale") ?: "en"}-${a.getString("composerOrientation") ?: "portrait"}-font${a.getString("composerFontScale") ?: "1.0"}"
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(compose.activity.getExternalFilesDir(null), "$name-$suffix.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }

    private fun assertOutsideSystemBars(node: SemanticsNodeInteraction) {
        val view = compose.activity.window.decorView
        val insets = WindowInsetsCompat.toWindowInsetsCompat(view.rootWindowInsets, view).getInsets(WindowInsetsCompat.Type.systemBars())
        if (InstrumentationRegistry.getArguments().getString("composerOrientation") == "landscape") {
            assertTrue("landscape must have a horizontal system bar", insets.left > 0 || insets.right > 0)
        }
        val bounds = node.fetchSemanticsNode().boundsInWindow
        assertTrue("left edge is outside system bars: $bounds", bounds.left >= insets.left - 1f)
        assertTrue("right edge is outside system bars: $bounds", bounds.right <= view.width - insets.right + 1f)
    }

    @Test fun favoritesFilterSearchAndScope() {
        host()
        assertOutsideSystemBars(compose.onNodeWithTag("settings_gear"))
        compose.onNodeWithTag("device_beelink").assertIsDisplayed()
        shot("devices-idle")
        compose.onNodeWithTag("devices_online_filter").performClick()
        compose.onNodeWithTag("device_archive").assertDoesNotExist()
        shot("devices-online")
        compose.onNodeWithTag("devices_search_open").performClick()
        compose.onNodeWithTag("search_field").performTextInput("красный")
        assertOutsideSystemBars(compose.onNodeWithContentDescription(localized.getString(R.string.connections_clear_query)))
        compose.onNodeWithTag("device_beelink").assertIsDisplayed()
        compose.onNodeWithTag("device_router-home").assertDoesNotExist()
        shot("devices-search-focused")
        compose.onNodeWithTag("search_field").performImeAction()
        compose.onNodeWithTag("devices_online_filter").assertIsOn()
        compose.onNodeWithTag("device_beelink").performClick()
        assertEquals("work" to "beelink", opened)
        shot("devices-search-retained")
        compose.onNodeWithTag("devices_search_open").performClick()
        compose.onNodeWithTag("search_field").performTextClearance()
        compose.onNodeWithTag("search_field").performTextInput("archive")
        compose.onNodeWithTag("search_field").performImeAction()
        compose.onNodeWithText(localized.getString(R.string.devices_no_online)).assertIsDisplayed()
        shot("devices-no-match")
        compose.onNodeWithTag("device_archive").assertDoesNotExist()
        compose.onNodeWithTag("devices_show_all").performClick()
        compose.onNodeWithTag("device_archive").assertIsDisplayed()
        shot("devices-recovered")
    }

    @Test fun loadingAndStaleState() {
        state = state.copy(devices = null)
        host()
        compose.onNodeWithTag("devices_loading").assertIsDisplayed()
        shot("devices-loading")
        compose.runOnIdle { state = state.copy(devices = names.map { DeviceRow(it, it !in offline, null, false, stale = true) }, staleSince = java.time.Instant.EPOCH, error = ru.adsrv.frpjump.errors.DomainError.ServerError("offline"), unreachable = true) }
        compose.onNodeWithTag("device_beelink").assertIsDisplayed()
        shot("devices-stale")
    }

    @Test fun favoriteMenuAndEmptyStates() {
        host()
        compose.onNodeWithTag("device_beelink").performTouchInput { swipeRight() }
        compose.onNodeWithTag("device_favorite_action").assertIsDisplayed()
        compose.runOnIdle { assertEquals(setOf("beelink", "router-home"), state.favorites) }
        shot("devices-favorite-revealed")
        compose.onNodeWithTag("device_favorite_action").performClick()
        compose.runOnIdle { assertEquals(setOf("router-home"), state.favorites) }
        compose.onAllNodesWithContentDescription(localized.getString(R.string.more))[0].performClick()
        shot("devices-menu")
        compose.onNodeWithText(localized.getString(R.string.favorite_remove)).performClick()
        compose.runOnIdle { assertEquals(emptySet<String>(), state.favorites) }
        compose.runOnIdle { state = state.copy(devices = emptyList()) }
        compose.onNodeWithText(localized.getString(R.string.devices_empty)).assertIsDisplayed()
        shot("devices-empty")
    }
}
