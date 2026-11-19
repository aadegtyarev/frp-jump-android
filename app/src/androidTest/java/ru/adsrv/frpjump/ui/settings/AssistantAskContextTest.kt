package ru.adsrv.frpjump.ui.settings

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.agent.AgentModule
import ru.adsrv.frpjump.agent.AgentProvider
import ru.adsrv.frpjump.agent.AgentReply
import ru.adsrv.frpjump.agent.AgentRequest
import ru.adsrv.frpjump.assistant.AssistantChatHistory
import ru.adsrv.frpjump.assistant.AssistantChatOwner
import ru.adsrv.frpjump.assistant.AssistantPrompts
import ru.adsrv.frpjump.assistant.AssistantProviderDraft
import ru.adsrv.frpjump.assistant.AssistantProviderSettings
import ru.adsrv.frpjump.assistant.AssistantProviderStore
import ru.adsrv.frpjump.assistant.chat.ChatQuote
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.settings.SettingsStore
import ru.adsrv.frpjump.store.AtomicTextFile
import ru.adsrv.frpjump.store.KeystoreSecretBox
import ru.adsrv.frpjump.store.SecretBox
import ru.adsrv.frpjump.store.TextStorage
import ru.adsrv.frpjump.ui.ApplyAssistantQuote
import ru.adsrv.frpjump.ui.FrpJumpTheme
import ru.adsrv.frpjump.ui.Routes
import ru.adsrv.frpjump.ui.askAssistant
import ru.adsrv.frpjump.ui.connections.askDraft
import java.util.Locale

/**
 * The tunnel's opening words (swipe or TalkBack action "Ask the assistant" on a tunnel card, R36b) and a terminal selection reach the model
 * with the owner's next message: the chat route's own [ApplyAssistantQuote] and the same entry ([askAssistant]) on a real chat screen and a
 * fake provider that records what it is sent. Cases: the chat never opened; open under another screen with a saved draft; open with an old
 * history; closed with a saved draft; a turn still running; the composer already holding text; a terminal quote.
 */
