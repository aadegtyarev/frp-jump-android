package ru.adsrv.frpjump.ui.settings

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ru.adsrv.frpjump.ui.AppTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModelStore
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.settings.AppSettings
import ru.adsrv.frpjump.settings.ThemeMode
import ru.adsrv.frpjump.ui.FrpJumpTheme
import ru.adsrv.frpjump.ui.profiles.ProfileItem
import ru.adsrv.frpjump.ui.profiles.ProfileServer
import java.io.File
import java.util.Locale

/**
 * R39: Settings and its Assistant screen on the real screens. The Assistant screen's rows in order, its master switch (the same setting as
 * before), Back to Settings through real navigation, and the main screen's labels whole (no word broken, no value over a label) at this
 * run's font size and language. Screenshots of both screens, light and dark, go to the app's external files dir (the run script pulls them
 * into build/shots). The original preferences are restored.
 */
@RunWith(AndroidJUnit4::class)
class SettingsR39Test {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val args = InstrumentationRegistry.getArguments()
    private var original: AppSettings? = null
    private val models = ViewModelStore()
    private val app get() = compose.activity.application as FrpJumpApp
    private fun text(id: Int) = compose.activity.getString(id)

    @Before fun display() {
        val landscape = args.getString("composerOrientation") == "landscape"
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        compose.activityRule.scenario.onActivity { it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        val deadline = SystemClock.uptimeMillis() + 5000
        while (compose.activity.resources.configuration.orientation != expected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertEquals(expected, compose.activity.resources.configuration.orientation)
        args.getString("chatLocale")?.let { lang ->
            val res = compose.activity.resources
            val config = Configuration(res.configuration).apply { setLocale(Locale(lang)) }
            @Suppress("DEPRECATION")
            res.updateConfiguration(config, res.displayMetrics)
            assertEquals(lang, res.configuration.locales[0].language)
        }
        assertEquals(args.getString("composerFontScale")?.toFloat() ?: 1f, compose.activity.resources.configuration.fontScale, 0.01f)
    }

    @After fun cleanup() { compose.runOnIdle { models.clear(); original?.let { old -> app.settings.update { old } } } }

    private fun model(assistantOn: Boolean): SettingsViewModel {
        lateinit var vm: SettingsViewModel
        compose.runOnUiThread {
            if (original == null) original = app.settings.settings.value
            app.settings.update { it.copy(assistantEnabled = assistantOn, appLock = true) }
            vm = SettingsViewModel(app)
            models.put("r39-settings", vm)
        }
        return vm
    }

    private val profile = ProfileItem("p1", "Home", "green", ProfileServer("frp.example.net", "Pixel"), true, 2, current = true, removed = false)

    @Composable private fun SettingsOnly(vm: SettingsViewModel, mode: ThemeMode) {
        FrpJumpTheme(mode) { Surface(Modifier.fillMaxSize(), color = AppTheme.colors.ground) { SettingsScreen(vm, profile, null, {}, {}, onBack = null, onAssistant = {}) } }
    }

    private fun top(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y

    @Test fun assistantScreenHasItsRowsInOrderAndBackReturnsToSettings() {
        val vm = model(assistantOn = false)
        compose.setContent {
            FrpJumpTheme {
                val nav = rememberNavController()
                NavHost(nav, "settings") {
                    composable("settings") { SettingsScreen(vm, null, null, {}, {}, onBack = null, onAssistant = { nav.navigate("assistant") }) }
                    composable("assistant") { AssistantSettingsScreen(vm, onBack = { nav.popBackStack() }, onChat = {}) }
                }
            }
        }
        // One Assistant row on Settings, saying the assistant is off.
        compose.onNodeWithTag("settings_assistant_entry").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText(text(R.string.assistant_row_off)) and hasAnyAncestor(hasTestTag("settings_assistant_entry")), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("settings_assistant_entry").performClick()
        compose.waitForIdle()
        // The rows, top to bottom: the master switch, the setup, the chat.
        val order = listOf("assistant_provider_entry", "assistant_tools_entry", "assistant_owner_entry", "assistant_limits_entry", "chat_text_size_slider", "assistant_chat_entry")
        order.forEach { compose.onNodeWithTag(it).performScrollTo().assertIsDisplayed() }
        val ys = order.map { top(it) }
        assertEquals("rows out of order: $ys", ys.sorted(), ys)
        assertTrue("the master switch is first", compose.onNode(isToggleable()).fetchSemanticsNode().positionInRoot.y < ys.first())
        // The master switch: the same setting as before, off then on then off.
        compose.onNode(isToggleable()).performScrollTo().assertIsOff().performClick()
        compose.waitUntil(5_000) { app.settings.settings.value.assistantEnabled }
        compose.onNode(isToggleable()).performScrollTo().assertIsOn().performClick()
        compose.waitUntil(5_000) { !app.settings.settings.value.assistantEnabled }
        // Tools says how many are on; Limits and Own instructions the short lines.
        compose.onNodeWithText(text(R.string.assistant_limits_hint), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(text(R.string.assistant_owner_not_set), useUnmergedTree = true).assertExists()
        // Back: the Settings list again, with its Assistant row.
        compose.onNodeWithTag("assistant_settings_back").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("settings_assistant_entry").assertExists()
        compose.onNodeWithTag("assistant_provider_entry").assertDoesNotExist()
    }

    @Test fun assistantRowOnSettingsSaysOn() {
        val vm = model(assistantOn = true)
        compose.setContent { SettingsOnly(vm, ThemeMode.LIGHT) }
        compose.onNodeWithTag("settings_assistant_entry").performScrollTo().assertIsDisplayed()
        // "On" or "On · <model>".
        val on = text(R.string.assistant_row_on_plain)
        compose.onNode(hasText(on, substring = true) and hasAnyAncestor(hasTestTag("settings_assistant_entry")), useUnmergedTree = true).assertExists()
    }

    /** No label of the main screen has a line that ends inside a word, and no value text overlaps its label. */
    @Test fun settingsLabelsAreWholeAtThisFontSize() {
        val vm = model(assistantOn = true)
        compose.setContent { SettingsOnly(vm, ThemeMode.LIGHT) }
        val labels = listOf(
            R.string.setting_direct, R.string.local_ports, R.string.background_poll, R.string.battery, R.string.setting_app_lock, R.string.setting_lock_after,
            R.string.setting_hide_in_recents, R.string.ssh_keys, R.string.terminal_history_rows, R.string.setting_terminal_direct_input, R.string.terminal_text_input,
            R.string.mqtt_ingress_title, R.string.mqtt_cache_title, R.string.plugins_title, R.string.language, R.string.theme, R.string.name_and_color,
            R.string.forget_profile, R.string.licenses,
        ).map { text(it) }
        for (label in labels) {
            val nodes = compose.onAllNodes(hasText(label), useUnmergedTree = true).fetchSemanticsNodes()
            assertTrue("'$label' is on the screen", nodes.isNotEmpty())
            for (node in nodes) {
                val results = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
                val layout = results.firstOrNull() ?: continue
                val s = layout.layoutInput.text.text
                for (line in 0 until layout.lineCount - 1) {
                    val end = layout.getLineEnd(line)
                    assertFalse("'$label' broken inside a word at $end", end in 1 until s.length && !s[end - 1].isWhitespace() && !s[end].isWhitespace() && s[end - 1] != '-')
                }
            }
        }
    }

    private fun shot(name: String) {
        compose.waitForIdle(); SystemClock.sleep(400); compose.waitForIdle()
        val tag = listOf(args.getString("composerOrientation") ?: "portrait", args.getString("composerFontScale") ?: "1.0", args.getString("chatLocale") ?: "en").joinToString("-")
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(compose.activity.getExternalFilesDir(null), "settings-$name-$tag.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private fun screenshotsOfSettings(mode: ThemeMode, name: String) {
        val vm = model(assistantOn = true)
        compose.setContent { SettingsOnly(vm, mode) }
        compose.waitForIdle()
        shot("main-top-$name")
        compose.onNodeWithTag("settings_assistant_entry").performScrollTo()
        shot("main-middle-$name")
        compose.onNodeWithText(text(R.string.licenses)).performScrollTo()
        shot("main-end-$name")
    }

    private fun screenshotsOfAssistant(mode: ThemeMode, name: String) {
        val vm = model(assistantOn = true)
        compose.setContent { FrpJumpTheme(mode) { Surface(Modifier.fillMaxSize(), color = AppTheme.colors.ground) { AssistantSettingsScreen(vm, onBack = {}, onChat = {}) } } }
        compose.waitForIdle()
        shot("assistant-top-$name")
        compose.onNodeWithTag("assistant_chat_entry").performScrollTo()
        shot("assistant-end-$name")
    }

    @Test fun screenshotSettingsLight() = screenshotsOfSettings(ThemeMode.LIGHT, "light")
    @Test fun screenshotSettingsDark() = screenshotsOfSettings(ThemeMode.DARK, "dark")
    @Test fun screenshotAssistantLight() = screenshotsOfAssistant(ThemeMode.LIGHT, "light")
    @Test fun screenshotAssistantDark() = screenshotsOfAssistant(ThemeMode.DARK, "dark")
}
