package ru.adsrv.frpjump.ui.settings

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.*
import ru.adsrv.frpjump.settings.SettingsStore
import ru.adsrv.frpjump.settings.assistantTools
import ru.adsrv.frpjump.store.SecretBox
import ru.adsrv.frpjump.store.TextStorage
import ru.adsrv.frpjump.ui.FrpJumpTheme

/**
 * Р56: the "Turn on" / "No thanks" buttons under a reply that asked for a tool that is off. The production chat content, conversation,
 * tool factory, kernel, Tools binding and press handler with a fake provider; portrait, font scale and language come from the runner.
 */
@RunWith(AndroidJUnit4::class)
class AssistantToolRequestScreenTest {
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
    private val prompts = lazy { AssistantPrompts.load(InstrumentationRegistry.getInstrumentation().targetContext.assets::open) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requests = java.util.concurrent.CopyOnWriteArrayList<AgentRequest>()
    /** The tools the first Send of a test asks for, in order; the second reply of an attempt is the answer. */
    private var asks: List<ToolCall> = emptyList()
    private val module = AgentModule(true, { settings.assistantAvailability.disableVersion }, { settings.assistantAvailability.enabled })
    private val conversation = AssistantConversation(scope, { settings.assistantAvailability }, { store.state.value }, {
        AgentProvider { request ->
            requests += request
            val done = request.turns.last().exchanges.size
            if (request.turns.size == 1 && done < asks.size) AgentReply(calls = listOf(asks[done])) else AgentReply("Answer ${request.turns.size}")
        }
    }, store::requireRevision, module, prompts = prompts, confirmations = ru.adsrv.frpjump.assistant.chat.ConfirmationSupport(
        policy = { ru.adsrv.frpjump.settings.ConfirmationPolicy(global = ru.adsrv.frpjump.settings.ConfirmationMode.OFF) }, persistOff = { true }))
    private val binding = AssistantToolsBinding(settings, scope)
    private val request = AssistantToolRequest(settings, binding, scope) { message -> conversation.sendOwnerMessage(message) }
    private val locale get() = Locale.forLanguageTag(args.getString("chatLocale") ?: "en")
    private val russian get() = locale.language == "ru"
    private val enabled = mutableStateOf(true)

    private fun ask(id: String, tool: String) = ToolCall(id, "request_tool", buildJsonObject { put("name", tool); put("why", "synthetic reason") })

    @Before fun display() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        val until = SystemClock.uptimeMillis() + 5000
        while (compose.activity.resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT && SystemClock.uptimeMillis() < until) SystemClock.sleep(50)
        assertEquals(args.getString("composerFontScale")?.toFloat() ?: 1f, compose.activity.resources.configuration.fontScale, .01f)
    }
    @After fun cleanup() { conversation.close(); scope.cancel() }

