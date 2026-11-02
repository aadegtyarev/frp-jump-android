package ru.adsrv.frpjump.ui.ssh

import android.view.inputmethod.EditorInfo
import android.graphics.Bitmap
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import java.io.File
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.semantics.SemanticsProperties
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
        val insert = {
            drafts.submission(id)?.let {
                inserted += it.text
                drafts.inserted(it)
            }
            Unit
        }
        Column(Modifier.fillMaxSize().background(AppTheme.colors.termBar).safeDrawingPadding().imePadding()) {
            Spacer(Modifier.weight(1f).fillMaxWidth().background(Color.Black))
            ExtraKeys(
                modifiers, onKeyboard = {}, onPaste = {}, onKey = { terminalKeys += it },
                textMode = draft.textMode, onTextMode = { drafts.textMode(id, it) },
                textEditor = {
                    if (!draft.expanded) TerminalTextComposer(
                        id, value, edit, focus, true, insert,
                        onExpand = { drafts.expand(id, true) }, onKeys = { drafts.textMode(id, false) },
                    )
                },
            )
        }
        if (draft.expanded) FullscreenTerminalText(onCollapse = { drafts.expand(id, false) }) {
            TerminalTextComposer(
                id, value, edit, focus, true, insert,
                onExpand = { drafts.expand(id, false) }, expanded = true, onKeys = { drafts.textMode(id, false) },
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

    @Test fun typingAndNewlineStayLocalUntilExplicitInsertion() {
        start()
        compose.onNodeWithTag("terminal_text_editor").assertDoesNotExist()
        compose.onNodeWithTag("terminal_mode_text").performClick()
        compose.onNodeWithTag("terminal_text_insert").assertIsNotEnabled()
        compose.onNodeWithTag("terminal_text_editor").performTextInput("Привет\nмир")
        compose.onNodeWithTag("terminal_text_editor").assertTextContains("Привет\nмир")
        compose.runOnIdle { assertEquals(emptyList<String>(), inserted) }
        compose.onNodeWithTag("terminal_text_insert").performClick()
        compose.runOnIdle { assertEquals(listOf("Привет\nмир"), inserted) }
        compose.onNodeWithTag("terminal_text_editor").assertTextContains("")
    }

    @Test fun expandCollapseAndModeSwitchKeepTextAndSelection() {
        start()
        compose.onNodeWithTag("terminal_mode_text").performClick()
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
        compose.onNodeWithTag("terminal_mode_text").performClick()
        compose.onNodeWithTag("terminal_text_editor").assertTextContains("Привет мир")
        compose.onNodeWithTag("terminal_text_editor").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 5)))
        compose.runOnIdle { assertEquals(emptyList<String>(), inserted) }
    }

    @Test fun backCollapsesFullscreenWithoutSending() {
        start()
        compose.onNodeWithTag("terminal_mode_text").performClick()
        compose.onNodeWithTag("terminal_text_editor").performTextInput("keep this draft")
        compose.onNodeWithTag("terminal_text_expand").performClick()
        Espresso.closeSoftKeyboard()
        Espresso.pressBack()
        compose.onNodeWithTag("terminal_text_fullscreen").assertDoesNotExist()
        compose.onNodeWithTag("terminal_text_editor").assertTextContains("keep this draft")
        compose.runOnIdle { assertEquals(emptyList<String>(), inserted) }
    }

    @Test fun rotatingTheCompactEditorKeepsDraftSelectionAndFocus() {
        start()
        val initiallyLandscape = compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        compose.onNodeWithTag("terminal_mode_text").performClick()
        compose.onNodeWithTag("terminal_text_editor").performTextInput("Привет мир")
        compose.onNodeWithTag("terminal_text_editor").performTextInputSelection(TextRange(2, 5))
        orient(!initiallyLandscape)
        compose.onNodeWithTag("terminal_text_editor").assertTextContains("Привет мир")
        compose.onNodeWithTag("terminal_text_editor").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 5)))
        orient(initiallyLandscape)
        compose.onNodeWithTag("terminal_text_editor").assertTextContains("Привет мир")
        compose.onNodeWithTag("terminal_text_editor").assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(2, 5)))
        compose.runOnIdle { assertEquals(emptyList<String>(), inserted) }
    }

    @Test fun tabsHaveIndependentDraftsAndReturningRestoresTheEditor() {
        start()
        compose.onNodeWithTag("terminal_mode_text").performClick()
        compose.onNodeWithTag("terminal_text_editor").performTextInput("first tab")
        compose.runOnIdle { selected.intValue = 2 }
        compose.onNodeWithTag("terminal_text_editor").assertDoesNotExist()
        compose.onNodeWithTag("terminal_mode_text").performClick()
        compose.onNodeWithTag("terminal_text_editor").performTextInput("second tab")
        compose.runOnIdle { selected.intValue = 1 }
        compose.onNodeWithTag("terminal_text_editor").assertTextContains("first tab")
        compose.runOnIdle { assertEquals(emptyList<String>(), inserted) }
    }

    @Test fun terminalControlButtonDoesNotSendTheDraft() {
        start()
        compose.onNodeWithTag("terminal_mode_text").performClick()
        compose.onNodeWithTag("terminal_text_editor").performTextInput("unfinished prompt")
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.terminal_key_ctrl_c)
        compose.onNodeWithContentDescription(label).performClick()
        compose.onNodeWithTag("terminal_text_editor").assertTextContains("unfinished prompt")
        compose.runOnIdle {
            assertEquals(listOf(ExtraKey.CTRL_C), terminalKeys)
            assertEquals(emptyList<String>(), inserted)
        }
    }

    @Test fun nativeImeCompositionIsVisibleWithoutInsertion() {
        start()
        compose.onNodeWithTag("terminal_mode_text").performClick()
        compose.onNodeWithTag("terminal_text_editor").performClick()
        compose.runOnIdle {
            val connection = compose.activity.currentFocus?.onCreateInputConnection(EditorInfo())
            assertNotNull(connection)
            connection!!.setComposingText("неподтверждённое", 1)
        }
        compose.onNodeWithTag("terminal_text_editor").assertTextContains("неподтверждённое")
        compose.runOnIdle { assertEquals(emptyList<String>(), inserted) }
        compose.onNodeWithTag("terminal_text_insert").performClick()
        compose.runOnIdle {
            assertEquals(listOf("неподтверждённое"), inserted)
            assertEquals("", drafts.state.value.getValue(1).text)
        }
        compose.onNodeWithTag("terminal_text_editor").performTextInput("следующее")
        compose.onNodeWithTag("terminal_text_editor").assertTextContains("следующее")
        compose.runOnIdle { assertEquals(listOf("неподтверждённое"), inserted) }
    }
}