@RunWith(AndroidJUnit4::class)
class AssistantAskContextTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val args get() = InstrumentationRegistry.getArguments()
    private class Memory : TextStorage {
        @Volatile var value: String? = null
        override fun read() = value
        override fun write(text: String) { value = text }
        override fun delete() { value = null }
    }
    private val settings = SettingsStore(Memory()).apply { update { it.copy(assistantEnabled = true) } }
    private val store = AssistantProviderStore(Memory(), object : SecretBox {
        override fun seal(plain: String) = "synthetic-sealed"
        override fun open(sealed: String): String = error("Fake provider must not resolve credentials")
        override fun destroy() = Unit
    }, settings.assistantChat).apply { save(AssistantProviderDraft(AssistantProviderSettings(model = "synthetic"), null, "synthetic-key")) }
    private val requests = java.util.concurrent.CopyOnWriteArrayList<AgentRequest>()
    @Volatile private var hold: CompletableDeferred<Unit>? = null
    private val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val module = AgentModule(true, { settings.assistantAvailability.disableVersion }, { settings.assistantAvailability.enabled })
    private val id = java.util.UUID.randomUUID().toString()
    private val file by lazy { java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "test-askctx-$id.json") }
    private val box by lazy { KeystoreSecretBox("frpjump-test-askctx-$id") }
    private val historyScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** The history's Undo window, held open by the test: [expire] ends it, as the six seconds do. */
    private var expire: (() -> Unit)? = null
    private val undo = ru.adsrv.frpjump.undo.PendingUndo(
        timer = { _, block -> expire = block; { expire = null } },
        run = { block -> ownerScope.launch { block() } }, announce = { counts, undo -> undoNow = if (counts == null) null else undo })
    private var undoNow: (() -> Unit)? = null
    private val owner by lazy {
        AssistantChatOwner(ownerScope, settings, store, module, null, null, AssistantChatHistory(AtomicTextFile(file), box, historyScope), undo,
            lazy { AssistantPrompts("system", "summary") }, prepare = {
                AgentProvider { request -> requests += request; hold?.await(); AgentReply("Answer ${requests.size}") }
            }, realtime = { SystemClock.elapsedRealtime() })
    }
    private val quotes = MutableStateFlow<String?>(null)
    private lateinit var nav: NavHostController
    private lateinit var model: AssistantChatViewModel
    private val tunnel = Connection("c1", "alpha", 80, PortKind.WEB, customName = "panel")
    private val context get() = compose.activity
    private val prefix get() = askDraft(context, tunnel)
    private val question = "подскажи статус машины"

    @Before fun display() {
        val landscape = args.getString("composerOrientation") == "landscape"
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            @Suppress("DEPRECATION")
            it.resources.updateConfiguration(Configuration(it.resources.configuration).apply { setLocale(Locale.forLanguageTag(args.getString("chatLocale") ?: "en")) }, it.resources.displayMetrics)
        }
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        val until = SystemClock.uptimeMillis() + 5000
        while (compose.activity.resources.configuration.orientation != expected && SystemClock.uptimeMillis() < until) SystemClock.sleep(50)
        assertEquals(expected, compose.activity.resources.configuration.orientation)
        compose.setContent {
            FrpJumpTheme {
                nav = rememberNavController()
                NavHost(nav, "tunnels") {
                    composable("tunnels") { Text("Tunnels") }
                    composable("other") { Text("Other screen") }
                    composable(Routes.CHAT) {
                        val vm = viewModel { AssistantChatViewModel(settings, store, owner) }
                        androidx.compose.runtime.SideEffect { model = vm }
                        ApplyAssistantQuote(quotes, vm)
                        AssistantChatScreen(vm, onBack = { nav.popBackStack() }, onSetup = {})
                    }
                }
            }
        }
    }

    @After fun end() {
        hold?.complete(Unit)
        compose.activityRule.scenario.onActivity { activity ->
            androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        owner.shutdown(); ownerScope.cancel(); historyScope.cancel(); file.delete(); box.destroy()
    }

    private val route get() = compose.runOnUiThread { nav.currentDestination?.route }
    private val draft: String get() = owner.let { runCatching { model.conversation.state.value.draft }.getOrDefault("") }
    private fun field() = compose.onNodeWithTag("chat_input")
    private fun fieldText() = field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun fieldCursor() = field().fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
    private fun go(to: String) { compose.runOnUiThread { nav.navigate(to) }; compose.waitUntil(WAIT) { route == to } }
    private fun swipeAsk(text: String? = prefix) { compose.runOnUiThread { nav.askAssistant(quotes, text) }; compose.waitUntil(WAIT) { route == Routes.CHAT } }
    private fun loaded() = compose.waitUntil(WAIT) { runCatching { !model.conversation.state.value.loading }.getOrDefault(false) }
    private fun type(text: String) { val node = field(); if (compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) node.performScrollTo(); node.performTextInput(text) }
    private fun send() {
        val before = requests.size
        val send = compose.onNodeWithTag("chat_send")
        if (compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) send.performScrollTo()
        compose.waitForIdle(); SystemClock.sleep(300)
        send.performClick()
        compose.waitUntil(WAIT) { requests.size > before }
    }
    private fun lastUser() = requests.last().turns.last().user
    /** The text is in the composer with the cursor after it, as the owner sees it. */
    private fun assertShown(expected: String) {
        compose.waitUntil(WAIT) { runCatching { fieldText() == expected }.getOrDefault(false) }
        compose.waitUntil(WAIT) { runCatching { fieldCursor() == TextRange(expected.length) }.getOrDefault(false) }
        assertEquals(expected, draft)
    }

    @Test fun theSwipeFromTunnelsOnAChatNeverOpenedGivesThePrefixAndTheQuestionToTheModel() {
        swipeAsk(); loaded()
        assertShown(prefix)
        assertEquals(0, requests.size)
        type(question)
        compose.waitUntil(WAIT) { draft == prefix + question }
        send()
        assertEquals(1, requests.size)
        assertEquals(prefix + question, lastUser())
        assertTrue(lastUser().startsWith(prefix.trim()))
    }

    @Test fun theChatOpenUnderAnotherScreenWithASavedDraftKeepsItAndGetsThePrefixAfterIt() {
        swipeAsk(null); loaded()
        type("old draft")
        compose.waitUntil(WAIT) { draft == "old draft" }
        go("other")
        swipeAsk(); loaded()
        val expected = "old draft\n\n$prefix"
        assertShown(expected)
        type(question)
        compose.waitUntil(WAIT) { draft == expected + question }
        send()
        assertEquals(expected + question, lastUser())
    }

    @Test fun theChatClosedWithASavedDraftRestoresItAndGetsThePrefixAfterIt() {
        swipeAsk(null); loaded()
        type("old draft")
        compose.waitUntil(WAIT) { draft == "old draft" }
        compose.runOnUiThread { nav.popBackStack() }
        compose.waitUntil(WAIT) { route == "tunnels" }
        swipeAsk(); loaded()
        val expected = "old draft\n\n$prefix"
        assertShown(expected)
        type(question)
        compose.waitUntil(WAIT) { draft == expected + question }
        send()
        assertEquals(expected + question, lastUser())
    }

    @Test fun theChatWithAnOldHistoryGetsThePrefixInAFreshDraft() {
        swipeAsk(null); loaded()
        type("first question"); send()
        compose.waitUntil(WAIT) { model.conversation.state.value.turns.size == 1 && !model.conversation.state.value.busy }
        compose.runOnUiThread { nav.popBackStack() }
        compose.waitUntil(WAIT) { route == "tunnels" }
        compose.waitForIdle()
        swipeAsk(); loaded()
        assertShown(prefix)
        type(question)
        compose.waitUntil(WAIT) { draft == prefix + question }
        send()
        assertEquals(2, requests.size)
        assertEquals(prefix + question, lastUser())
    }

    @Test fun aTurnStillRunningHoldsThePrefixUntilItEndsThenTheQuestionCarriesIt() {
        hold = CompletableDeferred()
        swipeAsk(null); loaded()
        type("first question"); send()
        compose.waitUntil(WAIT) { model.conversation.state.value.busy }
        compose.runOnUiThread { nav.popBackStack() }
        compose.waitUntil(WAIT) { route == "tunnels" }
        swipeAsk()
        compose.runOnIdle { assertEquals(1, requests.size) }
        hold!!.complete(Unit)
        compose.waitUntil(WAIT) { !model.conversation.state.value.busy && model.conversation.state.value.turns.size == 1 }
        assertShown(prefix)
        type(question)
        compose.waitUntil(WAIT) { draft == prefix + question }
        send()
        assertEquals(prefix + question, lastUser())
    }

    @Test fun textAlreadyInTheComposerStaysAndThePrefixGoesAfterItWithTheChatOnScreen() {
        swipeAsk(null); loaded()
        type("typed before")
        compose.waitUntil(WAIT) { draft == "typed before" }
        swipeAsk()
        val expected = "typed before\n\n$prefix"
        assertShown(expected)
        type(question)
        compose.waitUntil(WAIT) { draft == expected + question }
        send()
        assertEquals(expected + question, lastUser())
    }

    @Test fun aClearWaitingForItsUndoDoesNotHideThePrefixAndTheSendInThatWindowCarriesIt() {
        swipeAsk(null); loaded()
        type("first question"); send()
        compose.waitUntil(WAIT) { model.conversation.state.value.turns.size == 1 && !model.conversation.state.value.busy }
        compose.runOnUiThread { model.clearHistory() }
        compose.waitUntil(WAIT) { model.conversation.state.value.clearing }
        compose.runOnUiThread { nav.popBackStack() }
        compose.waitUntil(WAIT) { route == "tunnels" }
        swipeAsk()
        // The Undo window is still open: the field is free and Send works, so the owner must see the prefix now.
        assertTrue(model.conversation.state.value.clearing)
        assertShown(prefix)
        type(question)
        compose.waitUntil(WAIT) { draft == prefix + question }
        send()
        assertEquals(2, requests.size)
        assertEquals(prefix + question, lastUser())
    }

    /** A chat with one answered turn whose Clear waits for its Undo, left by Back; then the swipe lands in that window. */
    private fun askInsideTheClearWindow() {
        swipeAsk(null); loaded()
        type("first question"); send()
        compose.waitUntil(WAIT) { model.conversation.state.value.turns.size == 1 && !model.conversation.state.value.busy }
        compose.runOnUiThread { model.clearHistory() }
        compose.waitUntil(WAIT) { model.conversation.state.value.clearing }
        compose.runOnUiThread { nav.popBackStack() }
        compose.waitUntil(WAIT) { route == "tunnels" }
        swipeAsk()
        assertShown(prefix)
    }

    @Test fun theClearCommittingAfterTheSwipeKeepsThePrefixInTheNewDraft() {
        askInsideTheClearWindow()
        compose.runOnUiThread { expire!!.invoke() }
        compose.waitUntil(WAIT) { !model.conversation.state.value.clearing }
        assertShown(prefix)
        assertEquals(0, model.conversation.state.value.turns.size)
        type(question)
        compose.waitUntil(WAIT) { draft == prefix + question }
        send()
        assertEquals(prefix + question, lastUser())
    }

    @Test fun anUndoAfterTheSwipeBringsTheHistoryBackAndKeepsThePrefixInTheDraft() {
        askInsideTheClearWindow()
        compose.runOnUiThread { undoNow!!.invoke() }
        compose.waitUntil(WAIT) { model.conversation.state.value.let { !it.clearing && !it.restoring && !it.loading && it.turns.size == 1 } }
        assertShown(prefix)
        type(question)
        compose.waitUntil(WAIT) { draft == prefix + question }
        send()
        assertEquals(prefix + question, lastUser())
    }

    @Test fun twoSwipesInARowGiveBothPrefixes() {
        swipeAsk(); loaded()
        assertShown(prefix)
        go("other")
        swipeAsk(prefix.replace("panel", "second"))
        val expected = prefix + "\n\n" + prefix.replace("panel", "second")
        assertShown(expected)
    }

    @Test fun aTerminalQuoteStillReachesTheModelWithTheQuestion() {
        val quote = ChatQuote.block("ls -l\ntotal 0", "[truncated]")!!
        swipeAsk(quote); loaded()
        assertShown(quote)
        type(question)
        compose.waitUntil(WAIT) { draft == quote + question }
        send()
        assertEquals(quote + question, lastUser())
    }

    private companion object { const val WAIT = 15_000L }
}
