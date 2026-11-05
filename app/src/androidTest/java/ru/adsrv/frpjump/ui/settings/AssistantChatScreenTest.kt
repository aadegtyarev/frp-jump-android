package ru.adsrv.frpjump.ui.settings

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
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
    }, settings.assistantChat).apply { save(AssistantProviderDraft(AssistantProviderSettings(model = "synthetic"), null, "synthetic-key")) }
    private val prepEntered = java.util.concurrent.CountDownLatch(1)
    private val prepRelease = java.util.concurrent.CountDownLatch(1)
    private val formattedAnswer = "A **bold** year and *quiet* detail.\n\n- First item\n- Второй пункт\n\n`val answer = 42`\n\n```\n  code stays\n```"
    private val resourceAnswer = "[link](https://example.test) ![image](https://example.test/image) <b>literal</b>\n\n<img src=\"https://example.test/img\">"
    private val codeAnswer = "`C:\\`\n\n```\n  command \\ path\n    next\n```"
    private val longAnswer = (1..120).joinToString("\n") { "Line $it — длинный synthetic answer 🌍" }
    private var copySelection: (() -> Unit)? = null
    private val selectionToolbar = object : TextToolbar {
        override val status: TextToolbarStatus get() = if (copySelection == null) TextToolbarStatus.Hidden else TextToolbarStatus.Shown
        override fun hide() { copySelection = null }
        override fun showMenu(rect: Rect, onCopyRequested: (() -> Unit)?, onPasteRequested: (() -> Unit)?,
                              onCutRequested: (() -> Unit)?, onSelectAllRequested: (() -> Unit)?) {
            copySelection = onCopyRequested
        }
    }
    private var mode = "answer"
    private var calls = 0
    private val requests = mutableListOf<AgentRequest>()
    private var owner: AssistantConversation? = null
    private val module = AgentModule(true, { settings.assistantAvailability.disableVersion }, { settings.assistantAvailability.enabled })
    private fun vm() = AssistantChatViewModel(settings, store, module) {
        if (mode == "prepare") { prepEntered.countDown(); check(prepRelease.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
        AgentProvider { request ->
            requests += request; calls++
            when (mode) {
                "wait" -> awaitCancellation()
                "error" -> error("synthetic-private-error")
                "formatted" -> AgentReply(formattedAnswer)
                "long" -> AgentReply(longAnswer)
                "resources" -> AgentReply(resourceAnswer)
                "code" -> AgentReply(codeAnswer)
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
        CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration, LocalTextToolbar provides selectionToolbar) {
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
    private fun awaitMarkdown() {
        compose.waitUntil(5000) { compose.onAllNodesWithTag("assistant_markdown_rendered").fetchSemanticsNodes().isNotEmpty() }
    }

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

    @Test fun formattedAndLongAnswersRemainSelectableAndOriginalHistoryIsUnchanged() {
        mode = "formatted"
        install()
        input().performTextInput("Literal **question**")
        button("chat_send").performClick(); awaitIdle()
        compose.runOnIdle { assertEquals(formattedAnswer, owner!!.state.value.turns.single().answer) }
        compose.activityRule.scenario.onActivity {
            (it.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(it.window.decorView.windowToken, 0)
        }
        compose.waitForIdle(); awaitMarkdown()
        button("chat_history").performScrollToNode(hasText("A bold year", substring = true))
        compose.onNodeWithText("Literal **question**").assertExists()
        val answer = compose.onNodeWithText("A bold year", substring = true)
        answer.assertIsDisplayed().assertTextEquals("A bold year and quiet detail.")
        compose.onNodeWithText("First item").assertExists()
        compose.onNodeWithText("Второй пункт").assertExists()
        compose.onNodeWithText("-").assertDoesNotExist()
        compose.onNodeWithText("val answer = 42", substring = true).assertExists()
        compose.onNodeWithText("  code stays\n", substring = true).assertExists()
        if (args.getString("captureChatScreenshot") == "true") {
            compose.waitForIdle()
            val name = "chat-${args.getString("chatLocale") ?: "en"}-${args.getString("chatDark") ?: "false"}-${args.getString("composerOrientation") ?: "portrait"}-${args.getString("composerFontScale") ?: "1"}.png"
            val target = java.io.File(compose.activity.getExternalFilesDir(null), name)
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
                target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        }
        answer.performTouchInput { longClick(Offset(20f, 20f)) }
        compose.waitUntil(5000) { copySelection != null }
        compose.runOnIdle { assertNotNull(copySelection); mode = "long" }
        input().performTextInput("Next")
        button("chat_send").performClick(); awaitIdle()
        compose.runOnIdle {
            assertEquals(longAnswer, owner!!.state.value.turns.last().answer)
            assertEquals(formattedAnswer, requests.last().turns.first().exchanges.last().reply.text)
        }
        button("chat_history").performScrollToNode(hasText("Line 120", substring = true))
        compose.waitUntil(5000) { compose.onAllNodesWithTag("assistant_markdown_loading").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Line 120", substring = true).assertExists().assertTextEquals(longAnswer)
    }

    @Test fun resourceMarkupIsVisibleAndHasNoClickAction() {
        mode = "resources"
        install()
        input().performTextInput("Show resources")
        button("chat_send").performClick(); awaitIdle(); awaitMarkdown()
        button("chat_history").performScrollToNode(hasText("[link](https://example.test)", substring = true))
        compose.onNodeWithText("[link](https://example.test)", substring = true)
            .assertExists().assertHasNoClickAction()
            .assertTextContains("![image](https://example.test/image)", substring = true)
            .assertTextContains("<b>literal</b>", substring = true)
        compose.onNodeWithText("<img src=\"https://example.test/img\">", substring = true)
            .assertExists().assertHasNoClickAction()
        compose.runOnIdle { assertEquals(resourceAnswer, owner!!.state.value.turns.single().answer) }
    }

    @Test fun codePayloadIsSelectableAndOriginalIsSentInFollowup() {
        mode = "code"
        install()
        input().performTextInput("Code please")
        button("chat_send").performClick(); awaitIdle(); awaitMarkdown()
        button("chat_history").performScrollToNode(hasText("C:\\", substring = true))
        val inline = compose.onNodeWithText("C:\\", substring = true).assertIsDisplayed()
        compose.onNodeWithText("  command \\ path\n    next\n", substring = true).assertExists()
        inline.performTouchInput { longClick(Offset(20f, 20f)) }
        compose.waitUntil(5000) { copySelection != null }
        compose.runOnIdle { assertNotNull(copySelection); mode = "answer" }
        input().performTextInput("Next")
        button("chat_send").performClick(); awaitIdle()
        compose.runOnIdle {
            assertEquals(codeAnswer, owner!!.state.value.turns.first().answer)
            assertEquals(codeAnswer, requests.last().turns.first().exchanges.last().reply.text)
        }
    }

    @Test fun parsingFailureShowsSelectableOriginalAnswer() {
        val original = "Raw **answer** with \\ and https://example.test"
        compose.activityRule.scenario.onActivity { activity ->
            activity.setContent {
                CompositionLocalProvider(LocalTextToolbar provides selectionToolbar) {
                    FrpJumpTheme(mode = ThemeMode.LIGHT) {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            AssistantAnswerMarkdown(original, com.mikepenz.markdown.model.State.Error(IllegalStateException("synthetic")))
                        }
                    }
                }
            }
        }
        val text = compose.onNodeWithText(original).assertIsDisplayed().assertHasNoClickAction()
        text.performTouchInput { longClick(Offset(20f, 20f)) }
        compose.waitUntil(5000) { copySelection != null }
        compose.runOnIdle { assertNotNull(copySelection) }
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
        button("chat_back").performClick()
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

    @Test fun popDuringPreparationAndReopenKeepsGlobalSlotWithoutOldText() {
        mode = "prepare"
        install()
        input().performTextInput("Private old draft")
        button("chat_send").performClick()
        assertTrue(prepEntered.await(3, java.util.concurrent.TimeUnit.SECONDS))
        try {
            button("chat_back").performClick()
            compose.onNodeWithTag("assistant_chat_entry").performClick()
            button("chat_stop").assertIsDisplayed().assertIsNotEnabled()
            compose.onNodeWithText("Private old draft").assertDoesNotExist()
            compose.runOnIdle { assertEquals(0, calls); assertTrue(owner!!.state.value.turns.isEmpty()) }
        } finally { prepRelease.countDown() }
        awaitIdle()
        compose.runOnIdle { mode = "answer" }
        input().performTextInput("New draft")
        button("chat_send").performClick(); awaitIdle()
        compose.runOnIdle { assertEquals(1, calls); assertEquals("New draft", owner!!.state.value.turns.single().user) }
        val russian = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag("ru")) }
        val resources = compose.activity.createConfigurationContext(russian).resources
        val hint = resources.getString(ru.adsrv.frpjump.R.string.assistant_provider_stage_hint)
        assertTrue(hint.contains(resources.getString(ru.adsrv.frpjump.R.string.assistant_provider_models)))
        assertTrue(hint.contains(resources.getString(ru.adsrv.frpjump.R.string.chat_send)))
    }

}
