package ru.adsrv.frpjump.ui.ssh

import android.view.inputmethod.EditorInfo
import android.graphics.Bitmap
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import java.io.File
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import ru.adsrv.frpjump.ssh.PastePlan
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ssh.TerminalDrafts
import ru.adsrv.frpjump.snippets.Snippet
import ru.adsrv.frpjump.ui.FrpJumpTheme
import ru.adsrv.frpjump.ui.AppTheme

/** Component/IME tests for a clean emulator with no enrolled profiles or stored credentials. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class TerminalTextComposerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val drafts = TerminalDrafts().apply { open(1); open(2) }
    private val selected = mutableIntStateOf(1)
    private val inserted = mutableListOf<String>()
    private val terminalKeys = mutableListOf<ExtraKey>()
    private val keyboardRequests = mutableListOf<Boolean>()

    @Before fun configureOrientation() {
        val arguments = InstrumentationRegistry.getArguments()
        orient(arguments.getString("composerOrientation") == "landscape")
        val fontScale = arguments.getString("composerFontScale")?.toFloat() ?: 1f
        assertEquals("Requested font scale must really be applied", fontScale, compose.activity.resources.configuration.fontScale, 0.01f)
    }

    private fun orient(landscape: Boolean) {
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (compose.activity.resources.configuration.orientation != expected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertEquals("Requested orientation must really be applied", expected, compose.activity.resources.configuration.orientation)
    }

    private fun start() = compose.setContent { FrpJumpTheme { Harness() } }

    private fun enterText() = compose.onNodeWithTag("terminal_keyboard").performTouchInput { longClick() }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val config = compose.activity.resources.configuration
        runCatching { instrumentation.uiAutomation.waitForIdle(350, 3_000) }
        val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return
        val file = File(instrumentation.targetContext.externalCacheDir, "$name-${config.orientation}-${config.fontScale}.png")
        file.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }

    @Composable
    private fun Harness() {
        val states by drafts.state.collectAsState()
        val id = selected.intValue
        val draft = states.getValue(id)
        var value by remember(id) { mutableStateOf(TextFieldValue(draft.text, TextRange(draft.selectionStart, draft.selectionEnd))) }
        val surface = remember(id, draft.textMode, draft.expanded, LocalConfiguration.current.orientation) { Any() }
        val currentSurface = rememberUpdatedState(surface)
        val focus = remember { FocusRequester() }
        val modifiers = remember { Modifiers() }
        LaunchedEffect(draft.revision, id) {
            val latest = drafts.state.value.getValue(id)
            if (value.text != latest.text) value = TextFieldValue(latest.text, TextRange(latest.selectionStart, latest.selectionEnd))
        }
        val edit: (TextFieldValue) -> Unit = {
            if (currentSurface.value === surface && drafts.editFromSurface(id, it.text, it.selection.start, it.selection.end, draft.expanded, selected.intValue)) value = it
        }
        val submit: (Boolean) -> Unit = { enter ->
            drafts.submission(id)?.let {
                inserted += it.text + if (enter) "\r" else ""
                drafts.inserted(it)
            }
            Unit
        }
        val insert = { submit(false) }
        val insertAndEnter = { submit(true) }
        Column(Modifier.fillMaxSize().background(AppTheme.colors.termBar).safeDrawingPadding().imePadding()) {
            Spacer(Modifier.weight(1f).fillMaxWidth().background(Color.Black))
            ExtraKeys(
                modifiers, onKeyboard = { keyboardRequests += it }, onPaste = {}, onKey = { terminalKeys += it },
                textMode = draft.textMode, onTextMode = { drafts.textMode(id, it) },
                textEditor = {
                    if (!draft.expanded) TerminalTextComposer(
                        id, value, edit, focus, true, insert,
                        onExpand = { drafts.expand(id, true) }, onKeys = { drafts.textMode(id, false) }, onInsertAndEnter = insertAndEnter,
                    )
                },
            )
        }
        if (draft.expanded) FullscreenTerminalText(onCollapse = { drafts.expand(id, false) }) {
            TerminalTextComposer(
                id, value, edit, focus, true, insert,
                onExpand = { drafts.expand(id, false) }, expanded = true, onKeys = { drafts.textMode(id, false) }, onInsertAndEnter = insertAndEnter,
            )
        }
    }

    @Test fun emptySavedCommandsTakeNoRoom() {
        compose.setContent { FrpJumpTheme { SnippetsRow(emptyList(), onSend = { _, _ -> }, onManage = {}) } }
        compose.onNodeWithTag("terminal_snippets_row").assertDoesNotExist()
    }

    @Test fun existingSavedCommandsAndTheirManagerRemainAvailable() {
        var managed = 0
        compose.setContent {
            FrpJumpTheme {
                SnippetsRow(listOf(Snippet("test", "ls")), onSend = { text, run -> inserted += "$text:$run" }, onManage = { managed++ })
            }
        }
        compose.onNodeWithTag("terminal_snippets_row").assertExists()
        compose.onNodeWithText("ls").performClick()
        compose.onNodeWithText("+").performClick()
        compose.runOnIdle {
            assertEquals(listOf("ls:false"), inserted)
            assertEquals(1, managed)
        }
    }

    @Test fun additionalKeysAreCollapsedAndToggleWithoutTerminalInput() {
        start()
        compose.onNodeWithTag("terminal_main_keys").assertExists()
        compose.onNodeWithTag("terminal_mode_text").assertDoesNotExist()
        compose.onNodeWithTag("terminal_extra_rows").assertDoesNotExist()
        capture("panel-keys")
        val keyboardBounds = compose.onNodeWithTag("terminal_keyboard").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("terminal_more_keys").performClick()
        compose.onNodeWithTag("terminal_extra_rows").assertExists()
        assertEquals(keyboardBounds, compose.onNodeWithTag("terminal_keyboard").fetchSemanticsNode().boundsInRoot)
        capture("panel-expanded-keys")
        compose.onNodeWithTag("terminal_more_keys").performClick()
        compose.onNodeWithTag("terminal_extra_rows").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(emptyList<ExtraKey>(), terminalKeys)
            assertEquals(emptyList<Boolean>(), keyboardRequests)
            assertEquals(emptyList<String>(), inserted)
        }
    }

    @Test fun extraKeysShareColumnTracksAndDisclosureNeverOverlapsKeys() {
        start()
        checkSharedGrid("panel-shared-grid")
    }

    @Test fun narrowGridKeepsCompleteDisclosureInsideItsViewport() {
        compose.setContent {
            FrpJumpTheme {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.width(320.dp)) {
                        ExtraKeys(remember { Modifiers() }, onKeyboard = { keyboardRequests += it },
                            onPaste = {}, onKey = { terminalKeys += it }, textMode = false,
                            onTextMode = {}, textEditor = {})
                    }
                }
            }
        }
        checkSharedGrid("panel-shared-grid-320dp")
    }

    private fun checkSharedGrid(screenshot: String) {
        compose.onNodeWithTag("terminal_more_keys").performClick()
        compose.waitForIdle()
        fun bounds(tag: String, unmerged: Boolean = false): Rect {
            val node = compose.onNodeWithTag(tag, useUnmergedTree = unmerged).fetchSemanticsNode()
            return Rect(node.positionOnScreen, node.boundsInRoot.size)
        }
        val first = bounds("terminal_key_CTRL_C")
        val keyboard = bounds("terminal_keyboard")
        val step = (keyboard.left - first.left) / 8
        val gap = step - first.width
        val halfStep = step / 2
        val keys = listOf(ExtraKey.ESC, ExtraKey.TAB, ExtraKey.CTRL_D, ExtraKey.CTRL_Z,
            ExtraKey.CTRL_R, ExtraKey.HOME, ExtraKey.END, ExtraKey.PAGE_UP,
            ExtraKey.PAGE_DOWN, ExtraKey.PIPE, ExtraKey.TILDE, ExtraKey.SLASH, ExtraKey.DASH)
        val target = bounds("terminal_more_keys")
        keys.forEach { key ->
            val b = bounds("terminal_key_${key.name}")
            val track = kotlin.math.round((b.left - first.left) / halfStep)
            val span = kotlin.math.round((b.width + gap) / halfStep)
            assertEquals("$key left track", first.left + track * halfStep, b.left, 2f)
            assertEquals("$key cell span", span * halfStep - gap, b.width, 2f)
            org.junit.Assert.assertFalse("Disclosure must not cover $key", target.overlaps(b))
        }
        val symbol = bounds("terminal_more_keys_symbol", unmerged = true)
        val dash = bounds("terminal_key_DASH")
        assertEquals(keyboard.center.x, symbol.center.x, 2f)
        assertEquals(dash.center.y, symbol.center.y, 2f)
        org.junit.Assert.assertTrue("Disclosure must end above main keys", target.bottom <= keyboard.top + 1f)
        val padding = 8 * compose.activity.resources.displayMetrics.density
        org.junit.Assert.assertTrue("Complete target must stay inside viewport", target.right <= keyboard.right + padding + 1f)
        org.junit.Assert.assertTrue("Complete target must stay inside viewport", target.left >= first.left - padding - 1f)
        capture(screenshot)
        compose.onNodeWithTag("terminal_more_keys").performClick()
        compose.runOnIdle {
            assertEquals(emptyList<ExtraKey>(), terminalKeys)
            assertEquals(emptyList<Boolean>(), keyboardRequests)
        }
    }

    @Test fun keyboardShortClickAndLongPressAreIndependent() {
        start()
        val expectedLabel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.terminal_text_input)
        assertEquals(expectedLabel, compose.onNodeWithTag("terminal_keyboard").fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)
        compose.onNodeWithTag("terminal_keyboard").performClick()
        compose.runOnIdle {
            assertEquals(1, keyboardRequests.size)
            keyboardRequests.clear()
        }
        enterText()
        compose.onNodeWithTag("terminal_text_editor").assertExists()
        compose.runOnIdle { assertEquals(emptyList<Boolean>(), keyboardRequests) }
    }

    @Test fun textModeHasNoTerminalRowsAndReturningKeepsTheDraft() {
        start()
        compose.onNodeWithTag("terminal_more_keys").performClick()
        compose.onNodeWithTag("terminal_extra_rows").assertExists()
        enterText()
        compose.onNodeWithTag("terminal_main_keys").assertDoesNotExist()
        compose.onNodeWithTag("terminal_more_keys").assertDoesNotExist()
        compose.onNodeWithTag("terminal_extra_rows").assertDoesNotExist()
        compose.onNodeWithTag("terminal_text_editor").performTextInput("kept draft")
        compose.onNodeWithTag("terminal_mode_keys").performClick()
        compose.onNodeWithTag("terminal_main_keys").assertExists()
        compose.onNodeWithTag("terminal_extra_rows").assertDoesNotExist()
        enterText()
        compose.onNodeWithTag("terminal_text_editor").assertTextContains("kept draft")
        compose.runOnIdle { assertEquals(emptyList<String>(), inserted) }
    }

    @Test fun typingAndNewlineStayLocalUntilExplicitInsertion() {
        start()
        compose.onNodeWithTag("terminal_text_editor").assertDoesNotExist()
        enterText()
        compose.onNodeWithTag("terminal_text_insert").assertIsNotEnabled()
        compose.onNodeWithTag("terminal_text_editor").performTextInput("Привет\nмир")
        compose.onNodeWithTag("terminal_text_editor").assertTextContains("Привет\nмир")
        compose.runOnIdle { assertEquals(emptyList<String>(), inserted) }
        compose.onNodeWithTag("terminal_text_insert").performClick()
        compose.runOnIdle { assertEquals(listOf("Привет\nмир"), inserted) }
        compose.onNodeWithTag("terminal_text_editor").assertDoesNotExist()
        compose.onNodeWithTag("terminal_main_keys").assertExists()
        compose.onNodeWithTag("terminal_extra_rows").assertDoesNotExist()
    }

    @Test fun longInsertSendsOneExplicitEnterInBothEditorsWithoutShortClickOnRelease() {
        start()
        capture("keyboard-pencil")
        for (expanded in listOf(false, true)) {
            enterText()
            val text = if (expanded) "expanded" else "compact"
            compose.onNodeWithTag("terminal_text_editor").performTextInput(text)
            if (expanded) compose.onNodeWithTag("terminal_text_expand").performClick()
            val action = compose.onNodeWithTag("terminal_text_insert")
            assertEquals(compose.activity.getString(R.string.terminal_text_insert_enter), action.fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)
            capture(if (expanded) "insert-enter-expanded" else "insert-enter-compact")
            action.performTouchInput { longClick() }
            compose.onNodeWithTag("terminal_text_editor").assertDoesNotExist()
            compose.onNodeWithTag("terminal_text_fullscreen").assertDoesNotExist()
            compose.onNodeWithTag("terminal_main_keys").assertExists()
            compose.runOnIdle { assertEquals(if (expanded) listOf("compact\r", "expanded\r") else listOf("compact\r"), inserted) }
        }
    }

    @Test fun longInsertIsDisabledForEmptyContent() {
        start()
        enterText()
        compose.onNodeWithTag("terminal_text_insert").assertIsNotEnabled().performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(emptyList<String>(), inserted) }
        compose.onNodeWithTag("terminal_text_editor").assertExists()
    }

    private val rootWithWindowFocus = object : org.hamcrest.TypeSafeMatcher<androidx.test.espresso.Root>() {
        override fun describeTo(description: org.hamcrest.Description) { description.appendText("root with window focus") }
        override fun matchesSafely(root: androidx.test.espresso.Root) = root.decorView.hasWindowFocus()
    }

    // Which window holds focus (dialog or landscape IME) varies, so Back goes to whichever root has it.
    private fun pressBackOnFocusedRoot() = retryUntilRootExists {
        Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
            .inRoot(rootWithWindowFocus)
            .perform(androidx.test.espresso.action.ViewActions.pressBack())
    }

    // A dialog root can appear a frame after its semantics node; retry while no matching root exists yet.
    private fun retryUntilRootExists(action: () -> Unit) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (true) {
            try { action(); return } catch (e: RuntimeException) {
                val transient = e is androidx.test.espresso.NoMatchingRootException ||
                    e.javaClass.simpleName == "RootViewWithoutFocusException"
                if (!transient || SystemClock.uptimeMillis() > deadline) throw e
            }
            compose.waitForIdle()
            SystemClock.sleep(50)
        }
    }

    @Test fun executeConfirmationWarnsAndCancelDoesNothingWhileJoinIsExplicit() {
        val open = mutableStateOf(true)
        compose.setContent {
            FrpJumpTheme {
                if (open.value) PasteSheet(
                    PastePlan.Ask("first\nsecond", 2),
                    onPaste = { inserted += it + "\r" },
                    onDismiss = { open.value = false }, execute = true,
                )
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.terminal_paste_enter_warning), substring = true).assertExists()
        compose.runOnIdle { assertEquals(emptyList<String>(), inserted) }
        pressBackOnFocusedRoot()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(compose.activity.getString(R.string.terminal_paste_enter_warning), substring = true).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText(compose.activity.getString(R.string.terminal_paste_enter_warning), substring = true).assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<String>(), inserted); open.value = true }
        compose.onNodeWithText(compose.activity.getString(R.string.terminal_paste_join_enter)).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("first second\r"), inserted) }
    }

    @Test fun insertingFromFullscreenReturnsMainKeysWithoutEnter() {
        start()
        enterText()
        compose.onNodeWithTag("terminal_text_editor").performTextInput("echo test")
        compose.onNodeWithTag("terminal_text_expand").performClick()
        compose.onNodeWithTag("terminal_text_fullscreen").assertExists()
        compose.onNodeWithTag("terminal_text_insert").performClick()
        compose.onNodeWithTag("terminal_text_fullscreen").assertDoesNotExist()
        compose.onNodeWithTag("terminal_text_editor").assertDoesNotExist()
        compose.onNodeWithTag("terminal_main_keys").assertExists()
        compose.onNodeWithTag("terminal_extra_rows").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("echo test"), inserted) }
    }

    @Test fun expandCollapseAndModeSwitchKeepTextAndSelection() {
        start()
        enterText()
        compose.onNodeWithTag("terminal_text_editor").performTextInput("Привет мир")
        compose.onNodeWithTag("terminal_text_editor").performTextInputSelection(TextRange(2, 5))
        capture("composer-compact")
        compose.onNodeWithTag("terminal_text_expand").performClick()
        compose.onNodeWithTag("terminal_text_fullscreen").assertExists()
        capture("composer-expanded")
        compose.onNodeWithTag("terminal_text_editor").assertTextContains("Привет мир")
        compose.onNodeWithTag("terminal_text_editor").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 5)))
        compose.onNodeWithTag("terminal_text_expand").performClick()
        compose.onNodeWithTag("terminal_text_fullscreen").assertDoesNotExist()
        compose.onNodeWithTag("terminal_text_editor").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 5)))
        compose.onNodeWithTag("terminal_mode_keys").performClick()
        compose.onNodeWithTag("terminal_text_editor").assertDoesNotExist()
        enterText()
        compose.onNodeWithTag("terminal_text_editor").assertTextContains("Привет мир")
        compose.onNodeWithTag("terminal_text_editor").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 5)))
        compose.runOnIdle { assertEquals(emptyList<String>(), inserted) }
    }

    @Test fun backCollapsesFullscreenWithoutSending() {
        start()
        enterText()
        compose.onNodeWithTag("terminal_text_editor").performTextInput("keep this draft")
        compose.onNodeWithTag("terminal_text_expand").performClick()
        // Fullscreen lives in a Dialog: the Activity root is deliberately unfocused.
        // Target the visible dialog for both real IME dismissal and Back dispatch.
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("terminal_text_fullscreen").fetchSemanticsNodes().isNotEmpty() }
        retryUntilRootExists {
            Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
                .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                .perform(androidx.test.espresso.action.ViewActions.closeSoftKeyboard(), androidx.test.espresso.action.ViewActions.pressBack())
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("terminal_text_fullscreen").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("terminal_text_fullscreen").assertDoesNotExist()
        compose.onNodeWithTag("terminal_text_editor").assertTextContains("keep this draft")
        compose.runOnIdle { assertEquals(emptyList<String>(), inserted) }
    }

    @Test fun rotatingTheCompactEditorKeepsDraftSelectionAndFocus() {
        start()
        val initiallyLandscape = compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        enterText()
        compose.onNodeWithTag("terminal_text_editor").performTextInput("Привет мир")
        compose.onNodeWithTag("terminal_text_editor").performTextInputSelection(TextRange(2, 5))
        orient(!initiallyLandscape)
        compose.onNodeWithTag("terminal_text_editor").assertIsFocused()
        compose.onNodeWithTag("terminal_text_editor").assertTextContains("Привет мир")
        compose.onNodeWithTag("terminal_text_editor").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 5)))
        orient(initiallyLandscape)
        compose.onNodeWithTag("terminal_text_editor").assertIsFocused()
        compose.onNodeWithTag("terminal_text_editor").assertTextContains("Привет мир")
        compose.onNodeWithTag("terminal_text_editor").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 5)))
        compose.runOnIdle { assertEquals(emptyList<String>(), inserted) }
    }

    @Test fun tabsHaveIndependentDraftsAndReturningRestoresTheEditor() {
        start()
        enterText()
        compose.onNodeWithTag("terminal_text_editor").performTextInput("first tab")
        compose.runOnIdle { selected.intValue = 2 }
        compose.onNodeWithTag("terminal_text_editor").assertDoesNotExist()
        enterText()
        compose.onNodeWithTag("terminal_text_editor").performTextInput("second tab")
        compose.runOnIdle { selected.intValue = 1 }
        compose.onNodeWithTag("terminal_text_editor").assertTextContains("first tab")
        compose.runOnIdle { assertEquals(emptyList<String>(), inserted) }
    }

    @Test fun terminalControlButtonDoesNotSendTheDraft() {
        start()
        enterText()
        compose.onNodeWithTag("terminal_text_editor").performTextInput("unfinished prompt")
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.terminal_key_ctrl_c)
        compose.onNodeWithContentDescription(label).assertDoesNotExist()
        compose.onNodeWithTag("terminal_mode_keys").performClick()
        compose.onNodeWithContentDescription(label).performClick()
        enterText()
        compose.onNodeWithTag("terminal_text_editor").assertTextContains("unfinished prompt")
        compose.runOnIdle {
            assertEquals(listOf(ExtraKey.CTRL_C), terminalKeys)
            assertEquals(emptyList<String>(), inserted)
        }
    }

    @Test fun nativeImeCompositionIsVisibleWithoutInsertion() {
        start()
        enterText()
        compose.onNodeWithTag("terminal_text_editor").performClick()
        compose.runOnIdle {
            val connection = compose.activity.currentFocus?.onCreateInputConnection(EditorInfo())
            assertNotNull(connection)
            connection!!.setComposingText("неподтверждённое", 1)
        }
        compose.onNodeWithTag("terminal_text_editor").assertTextContains("неподтверждённое")
        compose.runOnIdle { assertEquals(emptyList<String>(), inserted) }
        // Composing via a native InputConnection also starts asynchronous IME/inset work.
        // Wait for that platform layout before injecting a coordinate-based short tap.
        capture("native-ime-before-insert")
        compose.onNodeWithTag("terminal_text_insert").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals("short insertion dispatch count", 1, inserted.size)
            assertEquals(listOf("неподтверждённое"), inserted)
            assertEquals("", drafts.state.value.getValue(1).text)
        }
        compose.onNodeWithTag("terminal_text_editor").assertDoesNotExist()
        compose.onNodeWithTag("terminal_main_keys").assertExists()
        enterText()
        compose.onNodeWithTag("terminal_text_editor").performTextInput("следующее")
        compose.onNodeWithTag("terminal_text_editor").assertTextContains("следующее")
        compose.runOnIdle { assertEquals(listOf("неподтверждённое"), inserted) }
    }
}
