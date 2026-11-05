package ru.adsrv.frpjump.ui.settings

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.*
import ru.adsrv.frpjump.settings.SettingsStore
import ru.adsrv.frpjump.settings.ThemeMode
import ru.adsrv.frpjump.store.SecretBox
import ru.adsrv.frpjump.store.TextStorage
import ru.adsrv.frpjump.ui.FrpJumpTheme

@RunWith(AndroidJUnit4::class)
class AssistantChatScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val args get() = InstrumentationRegistry.getArguments()
    private class Memory : TextStorage {
        var value: String? = null
        override fun read() = value
        override fun write(text: String) { value = text }
        override fun delete() { value = null }
    }
    private val settings = SettingsStore(Memory()).apply { update { it.copy(assistantEnabled = true) } }
    private val providerFile = Memory()
    private val store = AssistantProviderStore(providerFile, object : SecretBox {
        override fun seal(plain: String) = "synthetic-sealed"
        override fun open(sealed: String): String = error("Fake provider must not resolve credentials")
        override fun destroy() = Unit
    }).apply { save(AssistantProviderDraft(AssistantProviderSettings(model = "synthetic"), null, "synthetic-key")) }
    private var mode = "answer"
    private var calls = 0
    private val requests = mutableListOf<AgentRequest>()
    private var owner: AssistantConversation? = null
    private val module = AgentModule(true, { settings.assistantAvailability.disableVersion }, { settings.assistantAvailability.enabled })
    private fun vm() = AssistantChatViewModel(settings, store, module) {
        AgentProvider { request ->
            requests += request; calls++
            when (mode) {
                "wait" -> awaitCancellation()
                "error" -> error("synthetic-private-error")
                else -> AgentReply("Complete answer $calls")
            }
        }
    }
    @Before fun display() {
        // This suite is runnable with normal discovery too; the safe explicit runner is required
        // by the operator's device procedure, not by an unconditional wrong-runner test failure.
        val landscape = args.getString("composerOrientation") == "landscape"
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        val until = SystemClock.uptimeMillis() + 5000
        while (compose.activity.resources.configuration.orientation != expected && SystemClock.uptimeMillis() < until) SystemClock.sleep(50)
        assertEquals(expected, compose.activity.resources.configuration.orientation)
        assertEquals(args.getString("composerFontScale")?.toFloat() ?: 1f, compose.activity.resources.configuration.fontScale, .01f)
    }
    @Composable private fun Content() {
        val context = LocalContext.current
        val configuration = Configuration(LocalConfiguration.current).apply {
            setLocale(Locale.forLanguageTag(args.getString("chatLocale") ?: "en"))
        }
        val localized = context.createConfigurationContext(configuration)
        CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration) {
        FrpJumpTheme(mode = if (args.getString("chatDark") == "true") ThemeMode.DARK else ThemeMode.LIGHT) {
            val nav = rememberNavController()
            NavHost(nav, "settings") {
                composable("settings") {
                    androidx.compose.foundation.layout.Column {
                        Text("Outside chat")
                        AssistantChatEntry { nav.navigate("chat") }
                    }
                }
                composable("chat") {
                    val vm = viewModel { vm() }
                    SideEffect { owner = vm.conversation }
                    AssistantChatScreen(vm, onBack = { nav.popBackStack() },
                        onSetup = { nav.navigate("setup") })
                }
                composable("setup") { Text("Synthetic provider setup") }
            }
        }
    }
    }
    private fun install(activity: ComponentActivity) { activity.setContent { Content() } }
    private fun install() {
        compose.activityRule.scenario.onActivity { install(it) }
        compose.onNodeWithTag("assistant_chat_entry").assertIsDisplayed().performClick()
    }
    private fun input() = button("chat_input")
    private fun button(tag: String): SemanticsNodeInteraction {
        val node = compose.onNodeWithTag(tag)
        // Scroll only when the compact layout supplies a scrolling parent.
        if (compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) node.performScrollTo()
        return node
    }
    private fun awaitIdle() { compose.waitUntil(5000) { owner?.state?.value?.busy == false } }

    @Test fun sendFollowupStopErrorAndRecovery() {
        install()
        compose.runOnIdle { assertEquals(0, calls) }
        input().performTextInput("First message")
        button("chat_send").performClick(); awaitIdle()
        compose.runOnIdle { assertEquals("Complete answer 1", owner!!.state.value.turns.single().answer) }
        button("chat_history").performScrollToNode(hasText("Complete answer 1"))
        compose.onNodeWithText("Complete answer 1").assertIsDisplayed()
        input().performTextInput("Second message")
        button("chat_send").performClick(); awaitIdle()
        compose.runOnIdle { assertEquals(2, requests[1].turns.size); assertEquals("First message", requests[1].turns.first().user); mode = "wait" }
        input().performTextInput("Recover this text")
        button("chat_send").performClick()
        compose.waitUntil(5000) { owner!!.state.value.busy }
        button("chat_stop").assertIsDisplayed().performClick(); awaitIdle()
        input().assertTextContains("Recover this text")
        compose.runOnIdle { mode = "error" }
        button("chat_send").performClick(); awaitIdle()
        button("chat_error").assertIsDisplayed()
        input().assertTextContains("Recover this text")
        compose.runOnIdle { mode = "answer" }
        button("chat_send").performClick(); awaitIdle()
        compose.runOnIdle { assertEquals(3, owner!!.state.value.turns.size); assertEquals(3, requests.last().turns.size) }
    }

    @Test fun recreationRetainsActualBackStackOwnerAndPopDiscards() {
        install()
        input().performTextInput("First")
        button("chat_send").performClick(); awaitIdle()
        input().performTextInput("Draft only in memory")
        lateinit var before: AssistantConversation
        compose.runOnIdle { before = owner!! }
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { install(it) }
        compose.waitForIdle()
        compose.runOnIdle { assertSame(before, owner); assertEquals(1, owner!!.state.value.turns.size) }
        input().assertTextContains("Draft only in memory")
        compose.onNodeWithTag("chat_back").performClick()
        compose.onNodeWithText("Outside chat").assertExists()
        compose.waitUntil(5000) { before.state.value == ChatState() }
    }

    @Test fun disabledSetupHasDirectRecovery() {
        settings.update { it.copy(assistantEnabled = false) }
        install()
        button("chat_setup").assertIsDisplayed().performClick()
        compose.onNodeWithText("Synthetic provider setup").assertExists()
        compose.runOnIdle { assertEquals(0, calls) }
    }
    @Test fun unreadableProviderHasDirectSetupWithoutRequests() {
        providerFile.value = "unreadable synthetic document"
        store.reload()
        install()
        button("chat_setup").assertIsDisplayed().performClick()
        compose.onNodeWithText("Synthetic provider setup").assertExists()
        compose.runOnIdle { assertEquals(0, calls) }
    }

}
