package ru.adsrv.frpjump.ui.settings

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.settings.AppSettings
import ru.adsrv.frpjump.ui.FrpJumpTheme

/** Settings › LLM assistant › Own instructions on the actual Settings screen; the original preferences are restored. */
@RunWith(AndroidJUnit4::class)
class AssistantOwnerInstructionsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var original: AppSettings? = null
    private val models = ViewModelStore()
    private val app get() = compose.activity.application as FrpJumpApp
    private fun text(id: Int) = compose.activity.getString(id)
    private val saved get() = app.settings.settings.value.assistantOwnerInstructions

    @Before fun display() {
        val args = InstrumentationRegistry.getArguments()
        val landscape = args.getString("composerOrientation") == "landscape"
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        compose.activityRule.scenario.onActivity { it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        val deadline = SystemClock.uptimeMillis() + 5000
        while (compose.activity.resources.configuration.orientation != expected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertEquals(expected, compose.activity.resources.configuration.orientation)
        args.getString("chatLocale")?.let { lang ->
            val res = compose.activity.resources
            val config = Configuration(res.configuration).apply { setLocale(Locale(lang)) }
            @Suppress("DEPRECATION")
            res.updateConfiguration(config, res.displayMetrics)
            assertEquals(lang, res.configuration.locales[0].language)
        }
        assertEquals(args.getString("composerFontScale")?.toFloat() ?: 1f, compose.activity.resources.configuration.fontScale, 0.01f)
    }

    @After fun cleanup() { compose.runOnIdle { models.clear(); original?.let { old -> app.settings.update { old } } } }

    private fun show(initial: String = "") {
        lateinit var vm: SettingsViewModel
        compose.runOnUiThread {
            if (original == null) original = app.settings.settings.value
            app.settings.update { it.copy(assistantOwnerInstructions = initial) }
            vm = SettingsViewModel(app)
            models.put("owner-instructions-settings", vm)
        }
        compose.setContent { FrpJumpTheme { SettingsScreen(vm, null, null, {}, {}, onBack = null, onChat = {}) } }
    }

    private fun open() {
        compose.onNodeWithTag("assistant_owner_entry").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("assistant_owner_field").assertExists()
    }

    private fun assertNotCut(node: SemanticsNodeInteraction) {
        val results = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        assertTrue("no text layout", results.isNotEmpty())
        val r = results.first()
        assertTrue("ellipsized: '${r.layoutInput.text}'", (0 until r.lineCount).none { r.isLineEllipsized(it) })
        assertFalse("clipped: '${r.layoutInput.text}'", r.didOverflowHeight)
        assertTrue("wider than allowed: '${r.layoutInput.text}'", r.size.width <= r.layoutInput.constraints.maxWidth)
    }

    /** The counter is spoken as "N of M characters" (its visible "N / M" is replaced for accessibility). */
    private fun counter(shown: Int) = compose.activity.getString(R.string.assistant_owner_counter_spoken, shown, 2000)
    private fun counterNode(shown: Int) = compose.onNode(hasContentDescription(counter(shown)), useUnmergedTree = true)
    private fun field() = compose.onNode(hasSetTextAction())
    private fun back() = compose.onNodeWithContentDescription(text(R.string.back)).performClick()

    @Test fun screenShowsFieldCounterHintSaveAndResetUncut() {
        show()
        // The entry is in the LLM assistant group and says nothing about the text.
        compose.onNodeWithTag("assistant_owner_entry").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.assistant_owner_not_set), useUnmergedTree = true).assertExists()
        open()
        compose.onNodeWithTag("assistant_owner_field").performScrollTo().assertIsDisplayed()
        val hint = compose.onNodeWithText(text(R.string.assistant_owner_hint), useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        assertNotCut(hint)
        counterNode(0).performScrollTo().assertIsDisplayed()
        assertNotCut(compose.onNodeWithText(text(R.string.assistant_owner_reset), useUnmergedTree = true).performScrollTo().assertIsDisplayed())
        val save = compose.onNodeWithText(text(R.string.save)).assertIsDisplayed()
        save.assertIsNotEnabled()
        compose.onNodeWithTag("assistant_owner_reset").assertIsNotEnabled()
    }

    @Test fun savingPersistsAndLeavesAndTheRowSaysSet() {
        show()
        open()
        field().performTextInput("Answer briefly.\nPrefer tmux.")
        counterNode(28).assertExists()
        compose.onNodeWithText(text(R.string.save)).assertIsEnabled().performClick()
        compose.waitUntil(5000) { saved == "Answer briefly.\nPrefer tmux." }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("assistant_owner_entry").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.assistant_owner_set), useUnmergedTree = true).assertExists()
        // Reopening shows the saved text.
        open()
        compose.onNodeWithText("Answer briefly.\nPrefer tmux.", useUnmergedTree = true).assertExists()
    }

    @Test fun resetClearsOnlyTheFieldSaveCommitsAndBackDiscards() {
        show("Prefer tmux.")
        open()
        compose.onNodeWithText("Prefer tmux.", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("assistant_owner_reset").performScrollTo().assertIsEnabled().performClick()
        // Only the field is cleared; nothing is saved yet, and Save is the way to keep it.
        counterNode(0).assertExists()
        assertEquals("Prefer tmux.", saved)
        compose.onNodeWithTag("assistant_owner_reset").assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.save)).assertIsEnabled()
        // Back discards the unsaved edit: the saved text is still there and the row still says it is set.
        back()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("assistant_owner_entry").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("Prefer tmux.", saved)
        compose.onNodeWithText(text(R.string.assistant_owner_set), useUnmergedTree = true).assertExists()
        open()
        compose.onNodeWithText("Prefer tmux.", useUnmergedTree = true).assertExists()
        // Reset and Save: an empty field saved means no instructions.
        compose.onNodeWithTag("assistant_owner_reset").performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.save)).assertIsEnabled().performClick()
        compose.waitUntil(5000) { saved == "" }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("assistant_owner_entry").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text(R.string.assistant_owner_not_set), useUnmergedTree = true).assertExists()
    }

    @Test fun inputIsLimitedTo2000AndTheCounterShowsTheLimit() {
        show()
        open()
        field().performTextInput("x".repeat(2100))
        counterNode(2000).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.save)).assertIsEnabled().performClick()
        compose.waitUntil(5000) { saved.length == 2000 }
        assertEquals("x".repeat(2000), saved)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun insertingAtTheStartOfAFullFieldKeepsTheLastCharacter() {
        val full = "x".repeat(1997) + "END"
        show(full)
        open()
        counterNode(2000).assertExists()
        field().performTextInputSelection(TextRange(0))
        field().performTextInput("Z")
        counterNode(2000).assertExists()
        // Nothing was inserted and nothing was cut from the end, so nothing changed and there is nothing to save.
        compose.onNodeWithText(text(R.string.save)).assertIsNotEnabled()
        // A paste in the middle keeps both ends of the old text.
        field().performTextInputSelection(TextRange(1))
        field().performTextInput("PPPP")
        compose.onNodeWithText(text(R.string.save)).assertIsNotEnabled()
        compose.runOnIdle { assertEquals(full, saved) }
        // Deleting a character makes room; the next insertion is kept, still without cutting the end.
        field().performTextInputSelection(TextRange(2000))
        field().performTextReplacement(full.dropLast(1))
        field().performTextInputSelection(TextRange(0))
        field().performTextInput("Z")
        compose.onNodeWithText(text(R.string.save)).assertIsEnabled().performClick()
        compose.waitUntil(5000) { saved.length == 2000 }
        assertEquals("Z" + full.dropLast(1), saved)
        assertTrue(saved.endsWith("EN"))
    }
}
