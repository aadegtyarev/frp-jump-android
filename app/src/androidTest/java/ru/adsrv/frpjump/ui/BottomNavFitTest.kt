package ru.adsrv.frpjump.ui

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.R
import java.util.Locale

/**
 * The four labels of the bottom navigation (Devices, Connections, Sessions, Assistant) stay whole in the narrowest phone widths (320 and 360 dp), in RU and EN, at 100% and 200%
 * font. The font scale and the language are set here, on the composition, so the check does not depend on the device's settings.
 */
@RunWith(AndroidJUnit4::class)
class BottomNavFitTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val items = listOf("nav_devices" to R.string.tab_devices, "nav_connections" to R.string.tab_connections,
        "nav_sessions" to R.string.tab_sessions, "nav_assistant" to R.string.tab_assistant)

    private fun check(widthDp: Int, lang: String, fontScale: Float, selected: Tab) {
        val base = compose.activity
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale(lang)) }
        val localized = base.createConfigurationContext(config)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                LocalDensity provides Density(density.density, fontScale)) {
                FrpJumpTheme { Box(Modifier.width(widthDp.dp)) { BottomNav(selected, onAssistant = {}) {} } }
            }
        }
        compose.waitForIdle()
        for ((tag, label) in items) assertNavLabelWhole(compose, localized.getString(label), tag)
    }

    @Test fun ruAt320dpAndDoubleFont() = check(320, "ru", 2f, Tab.DEVICES)
    @Test fun ruAt360dpAndDoubleFont() = check(360, "ru", 2f, Tab.CONNECTIONS)
    @Test fun enAt320dpAndDoubleFont() = check(320, "en", 2f, Tab.SESSIONS)
    @Test fun enAt360dpAndDoubleFont() = check(360, "en", 2f, Tab.DEVICES)
    @Test fun threeItemsWithTheAssistantOffAtDoubleFont() {
        val config = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale("ru")) }
        val localized = compose.activity.createConfigurationContext(config)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                LocalDensity provides Density(density.density, 2f)) {
                FrpJumpTheme { Box(Modifier.width(320.dp)) { BottomNav(Tab.SESSIONS) {} } }
            }
        }
        compose.waitForIdle()
        for ((tag, label) in items.filter { it.first != "nav_assistant" }) assertNavLabelWhole(compose, localized.getString(label), tag)
        compose.onNodeWithTag("nav_settings").assertDoesNotExist()
        compose.onNodeWithTag("nav_assistant").assertDoesNotExist()
    }
    @Test fun ruAt320dpAtNormalFont() = check(320, "ru", 1f, Tab.DEVICES)
    @Test fun ruAt360dpAtNormalFont() = check(360, "ru", 1f, Tab.CONNECTIONS)
}
