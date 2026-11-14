package ru.adsrv.frpjump.ui.settings

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.*
import ru.adsrv.frpjump.settings.ChatTextSize
import ru.adsrv.frpjump.settings.SettingsStore
import ru.adsrv.frpjump.store.SecretBox
import ru.adsrv.frpjump.store.TextStorage
import ru.adsrv.frpjump.ui.FrpJumpTheme

/**
 * The chat's own text size: the feed (answers, the user's messages) follows the setting on top of the system font scale while the
 * top bar and the message field keep the system size, and the Settings control announces, shows and saves the value.
 * Production chat content and conversation with a fake provider; portrait, font scale and language come from the runner.
 */
@RunWith(AndroidJUnit4::class)
class AssistantChatTextSizeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val args get() = InstrumentationRegistry.getArguments()
    private class Memory : TextStorage {
        @Volatile var value: String? = null
        override fun read() = value
        override fun write(text: String) { value = text }
        override fun delete() { value = null }
    }
    private val settingsFile = Memory()
    private val settings = SettingsStore(settingsFile).apply { update { it.copy(assistantEnabled = true) } }
    private val store = AssistantProviderStore(Memory(), object : SecretBox {
        override fun seal(plain: String) = "synthetic-sealed"
        override fun open(sealed: String): String = error("Fake provider must not resolve credentials")
        override fun destroy() = Unit
    }, settings.assistantChat).apply { save(AssistantProviderDraft(AssistantProviderSettings(model = "synthetic"), null, "synthetic-key")) }
    private val prompts = lazy { AssistantPrompts.load(InstrumentationRegistry.getInstrumentation().targetContext.assets::open) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val module = AgentModule(true, { settings.assistantAvailability.disableVersion }, { settings.assistantAvailability.enabled })
    private val conversation = AssistantConversation(scope, { settings.assistantAvailability }, { store.state.value }, {
        AgentProvider { AgentReply("Synthetic answer") }
    }, store::requireRevision, module, prompts = prompts, confirmations = ru.adsrv.frpjump.assistant.chat.ConfirmationSupport(
        policy = { ru.adsrv.frpjump.settings.ConfirmationPolicy() }, persistOff = { true }))
    private val chosenLocale get() = Locale.forLanguageTag(args.getString("chatLocale") ?: "en")

    @Before fun display() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        val until = SystemClock.uptimeMillis() + 5000
        while (compose.activity.resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT && SystemClock.uptimeMillis() < until) SystemClock.sleep(50)
        assertEquals(args.getString("composerFontScale")?.toFloat() ?: 1f, compose.activity.resources.configuration.fontScale, .01f)
    }
    @After fun cleanup() { conversation.close(); scope.cancel() }

    private fun localizedContext(): android.content.Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(chosenLocale) })
    }
    private fun show(content: @Composable () -> Unit) = compose.setContent {
        val context = remember { localizedContext() }
        CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration) { FrpJumpTheme { content() } }
    }
    private fun height(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().size.height
    private fun heightOfText(text: String) = height(compose.onNodeWithText(text, substring = true, useUnmergedTree = true))

    @Test fun feedTextFollowsTheSettingWhileTheTopBarAndTheFieldKeepTheSystemSize() {
        var percent by mutableStateOf(100)
        show {
            CompositionLocalProvider(LocalChatTextScale provides ChatTextSize.factor(percent)) {
                AssistantChatContent(conversation, true, remember { store.currentSnapshot }, onBack = {}, onSetup = {})
            }
        }
        compose.runOnIdle { conversation.edit("Synthetic question"); conversation.send() }
        compose.waitUntil(15_000) { conversation.state.value.let { !it.busy && it.turns.isNotEmpty() } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Synthetic answer", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val answer = heightOfText("Synthetic answer"); val user = heightOfText("Synthetic question")
        val title = height(compose.onNodeWithTag("chat_title")); val field = height(compose.onNodeWithTag("chat_input")); val send = height(compose.onNodeWithTag("chat_send"))
        compose.runOnIdle { percent = 200 }
        compose.waitForIdle()
        // Android 14+ scales text nonlinearly at large factors, so "grows" means clearly bigger, not exactly double.
        val answer200 = heightOfText("Synthetic answer"); val user200 = heightOfText("Synthetic question")
        assertTrue("answer grows: $answer -> $answer200", answer200 >= answer * 1.4)
        assertTrue("the user's message grows: $user -> $user200", user200 >= user * 1.4)
        assertEquals("the top bar keeps the system size", title, height(compose.onNodeWithTag("chat_title")))
        assertEquals("the message field keeps the system size", field, height(compose.onNodeWithTag("chat_input")))
        assertEquals("Send keeps the system size", send, height(compose.onNodeWithTag("chat_send")))
        compose.runOnIdle { percent = 90 }
        compose.waitForIdle()
        assertTrue("answer shrinks", heightOfText("Synthetic answer") < answer)
        assertEquals(title, height(compose.onNodeWithTag("chat_title")))
        assertEquals(field, height(compose.onNodeWithTag("chat_input")))
    }

    @Test fun settingsControlAnnouncesShowsAndSavesTheValue() {
        val saved = mutableListOf<Int>()
        var percent by mutableStateOf(settings.settings.value.assistantChatTextSize)
        show { AssistantChatTextSizeSetting(percent) { saved += it; settings.setAssistantChatTextSize(it); percent = settings.settings.value.assistantChatTextSize } }
        val slider = compose.onNodeWithTag("chat_text_size_slider")
        assertEquals("100%", slider.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        assertEquals(localizedContext().getString(R.string.chat_text_size), slider.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.single())
        compose.onNodeWithTag("chat_text_size_value").assertTextEquals("100%")
        val trackDp = with(compose.density) { height(compose.onNodeWithTag("chat_text_size_track")).toDp().value }
        assertTrue("48 dp target: $trackDp dp", trackDp >= 47.5f)
        val preview = height(compose.onNodeWithTag("chat_text_size_preview"))
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(3f) }
        compose.waitForIdle()
        assertEquals(listOf(130), saved)
        compose.onNodeWithTag("chat_text_size_value").assertTextEquals("130%")
        assertEquals("130%", slider.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        assertTrue("the preview follows the value", height(compose.onNodeWithTag("chat_text_size_preview")) > preview)
        assertTrue(settingsFile.value!!.contains(""""assistant_chat_text_size":130"""))
        assertEquals(130, SettingsStore(settingsFile).settings.value.assistantChatTextSize)
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(6f) }
        compose.waitForIdle()
        compose.onNodeWithTag("chat_text_size_value").assertTextEquals("200%")
        assertEquals(200, settings.settings.value.assistantChatTextSize)
    }
}
