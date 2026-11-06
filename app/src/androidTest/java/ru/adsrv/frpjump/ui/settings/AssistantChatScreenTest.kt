package ru.adsrv.frpjump.ui.settings

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
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
    private val requests = mutableListOf<AgentRequest>()
    private var owner: AssistantConversation? = null
    private val module = AgentModule(true, { settings.assistantAvailability.disableVersion }, { settings.assistantAvailability.enabled })
    private fun vm() = AssistantChatViewModel(settings, store, module) {
        if (mode == "prepare") { prepEntered.countDown(); check(prepRelease.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
        AgentProvider { request ->
            requests += request; calls++
            when (mode) {
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
                    SideEffect { owner = vm.conversation; chatBinding = vm.tools }
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
        if (tag == "chat_tools") toolsBinding = chatBinding
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
        when (id) { "web_search" -> it.search; "read_page" -> it.readPage; "inspect_image" -> it.inspectImage; else -> error("Unknown test tool") }
    }
    private fun awaitToolSaved(id: String, enabled: Boolean) {
        fun snapshot(): String {
            val node = compose.onAllNodes(hasAnyAncestor(hasTestTag("tool_$id")) and isToggleable()).fetchSemanticsNodes().singleOrNull()
            val current = settings.settings.value
            return "tool=$id expected=$enabled committed=${committedTool(id)} observable=${current.assistantSearch}/${current.assistantReadPage}/${current.assistantInspectImage} presentation=${toolsBinding?.state?.value} rendered=${node?.config?.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ToggleableState)} enabled=${node != null && !node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)} busy=${owner?.state?.value?.busy}"
        }
        try {
            compose.waitUntil(5000) {
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

    @Test fun toolsEntrySwitchesFailedSaveAndRecreationAreInert() {
        compose.activityRule.scenario.onActivity { install(it) }
        compose.onNodeWithTag("assistant_tools_entry").performClick()
        val retained = settingsBinding
        for (id in listOf("web_search", "read_page", "inspect_image")) {
            toolToggle(id).assertIsOff().performClick(); awaitToolSaved(id, true)
            toolToggle(id).assertIsOn().assertIsEnabled()
            compose.runOnIdle { assertEquals(0, calls); settingsFile.failWrites = true }
            toolToggle(id).performClick()
            compose.waitUntil(5000) { toolsBinding!!.state.value.let { id in it.failed && id !in it.pending } }
            toolToggle(id).assertIsOn().assertIsEnabled()
            compose.onNode(hasText(localizedText(ru.adsrv.frpjump.R.string.assistant_tool_save_failed)) and hasAnyAncestor(hasTestTag("tool_$id"))).assertExists()
            compose.runOnIdle { assertTrue(committedTool(id)); settingsFile.failWrites = false }
            toolToggle(id).performClick(); awaitToolSaved(id, false)
            toolToggle(id).assertIsOff().assertIsEnabled()
            toolToggle(id).performClick(); awaitToolSaved(id, true)
        }
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { install(it) }
        for (id in listOf("web_search", "read_page", "inspect_image")) toolToggle(id).assertIsOn().assertIsEnabled()
        compose.runOnIdle { assertSame(retained, settingsBinding); assertEquals(0, calls) }
    }

    @Test fun toolsFlowShowsFetchedSourcesAndFailureWithoutMakingMarkdownActive() {
        mode = "tool-read"
        settings.update { it.copy(assistantReadPage = true) }
        settings.assistantChat.publicWeb = PublicFetcher { url, _, dispatch ->
            dispatch("public-dispatch") { }
            if (!url.startsWith("https://")) throw WebException(WebFailure.URL)
            WebResponse("https://public.org/doc", "text/html", "<title>Fetched guide</title><p>Useful static documentation with enough text to read.</p>".toByteArray(), 200, null)
        }
        install(); input().performTextInput("Read documentation")
        button("chat_send").performClick(); awaitIdle()
        button("chat_history").performScrollToNode(hasText("Fetched guide\nhttps://public.org/doc"))
        compose.onNodeWithText("Fetched guide\nhttps://public.org/doc").assertIsDisplayed().assertHasNoClickAction()
        compose.runOnIdle { assertEquals(2, calls); assertEquals(1, owner!!.state.value.turns.single().sources.size); mode = "tool-failure" }
        input().performTextInput("Unavailable page")
        button("chat_send").performClick(); awaitIdle()
        compose.runOnIdle { assertTrue(owner!!.state.value.turns.last().toolFailed); assertTrue(owner!!.state.value.turns.last().sources.isEmpty()); mode = "tool-read-provider-fail" }
        input().performTextInput("Fetch then fail")
        button("chat_send").performClick(); awaitIdle()
        compose.runOnIdle { assertEquals(ChatProblem.PROVIDER, owner!!.state.value.problem); assertEquals(1, owner!!.state.value.currentSources.size) }
        button("chat_history").performScrollToIndex(2)
        assertTrue(compose.onAllNodes(hasTestTag("chat_sources") and hasAnyAncestor(hasTestTag("chat_history"))).fetchSemanticsNodes().isNotEmpty())
        input().assertIsDisplayed()
        compose.runOnIdle { mode = "tool-failure-provider-fail" }
        input().performTextReplacement("Fail again")
        button("chat_send").performClick(); awaitIdle()
        compose.runOnIdle {
            assertTrue(owner!!.state.value.turns.last().toolFailed)
            assertTrue(owner!!.state.value.toolFailed)
            assertEquals(ChatProblem.PROVIDER, owner!!.state.value.problem)
        }
        button("chat_current_tool_failure").assertIsDisplayed().assertTextEquals(localizedText(ru.adsrv.frpjump.R.string.tool_failed))
    }

    @Test fun actualAndroidImageBoundsAndOptimisticObservationReleasePixelsAndFailTruthfully() = runBlocking {
        val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        validateImage("image/png", bytes)
        assertThrows(WebException::class.java) { validateImage("image/jpeg", bytes) }
        assertThrows(WebException::class.java) { validateImage("image/png", byteArrayOf(1, 2, 3)) }
        val inspected = mutableListOf<String>()
        val inspector = object : ru.adsrv.frpjump.agent.openai.ImageInspector {
            override suspend fun inspectImage(mime: String, bytes: ByteArray, question: String, dispatch: (() -> Unit) -> Unit): String {
                dispatch { inspected += question }; return "I cannot process images"
            }
        }
        val session = AssistantWebTools(ru.adsrv.frpjump.settings.AssistantTools(inspectImage = true), PublicFetcher { _, _, dispatch ->
            dispatch("public-dispatch") { }; WebResponse("https://public.org/image.png", "image/png", bytes, 200, null)
        }, WebSearchState(), inspector, { _, start -> start() }, {})
        val arguments = kotlinx.serialization.json.buildJsonObject { put("url", kotlinx.serialization.json.JsonPrimitive("https://public.org/image.png")); put("question", kotlinx.serialization.json.JsonPrimitive("What is shown?")) }
        session.tools.single().execute(arguments).also {
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
        val rejecting = object : ru.adsrv.frpjump.agent.openai.ImageInspector {
            override suspend fun inspectImage(mime: String, bytes: ByteArray, question: String, dispatch: (() -> Unit) -> Unit): String {
                dispatch { }; throw ru.adsrv.frpjump.agent.openai.OpenAiProviderException(ru.adsrv.frpjump.agent.openai.ProviderFailure.HTTP, 400)
            }
        }
        val rejected = AssistantWebTools(ru.adsrv.frpjump.settings.AssistantTools(inspectImage = true), PublicFetcher { _, _, dispatch ->
            dispatch("public-dispatch") { }; WebResponse("https://public.org/image.png", "image/png", bytes, 200, null)
        }, WebSearchState(), rejecting, { _, start -> start() }, {})
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
        button("chat_send").performClick(); awaitIdle()
        val retained = owner!!
        val ids = listOf("web_search", "read_page", "inspect_image")
        for ((index, id) in ids.withIndex()) {
            val completeCount = retained.state.value.turns.size
            compose.runOnIdle { heldProviderReply = CompletableDeferred(); mode = "held-answer" }
            input().performTextInput("pending-$id"); button("chat_send").performClick()
            compose.waitUntil(5000) { retained.state.value.busy }
            compose.runOnIdle { assertEquals(ids.take(index), requests.last().tools.map { it.name }) }
            button("chat_tools").performClick()
            compose.runOnIdle { settingsFile.failWrites = true }
            toolToggle(id).assertIsOff().performClick()
            compose.waitUntil(5000) { toolsBinding!!.state.value.let { id in it.failed && id !in it.pending } }
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
            input().performTextInput("next-$id"); button("chat_send").performClick(); awaitIdle()
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
            compose.waitUntil(5000) { retained.state.value.busy }
            button("chat_tools").performClick()
            toolToggle(id).assertIsOn().performClick(); awaitToolSaved(id, false)
            toolToggle(id).assertIsOff().assertIsEnabled()
            compose.runOnIdle { assertFalse(committedTool(id)); assertTrue(retained.state.value.busy); assertEquals(count, retained.state.value.turns.size) }
            if (index % 2 == 0) compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            else compose.onNodeWithTag("tools_back").performScrollTo().performClick()
            input().assertTextContains("pending-off-$id")
            compose.runOnIdle { heldProviderReply.complete(Unit) }; awaitIdle()
            compose.runOnIdle { assertSame(retained, owner); assertEquals(count + 1, retained.state.value.turns.size); assertEquals("", retained.state.value.draft); mode = "answer" }
            input().performTextInput("next-off-$id"); button("chat_send").performClick(); awaitIdle()
            compose.runOnIdle { assertEquals(ids.drop(index + 1), requests.last().tools.map { it.name }); assertEquals(count + 2, requests.last().turns.size) }
        }
        input().performTextInput("Retained draft")
        button("chat_tools").performClick()
        for (id in ids) toolToggle(id).assertIsOff().assertIsEnabled()
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { install(it) }
        toolToggle("web_search").assertIsOff().assertIsEnabled()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertSame(retained, owner); assertEquals(13, retained.state.value.turns.size) }
        input().assertTextContains("Retained draft")
        button("chat_back").performClick()
        compose.waitUntil(5000) { retained.state.value == ChatState() }
        compose.onNodeWithTag("assistant_chat_entry").performClick()
        compose.runOnIdle { assertNotSame(retained, owner); assertTrue(owner!!.state.value.turns.isEmpty()) }
    }

    @Test fun oldAndCurrentExchangeOmissionsUseTruthfulLocalizedContextLabel() {
        mode = "current-omission"; install()
        input().performTextInput("Current exchanges"); button("chat_send").performClick(); awaitIdle()
        compose.runOnIdle { assertEquals(1, owner!!.state.value.turns.size); assertTrue(owner!!.state.value.omitted) }
        compose.onNodeWithText(localizedText(ru.adsrv.frpjump.R.string.chat_omitted)).assertExists()
        button("chat_back").performClick()
        compose.onNodeWithTag("assistant_chat_entry").performClick()
        compose.runOnIdle { mode = "long-context" }
        input().performTextInput("First old turn"); button("chat_send").performClick(); awaitIdle()
        compose.runOnIdle { assertFalse(owner!!.state.value.omitted); owner!!.edit("y".repeat(7000)) }
        button("chat_send").performClick(); awaitIdle()
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
            compose.waitUntil(5000) { oldChat.state.value == ChatState() }
            compose.onNodeWithTag("assistant_chat_entry").performClick()
            compose.runOnIdle { assertNotSame(oldChat, owner); assertNotSame(oldBinding, chatBinding) }
            settingsFile.beforeWrite = null; release.countDown()
            button("chat_tools").performClick(); awaitToolSaved("web_search", true)
            toolToggle("web_search").assertIsOn().assertIsEnabled()
            compose.runOnIdle { assertEquals(oldState, oldBinding.state.value); assertTrue(oldBinding.state.value.failed.isEmpty()); assertEquals(0, calls) }
        } finally { settingsFile.beforeWrite = null; release.countDown() }
    }

}
