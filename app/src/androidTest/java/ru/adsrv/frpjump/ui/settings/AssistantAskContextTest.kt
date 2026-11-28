package ru.adsrv.frpjump.ui.settings

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import ru.adsrv.frpjump.ui.FrpJumpTheme
import ru.adsrv.frpjump.ui.Routes
import ru.adsrv.frpjump.ui.openAssistant
import java.util.Locale

/** The shared chat carries removable selected context only with an admitted manual Send. */
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
                AgentProvider { request -> requests += request; hold?.await(); AgentReply(if (request.turns.last().user.contains("[tunnel:synthetic]")) "Answer\n\n[tunnel:synthetic]" else "Answer ${requests.size}") }
            }, realtime = { SystemClock.elapsedRealtime() })
    }
    private lateinit var nav: NavHostController
    private lateinit var model: AssistantChatViewModel
    private val tunnel = Connection("c1", "alpha", 80, PortKind.WEB, customName = "panel")
    private val context get() = compose.activity
    private val attachment get() = ru.adsrv.frpjump.assistant.chat.ChatAttachment("tunnel", "panel · alpha · Work", profileId = "p1", device = "alpha", port = 80,
        connectionId = "c1", reference = "[tunnel:synthetic]", tunnelKind = "WEB")
    private val question = "подскажи статус машины"

    @Before fun display() {
        val landscape = args.getString("composerOrientation") == "landscape"
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            @Suppress("DEPRECATION")
            it.resources.updateConfiguration(Configuration(it.resources.configuration).apply { setLocale(Locale.forLanguageTag(args.getString("chatLocale") ?: "en")); fontScale = args.getString("composerFontScale")?.toFloatOrNull() ?: 1f }, it.resources.displayMetrics)
        }
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        val until = SystemClock.uptimeMillis() + 5000
        while (compose.activity.resources.configuration.orientation != expected && SystemClock.uptimeMillis() < until) SystemClock.sleep(50)
        assertEquals(expected, compose.activity.resources.configuration.orientation)
        compose.setContent {
            FrpJumpTheme {
              androidx.compose.material3.Surface(color = ru.adsrv.frpjump.ui.AppTheme.colors.ground, contentColor = ru.adsrv.frpjump.ui.AppTheme.colors.ink) {
                nav = rememberNavController()
                NavHost(nav, "tunnels") {
                    composable("tunnels") { Text("Tunnels") }
                    composable("other") { Text("Other screen") }
                    composable(Routes.CHAT) {
                        val vm = viewModel { AssistantChatViewModel(settings, store, owner) }
                        androidx.compose.runtime.SideEffect { model = vm }
                        AssistantChatScreen(vm, onBack = { nav.popBackStack() }, onSetup = {})
                    }
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
    private fun swipeAsk(item: ru.adsrv.frpjump.assistant.chat.ChatAttachment? = attachment) { compose.runOnUiThread { if (item != null) assertTrue(owner.attach(item)); nav.openAssistant() }; compose.waitUntil(WAIT) { route == Routes.CHAT } }
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

    @Test fun attachmentIsVisibleBeforeSendAndIncludedInTheNextRequest() {
        swipeAsk(); loaded(); assertShown("")
        compose.onNodeWithTag("chat_attachments").assertExists()
        assertTrue(requests.isEmpty())
        type(question); assertShown(question)
        compose.waitForIdle(); SystemClock.sleep(300)
        val tag = listOf(args.getString("composerOrientation") ?: "portrait", args.getString("composerFontScale") ?: "1.0", args.getString("chatLocale") ?: "en").joinToString("-")
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { image ->
            java.io.File(compose.activity.getExternalFilesDir(null), "context-composer-$tag.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        }
        send()
        assertTrue(lastUser().contains(question)); assertTrue(lastUser().contains("[tunnel:synthetic]"))
        compose.waitUntil(WAIT) { owner.attachments.value.isEmpty() }
        compose.onNodeWithTag("chat_sent_attachment").assertExists()
        compose.waitUntil(WAIT) { !model.conversation.state.value.busy }
        compose.onNodeWithText(context.getString(R.string.chat_open_tunnel, attachment.label)).assertExists().performClick()
    }

    @Test fun draftAndAttachmentSurviveBackAndReentry() {
        swipeAsk(null); loaded(); type("old draft"); go("other"); swipeAsk(); assertShown("old draft")
        compose.runOnUiThread { nav.popBackStack() }; compose.waitUntil(WAIT) { route == "tunnels" }
        swipeAsk(null); loaded(); assertShown("old draft")
        compose.onNodeWithTag("chat_attachments").assertExists(); send()
        assertTrue(lastUser().contains("old draft")); assertTrue(lastUser().contains("[tunnel:synthetic]"))
    }

    @Test fun attachmentWhileBusyWaitsForTheNextManualSend() {
        hold = CompletableDeferred(); swipeAsk(null); loaded(); type("first"); send()
        compose.waitUntil(WAIT) { model.conversation.state.value.busy }
        compose.runOnUiThread { nav.popBackStack() }; compose.waitUntil(WAIT) { route == "tunnels" }; swipeAsk()
        assertEquals(1, requests.size); hold!!.complete(Unit)
        compose.waitUntil(WAIT) { !model.conversation.state.value.busy }
        compose.onNodeWithTag("chat_attachments").assertExists(); type(question); send()
        assertTrue(lastUser().contains("[tunnel:synthetic]"))
    }

    @Test fun clearUndoKeepsPendingContext() {
        swipeAsk(null); loaded(); type("first"); send()
        compose.waitUntil(WAIT) { model.conversation.state.value.turns.size == 1 && !model.conversation.state.value.busy }
        compose.runOnUiThread { model.clearHistory() }; compose.waitUntil(WAIT) { model.conversation.state.value.clearing }
        compose.runOnUiThread { nav.popBackStack() }; compose.waitUntil(WAIT) { route == "tunnels" }; swipeAsk()
        compose.onNodeWithTag("chat_attachments").assertExists()
        compose.runOnUiThread { undoNow!!.invoke() }
        compose.waitUntil(WAIT) { !model.conversation.state.value.clearing && !model.conversation.state.value.restoring }
        type(question); send(); assertTrue(lastUser().contains("[tunnel:synthetic]"))
    }

    @Test fun removedAttachmentDoesNotReachProviderAndDistinctProfilesRemain() {
        swipeAsk(); loaded(); compose.runOnUiThread { assertTrue(owner.attach(attachment.copy(profileId = "p2", reference = "[tunnel:other]"))) }
        compose.onNodeWithTag("chat_attachment_details").performClick()
        compose.onNodeWithTag("chat_attachment_sheet_remove_0").performClick()
        androidx.test.espresso.Espresso.pressBack()
        type(question); send()
        assertTrue(!lastUser().contains("[tunnel:synthetic]")); assertTrue(lastUser().contains("[tunnel:other]"))
    }

    @Test fun selectedTerminalTextIsRemovableContext() {
        val quote = ChatQuote.block("ls -l\ntotal 0", "[truncated]")!!
        swipeAsk(ru.adsrv.frpjump.assistant.chat.ChatAttachment("terminal_selection", "Terminal", quote = quote)); loaded()
        assertShown(""); compose.onNodeWithTag("chat_attachment_details").performClick()
        compose.onNodeWithTag("chat_attachment_quote_0").assertExists()
        androidx.test.espresso.Espresso.pressBack()
        type(question); send()
        assertTrue(lastUser().contains("total 0")); assertTrue(lastUser().contains(question))
    }

    private companion object { const val WAIT = 15_000L }
}
