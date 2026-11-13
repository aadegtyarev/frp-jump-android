package ru.adsrv.frpjump.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.assistant.chat.ChatQuote
import ru.adsrv.frpjump.settings.AppSettings
import java.util.Locale

/**
 * The assistant in the bottom navigation, on the app's own navigation pieces ([TabFrame], [assistantRoutes]) and the real chat
 * screen: shown only while the assistant is on, opens the chat over the section, Back returns, the provider screen works from
 * every entry, a terminal quote reaches the draft, and the five labels are not cut. The original preferences and chat file are restored.
 */
@RunWith(AndroidJUnit4::class)
class AssistantEntryNavTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = compose.activity.application as FrpJumpApp
    private var original: AppSettings? = null
    private lateinit var nav: NavHostController
    private fun text(id: Int) = compose.activity.getString(id)

    @Before fun display() {
        val args = InstrumentationRegistry.getArguments()
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
        compose.runOnUiThread { original = app.settings.settings.value; app.assistantQuote.value = null }
        runBlocking(Dispatchers.IO) { app.assistantHistory.clear() }
    }

    @After fun restore() {
        compose.runOnIdle { app.assistantQuote.value = null; original?.let { old -> app.settings.update { old } } }
        runBlocking(Dispatchers.IO) { app.assistantHistory.clear() }
    }

    private fun assistant(on: Boolean) = compose.runOnUiThread { app.settings.update { it.copy(assistantEnabled = on) } }

    @Composable private fun Section(tab: Tab, route: String) {
        TabFrame(tab, nav) { Box(Modifier.testTag("screen_$route")) { Text("Screen $route") } }
    }

    private fun host(startAssistant: Boolean = true) {
        assistant(startAssistant)
        compose.setContent {
            FrpJumpTheme {
                nav = rememberNavController()
                NavHost(nav, Routes.DEVICES) {
                    composable(Routes.DEVICES) { Section(Tab.DEVICES, "devices") }
                    composable(Routes.SETTINGS) { Section(Tab.SETTINGS, "settings") }
                    assistantRoutes(nav, app)
                }
            }
        }
        compose.waitForIdle()
    }

    private val navItem get() = compose.onNodeWithTag("nav_assistant")
    private val route: String? get() = compose.runOnUiThread { nav.currentDestination?.route }

    private fun openFromNav() {
        navItem.assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { route == Routes.CHAT }
        compose.onNodeWithTag("chat_title").assertIsDisplayed()
    }

    private fun assertNotCut(label: String, tag: String) {
        val node = compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).assertIsDisplayed()
        val results = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        assertTrue("no text layout for '$label'", results.isNotEmpty())
        val r = results.first()
        assertTrue("'$label' is cut", (0 until r.lineCount).none { r.isLineEllipsized(it) } && !r.didOverflowHeight && !r.didOverflowWidth)
    }

    @Test fun theItemFollowsTheSettingInBothDirections() {
        host(startAssistant = false)
        navItem.assertDoesNotExist()
        assistant(true); compose.waitForIdle()
        navItem.assertIsDisplayed()
        assistant(false); compose.waitForIdle()
        navItem.assertDoesNotExist()
        assistant(true); compose.waitForIdle()
        navItem.assertIsDisplayed()
    }

    @Test fun theChatOpensOverTheSectionAndBackReturnsToIt() {
        host()
        openFromNav()
        compose.onNodeWithTag("chat_back").performClick()
        compose.waitUntil(10_000) { route == Routes.DEVICES }
        compose.onNodeWithTag("screen_devices").assertIsDisplayed()
        // The system Back does the same.
        openFromNav()
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) { route == Routes.DEVICES }
        compose.onNodeWithTag("screen_devices").assertIsDisplayed()
    }

    @Test fun theItemOpensTheChatOverAnotherSectionToo() {
        host()
        compose.runOnUiThread { nav.navigate(Routes.SETTINGS) }
        compose.waitUntil(10_000) { route == Routes.SETTINGS }
        openFromNav()
        compose.onNodeWithTag("chat_back").performClick()
        compose.waitUntil(10_000) { route == Routes.SETTINGS }
    }

    @Test fun theProviderScreenWorksFromAChatOpenedFromTheNavigation() {
        host()
        openFromNav()
        // The crash: the provider screen looked up Settings' entry, which is not on the back stack from here.
        compose.runOnUiThread { nav.navigate(Routes.PROVIDER) }
        compose.waitUntil(10_000) { route == Routes.PROVIDER }
        compose.onNodeWithTag("assistant_root").assertIsDisplayed()
        compose.runOnIdle { assertEquals(Routes.PROVIDER, nav.currentDestination?.route) }
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(10_000) { route == Routes.CHAT }
    }

    @Test fun theProviderScreenStillWorksFromAChatOpenedThroughSettings() {
        host()
        compose.runOnUiThread { nav.navigate(Routes.SETTINGS) }
        compose.waitUntil(10_000) { route == Routes.SETTINGS }
        compose.runOnUiThread { nav.navigate(Routes.CHAT) }
        compose.waitUntil(10_000) { route == Routes.CHAT }
        compose.runOnUiThread { nav.navigate(Routes.PROVIDER) }
        compose.waitUntil(10_000) { route == Routes.PROVIDER }
        compose.onNodeWithTag("assistant_root").assertIsDisplayed()
    }

    @Test fun aWaitingQuoteReachesTheDraftOnceWithTheCursorAfterIt() {
        host()
        val quote = ChatQuote.block("ls -l\ntotal 0", text(R.string.chat_quote_truncated))!!
        compose.runOnUiThread { app.assistantQuote.value = quote; nav.openAssistant() }
        compose.waitUntil(10_000) { route == Routes.CHAT }
        compose.waitUntil(10_000) { app.assistantQuote.value == null }
        compose.onNodeWithTag("chat_input").assertTextContains("total 0", substring = true)
        compose.waitUntil(10_000) {
            compose.onNodeWithTag("chat_input").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange] == TextRange(quote.length)
        }
        compose.onNodeWithTag("chat_input").performTextInput("what is this")
        compose.waitUntil(10_000) { compose.onNodeWithTag("chat_input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text == quote + "what is this" }
        // Opening again with no quote waiting keeps the draft as it is.
        compose.onNodeWithTag("chat_back").performClick()
        compose.waitUntil(10_000) { route == Routes.DEVICES }
        assertNull(app.assistantQuote.value)
        openFromNav()
        compose.waitUntil(10_000) { compose.onNodeWithTag("chat_input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text == quote + "what is this" }
    }

    @Test fun noSelectionJustOpensTheChat() {
        host()
        compose.runOnUiThread { app.assistantQuote.value = null; nav.openAssistant() }
        compose.waitUntil(10_000) { route == Routes.CHAT }
        compose.onNodeWithTag("chat_title").assertIsDisplayed()
        compose.waitForIdle()
        assertEquals("", compose.onNodeWithTag("chat_input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    @Test fun theFiveLabelsAreNotCut() {
        host()
        for ((tag, label) in listOf("nav_devices" to R.string.tab_devices, "nav_connections" to R.string.tab_connections,
            "nav_sessions" to R.string.tab_sessions, "nav_settings" to R.string.tab_settings, "nav_assistant" to R.string.tab_assistant)) {
            compose.onNodeWithTag(tag).assertIsDisplayed()
            assertNotCut(text(label), tag)
        }
    }
}