    private fun show() {
        compose.setContent {
            val context = remember { localizedContext() }
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides context.resources.configuration) {
                FrpJumpTheme {
                    val current by settings.settings.collectAsState()
                    AssistantChatContent(conversation, enabled.value, store.state.value, onBack = {}, onSetup = {},
                        tools = current.assistantTools(), onTurnOn = request::turnOn)
                }
            }
        }
    }
    private fun send(text: String = "question") {
        compose.runOnIdle { conversation.edit(text); conversation.send() }
        compose.waitUntil(15_000) { conversation.state.value.let { !it.busy && it.turns.isNotEmpty() } }
    }
    private fun tag(kind: String, tool: AssistantToolId) = "chat_tool_request_${kind}${tool.wire}"
    /** A context of the chosen language built from the app context (an Activity's own may keep the device language). */
    private fun localizedContext(): android.content.Context {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(locale) })
    }
    private fun text(id: Int, vararg format: Any): String = localizedContext().getString(id, *format)
    private fun title(tool: AssistantToolId) = text(tool.title())
    private fun shown(kind: String, tool: AssistantToolId) = compose.onNodeWithTag(tag(kind, tool)).performScrollTo().assertIsDisplayed()
    private fun gone(tool: AssistantToolId) {
        for (kind in listOf("", "on_", "no_", "warning_", "web_")) compose.onNodeWithTag(tag(kind, tool)).assertDoesNotExist()
    }

    @Test fun buttonsAndWarningShowUnderTheReply() {
        asks = listOf(ask("a", "ssh_command"))
        show(); send()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag("on_", AssistantToolId.SSH_COMMAND)).fetchSemanticsNodes().isNotEmpty() }
        shown("on_", AssistantToolId.SSH_COMMAND).assertTextContains(text(R.string.chat_request_turn_on, title(AssistantToolId.SSH_COMMAND)))
        shown("no_", AssistantToolId.SSH_COMMAND).assertTextContains(text(R.string.chat_request_decline))
        shown("warning_", AssistantToolId.SSH_COMMAND).assertTextContains(text(R.string.tool_ssh_command_hint))
            .assertTextContains(if (russian) "ПОЛНЫЙ ДОСТУП" else "FULL SSH ACCESS", substring = true)
        assertEquals(if (russian) "Не надо" else "No thanks", text(R.string.chat_request_decline))
        assertEquals(if (russian) "Включить „Команда SSH“" else "Turn on “SSH command”", text(R.string.chat_request_turn_on, title(AssistantToolId.SSH_COMMAND)))
        // The model's own reason is not shown, and without web reading there is no mark.
        compose.onNodeWithText("synthetic reason", substring = true).assertDoesNotExist()
        compose.onNodeWithTag(tag("web_", AssistantToolId.SSH_COMMAND)).assertDoesNotExist()
        assertFalse("nothing is turned on by showing the buttons", settings.assistantAvailability.tools.sshCommand)
        assertEquals("no press, no second Send", 2, requests.size)
    }

    @Test fun theMarkShowsWhenTheAttemptReadTheInternet() {
        settings.update { it.copy(assistantSearch = true) }
        settings.assistantChat.publicWeb = PublicFetcher { _, _, _ -> throw ru.adsrv.frpjump.assistant.WebException(ru.adsrv.frpjump.assistant.WebFailure.NETWORK) }
        asks = listOf(ToolCall("w", "web_search", buildJsonObject { put("query", "synthetic") }), ask("a", "ssh_command"))
        show(); send()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag("on_", AssistantToolId.SSH_COMMAND)).fetchSemanticsNodes().isNotEmpty() }
        shown("web_", AssistantToolId.SSH_COMMAND).assertTextContains(text(R.string.chat_request_web))
        assertEquals(if (russian) "В этой попытке помощник читал страницы из интернета" else "In this attempt the assistant read web pages", text(R.string.chat_request_web))
        shown("on_", AssistantToolId.SSH_COMMAND)
    }

    @Test fun pressingTurnOnSwitchesTheToolOnAndSendsTheMessage() {
        asks = listOf(ask("a", "ssh_command"))
        show(); send()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag("on_", AssistantToolId.SSH_COMMAND)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(emptyList<AssistantToolId>(), AssistantToolId.entries.filter { settings.assistantAvailability.tools[it] })
        shown("on_", AssistantToolId.SSH_COMMAND).performClick()
        val message = text(R.string.chat_request_turned_on_message, title(AssistantToolId.SSH_COMMAND))
        compose.waitUntil(15_000) { conversation.state.value.let { it.turns.size == 2 && !it.busy } }
        assertEquals(listOf(AssistantToolId.SSH_COMMAND), AssistantToolId.entries.filter { settings.assistantAvailability.tools[it] })
        assertEquals(message, conversation.state.value.turns.last().user)
        assertEquals(if (russian) "Включил „Команда SSH“" else "Turned on “SSH command”", message)
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag("on_", AssistantToolId.SSH_COMMAND)).fetchSemanticsNodes().isEmpty() }
        gone(AssistantToolId.SSH_COMMAND)
    }

    @Test fun noThanksHidesTheButtonsAndDoesNothingElse() {
        asks = listOf(ask("a", "ssh_command"))
        show(); send()
        val before = requests.size
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag("no_", AssistantToolId.SSH_COMMAND)).fetchSemanticsNodes().isNotEmpty() }
        shown("no_", AssistantToolId.SSH_COMMAND).performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag("no_", AssistantToolId.SSH_COMMAND)).fetchSemanticsNodes().isEmpty() }
        gone(AssistantToolId.SSH_COMMAND)
        assertEquals(emptyList<AssistantToolId>(), AssistantToolId.entries.filter { settings.assistantAvailability.tools[it] })
        assertEquals(before, requests.size); assertEquals(1, conversation.state.value.turns.size)
        assertEquals(setOf("ssh_command"), conversation.state.value.declinedTools)
    }

    @Test fun theButtonsGoWhenTheToolIsTurnedOnInToolsOrTheAssistantIsOff() {
        asks = listOf(ask("a", "ssh_command"))
        show(); send()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag("on_", AssistantToolId.SSH_COMMAND)).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnUiThread { enabled.value = false }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag("on_", AssistantToolId.SSH_COMMAND)).fetchSemanticsNodes().isEmpty() }
        compose.runOnUiThread { enabled.value = true }
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag("on_", AssistantToolId.SSH_COMMAND)).fetchSemanticsNodes().isNotEmpty() }
        settings.setAssistantTool(AssistantToolId.SSH_COMMAND, true)
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag("on_", AssistantToolId.SSH_COMMAND)).fetchSemanticsNodes().isEmpty() }
        gone(AssistantToolId.SSH_COMMAND)
    }
}
