package ru.adsrv.frpjump.ui.settings

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.assistant.*
import ru.adsrv.frpjump.settings.AssistantLimits
import ru.adsrv.frpjump.settings.WindowSplit
import ru.adsrv.frpjump.ui.FrpJumpTheme

@RunWith(AndroidJUnit4::class)
class AssistantProviderScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun display() {
        val args = InstrumentationRegistry.getArguments()
        val landscape = args.getString("composerOrientation") == "landscape"
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        compose.activityRule.scenario.onActivity { it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        val deadline = SystemClock.uptimeMillis() + 5000
        while (compose.activity.resources.configuration.orientation != expected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertEquals(expected, compose.activity.resources.configuration.orientation)
        assertEquals(args.getString("composerFontScale")?.toFloat() ?: 1f, compose.activity.resources.configuration.fontScale, 0.01f)
    }
    private fun field(tag: String) = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText) and hasAnyAncestor(hasTestTag(tag)))
    // The discovery button enables a frame after the key edit; a tap before that is silently dropped.
    private fun awaitModelsEnabled() = compose.waitUntil(5_000) {
        compose.onAllNodes(hasTestTag("assistant_models") and androidx.compose.ui.test.isEnabled()).fetchSemanticsNodes().isNotEmpty()
    }
    private fun save() = compose.onNodeWithText(compose.activity.getString(R.string.save))
    private fun models() = compose.onNodeWithTag("assistant_models")
    private val configured = AssistantProviderSnapshot(AssistantProviderSettings("https://example.invalid/v1/", "existing"), "11111111-1111-1111-1111-111111111111")

    @Test fun summaryModelSavesWithoutDiscoveryAndTheOldTriggerStaysAsItWas() {
        var saved: AssistantProviderDraft? = null
        val args=InstrumentationRegistry.getArguments()
        val config=Configuration(compose.activity.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(args.getString("chatLocale")?:"en")) }
        val context=compose.activity.createConfigurationContext(config)
        fun save()=compose.onNodeWithText(context.getString(R.string.save))
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides context,
                androidx.compose.ui.platform.LocalConfiguration provides config) {
                FrpJumpTheme(mode=if(args.getString("chatDark")=="true") ru.adsrv.frpjump.settings.ThemeMode.DARK else ru.adsrv.frpjump.settings.ThemeMode.LIGHT) {
                    AssistantProviderScreen(configured, AssistantLimits(), {}, { draft,done -> saved=draft;done(null) }, { _,_ -> error("No implicit discovery") }, {}, {})
                }
            }
        }
        compose.onAllNodesWithTag("assistant_summary_percent").assertCountEquals(0)
        field("assistant_summary_model").performScrollTo().performTextReplacement("summary-model")
        save().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(configured.settings!!.summaryPercent,saved!!.settings.summaryPercent)
            assertEquals("summary-model",saved!!.settings.summaryModel)
            assertEquals(configured.settings!!.baseUrl,saved!!.settings.baseUrl)
            assertEquals(configured.settings!!.model,saved!!.settings.model)
        }
    }

    @Test fun saveWaitsForCommitAndFailureKeepsExactDraftEditable() {
        var captured: AssistantProviderDraft? = null
        var complete: ((String?) -> Unit)? = null
        var backs = 0
        compose.setContent { FrpJumpTheme {
            AssistantProviderScreen(AssistantProviderSnapshot(), AssistantLimits(), {}, { draft, done -> captured = draft; complete = done }, { _, _ -> error("No discovery") }, {}, { backs++ })
        } }
        save().assertIsNotEnabled()
        field("assistant_key").performTextReplacement("test-key")
        field("assistant_model").performTextReplacement("manual-model")
        save().assertIsEnabled().performClick()
        field("assistant_root").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, backs); assertEquals("manual-model", captured!!.settings.model); complete!!("Synthetic write failure") }
        compose.onNodeWithText("Synthetic write failure").assertExists()
        field("assistant_root").assertIsEnabled()
        save().performClick()
        compose.runOnIdle { complete!!(null); complete!!("Duplicate failure"); assertEquals(1, backs) }
        compose.onNodeWithText("Duplicate failure").assertDoesNotExist()
    }

    @Test fun discoveryIsExplicitAndAnUnavailableListDoesNotBlockManualModelEntry() {
        var count = 0
        var complete: ((List<String>?, String?) -> Unit)? = null
        compose.setContent { FrpJumpTheme {
            AssistantProviderScreen(AssistantProviderSnapshot(), AssistantLimits(), {}, { _, _ -> }, { _, done -> count++; complete = done; {} }, {}, {})
        } }
        compose.runOnIdle { assertEquals(0, count) }
        field("assistant_key").performTextReplacement("test-key")
        awaitModelsEnabled()
        models().performScrollTo().performClick()
        compose.waitUntil(5_000) { count == 1 && complete != null }
        compose.runOnIdle { assertEquals(1, count); complete!!(null, "Synthetic models unavailable") }
        field("assistant_model").performScrollTo().performTextReplacement("manual")
        save().assertIsEnabled()
        models().performScrollTo().performClick()
        compose.runOnIdle { complete!!(listOf("model-one", "model-two"), null) }
        compose.onNodeWithText("model-two").performClick()
        field("assistant_model").assertTextContains("model-two")
    }

    @Test fun existingKeyIsNotPrefilledAndChangedRootRequiresExplicitReplacement() {
        compose.setContent { FrpJumpTheme {
            AssistantProviderScreen(configured, AssistantLimits(), {}, { _, _ -> }, { _, _ -> {} }, {}, {})
        } }
        field("assistant_key").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        field("assistant_key").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        save().assertIsEnabled()
        field("assistant_root").performTextReplacement("https://other.invalid/v1")
        save().assertIsNotEnabled()
        models().performScrollTo().assertIsNotEnabled()
        field("assistant_key").performScrollTo().performTextReplacement("test-new-key")
        save().assertIsEnabled()
    }

    @Test fun replacementKeyIsNeverRestoredFromSavedStateOrSilentlyReplacedWithStoredKey() {
        val restore = StateRestorationTester(compose)
        restore.setContent { FrpJumpTheme { AssistantProviderScreen(configured, AssistantLimits(), {}, { _, _ -> }, { _, _ -> {} }, {}, {}) } }
        field("assistant_key").performTextReplacement("test-new-key")
        save().assertIsEnabled()
        restore.emulateSavedInstanceStateRestore()
        field("assistant_key").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        field("assistant_model").assertTextContains("existing")
        save().assertIsNotEnabled()
    }

    @Test fun disposedDiscoveryIsCancelledAndCannotPopulateAReplacementForm() {
        val shown = mutableStateOf(true)
        val callbacks = mutableListOf<(List<String>?, String?) -> Unit>()
        var cancels = 0
        compose.setContent { FrpJumpTheme {
            if (shown.value) AssistantProviderScreen(configured, AssistantLimits(), {}, { _, _ -> }, { _, done -> callbacks += done; { cancels++ } }, {}, {})
        } }
        models().performScrollTo().performClick()
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, cancels); shown.value = true }
        models().performScrollTo().performClick()
        compose.runOnIdle { callbacks[0](listOf("old-model"), "Old failure") }
        compose.onNodeWithText("old-model").assertDoesNotExist()
        compose.onNodeWithText("Old failure").assertDoesNotExist()
        field("assistant_root").assertIsNotEnabled()
        compose.runOnIdle { callbacks[1](listOf("new-model"), null) }
        compose.onNodeWithText("new-model").performClick()
        field("assistant_model").assertTextContains("new-model")
    }

    @Test fun restoredDraftKeepsItsOriginalRevisionInsteadOfRetargetingNewerSettings() {
        val snapshot = mutableStateOf(configured)
        val restore = StateRestorationTester(compose)
        restore.setContent { FrpJumpTheme { AssistantProviderScreen(snapshot.value, AssistantLimits(), {}, { _, _ -> }, { _, _ -> {} }, {}, {}) } }
        field("assistant_model").performTextReplacement("old-draft")
        compose.runOnIdle { snapshot.value = configured.copy(revision = "22222222-2222-2222-2222-222222222222") }
        restore.emulateSavedInstanceStateRestore()
        save().assertIsNotEnabled()
        models().performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.assistant_provider_stale)).assertExists()
    }
    private fun tagText(tag: String) = compose.onNodeWithTag(tag).performScrollTo()
    /** A stepper button pressed by its action: scrolled to the top of the view it lies under the pinned bar, where a touch would reach the bar. */
    private fun tap(tag: String) {
        val node = compose.onNodeWithTag(tag).performScrollTo()
        // A button at the end of its range has no click action: a press does nothing.
        if (node.fetchSemanticsNode().config.contains(SemanticsActions.OnClick)) node.performSemanticsAction(SemanticsActions.OnClick)
    }
    /** The screen in the run's locale (the activity's own when none is given), as the first test of this class shows it. */
    private fun show(snapshot: AssistantProviderSnapshot, limits: AssistantLimits, onLimits: (AssistantLimits) -> Unit = {},
        onSave: (AssistantProviderDraft, (String?) -> Unit) -> Unit = { _, _ -> }) {
        val args = InstrumentationRegistry.getArguments()
        val config = Configuration(compose.activity.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(args.getString("chatLocale") ?: "en")) }
        val context = compose.activity.createConfigurationContext(config)
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides context, androidx.compose.ui.platform.LocalConfiguration provides config) {
                FrpJumpTheme(mode = if (args.getString("chatDark") == "true") ru.adsrv.frpjump.settings.ThemeMode.DARK else ru.adsrv.frpjump.settings.ThemeMode.LIGHT) {
                    AssistantProviderScreen(snapshot, limits, onLimits, onSave, { _, _ -> {} }, {}, {})
                }
            }
        }
    }
    private val localeContext get() = compose.activity.createConfigurationContext(Configuration(compose.activity.resources.configuration).apply {
        setLocale(java.util.Locale.forLanguageTag(InstrumentationRegistry.getArguments().getString("chatLocale") ?: "en")) })

    /**
     * What the owner sees when a finger has scrolled the row of [tag] up to just below the pinned bar: drags until the row's top is a few pixels
     * under the bar's bottom (the bar is stuck at the top of the view by then), then takes the screen.
     */
    private fun glance(tag: String, name: String) {
        compose.onNodeWithTag(tag).performScrollTo()
        repeat(10) {
            val row = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top
            val bar = compose.onNodeWithTag("window_bar").fetchSemanticsNode().boundsInRoot.bottom
            val want = bar + 12f - row
            if (kotlin.math.abs(want) > 20f) compose.onRoot().performTouchInput {
                val from = androidx.compose.ui.geometry.Offset(centerX, centerY)
                val dy = want.coerceIn(-height * 0.4f, height * 0.4f)
                // A drag that stops before the finger lifts: no fling carries it further.
                down(from); moveTo(from + androidx.compose.ui.geometry.Offset(0f, dy)); advanceEventTime(300); up()
            }
        }
        capture(name)
    }

    private fun capture(name: String) {
        compose.waitForIdle(); SystemClock.sleep(300); compose.waitForIdle()
        val c = compose.activity.resources.configuration
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        java.io.File(compose.activity.getExternalFilesDir(null), "$name-${c.orientation}-${c.fontScale}-${InstrumentationRegistry.getArguments().getString("chatLocale") ?: "en"}.png").outputStream().use { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        shot.recycle()
    }

    @Test fun theWindowGroupShowsTheBarAndTheThreeSteppersAtTheirDefaults() {
        show(configured, AssistantLimits())
        tagText("window_history_value").assertTextEquals("75")
        tagText("window_reply_value").assertTextEquals("5")
        tagText("window_summary_value").assertTextEquals("15")
        // The window field, the bar, the steppers and the summary model are one group, in that order.
        tagText("assistant_context"); tagText("window_bar"); tagText("assistant_summary_model")
        compose.onAllNodesWithTag("assistant_output").assertCountEquals(0)
        compose.onNodeWithTag("window_label_compact", useUnmergedTree = true).assertExists()
        compose.onNode(hasTestTag("window_bar") and hasContentDescription("262", substring = true)).assertExists()
        capture("window-group-top")
        glance("window_history_value", "window-glance-history"); glance("window_reply_value", "window-glance-reply"); glance("window_summary_value", "window-glance-summary")
    }

    @Test fun theRulesStopEachPlusAndOneLineNamesThem() {
        show(configured, AssistantLimits())
        val summaryRule = localeContext.getString(R.string.window_rule_summary, 95); val replyRule = localeContext.getString(R.string.window_rule_reply, 95); val both = localeContext.getString(R.string.window_rule_both, 95)
        // 75 + 15 = 90 and 75 + 5 = 80: all pluses work; one step of history makes 80 + 15 = 95.
        tagText("window_history_plus").assertIsEnabled(); tap("window_history_plus")
        tagText("window_history_value").assertTextEquals("80")
        tagText("window_history_plus").assertIsNotEnabled(); tagText("window_history_hint").assertTextEquals(summaryRule)
        tagText("window_summary_plus").assertIsNotEnabled(); tagText("window_summary_hint").assertTextEquals(summaryRule)
        compose.onAllNodesWithTag("window_reply_hint").assertCountEquals(0)
        tagText("window_reply_plus").assertIsEnabled()
        // The reply up to 15: 80 + 15 = 95 as well, so the history names both rules and the reply its own.
        repeat(10) { tap("window_reply_plus") }
        tagText("window_reply_value").assertTextEquals("15")
        tagText("window_reply_plus").assertIsNotEnabled(); tagText("window_reply_hint").assertTextEquals(replyRule)
        tagText("window_history_hint").assertTextEquals(both)
        compose.onNode(hasText("95", substring = true) and hasAnyAncestor(hasTestTag("window_label_compact")), useUnmergedTree = true).assertExists()
        glance("window_reply_value", "window-glance-rules")
        // Lowering the history gives both room back and the lines go.
        tap("window_history_minus")
        tagText("window_history_plus").assertIsEnabled(); tagText("window_summary_plus").assertIsEnabled(); tagText("window_reply_plus").assertIsEnabled()
        compose.onAllNodesWithTag("window_history_hint").assertCountEquals(0)
        compose.onAllNodesWithTag("window_summary_hint").assertCountEquals(0); compose.onAllNodesWithTag("window_reply_hint").assertCountEquals(0)
        // The ranges: history 40-90, reply 1-20, summary 5-40.
        repeat(12) { tap("window_history_minus") }
        tagText("window_history_value").assertTextEquals("40"); tagText("window_history_minus").assertIsNotEnabled()
        repeat(25) { tap("window_reply_minus") }
        tagText("window_reply_value").assertTextEquals("1"); tagText("window_reply_minus").assertIsNotEnabled()
        repeat(9) { tap("window_summary_plus") }
        tagText("window_summary_value").assertTextEquals("40"); tagText("window_summary_plus").assertIsNotEnabled()
    }

    @Test fun saveStoresTheThreeSharesAfterTheProviderAndTheTokenMirrorIsTheReplyShare() {
        var draft: AssistantProviderDraft? = null; var complete: ((String?) -> Unit)? = null; val stored = ArrayList<AssistantLimits>()
        show(configured, AssistantLimits(), { stored += it }) { d, done -> draft = d; complete = done }
        tap("window_reply_plus"); tap("window_summary_minus"); tap("window_history_minus")
        compose.onNodeWithText(localeContext.getString(R.string.save)).performClick()
        compose.runOnIdle {
            assertTrue("the shares wait for the provider's save", stored.isEmpty())
            assertEquals(WindowSplit.tokens(draft!!.settings.contextTokens, 6), draft!!.settings.outputTokens)
            complete!!(null)
            assertEquals(1, stored.size); assertEquals(WindowSplit(70, 6, 10), stored.single().split)
            assertNull(stored.single().replyLimitedFrom)
        }
    }

    @Test fun aRefusedSaveKeepsTheSharesAsTheyWere() {
        val stored = ArrayList<AssistantLimits>(); var complete: ((String?) -> Unit)? = null
        show(configured, AssistantLimits(), { stored += it }) { _, done -> complete = done }
        tap("window_reply_plus"); compose.onNodeWithText(localeContext.getString(R.string.save)).performClick()
        compose.runOnIdle { complete!!("Synthetic write failure") }
        compose.runOnIdle { assertTrue(stored.isEmpty()) }
    }

    @Test fun aReserveThatWasCutSaysSoUntilTheOwnerChangesTheReply() {
        val limited = AssistantLimits(historyPercentSet = 60, replyPercentSet = 20, replyLimitedFrom = 256_000)
        val stored = ArrayList<AssistantLimits>()
        show(configured, limited, { stored += it }) { _, done -> done(null) }
        tagText("window_reply_hint").assertTextContains("256", substring = true)
        capture("window-group-limited")
        // Saved without touching the reply: the old tokens are kept, so the line stays.
        compose.onNodeWithText(localeContext.getString(R.string.save)).performClick()
        compose.runOnIdle { assertEquals(256_000, stored.last().replyLimitedFrom) }
    }

    @Test fun theLimitedLineGoesWhenTheReplyIsChanged() {
        val limited = AssistantLimits(historyPercentSet = 60, replyPercentSet = 20, replyLimitedFrom = 256_000)
        val stored = ArrayList<AssistantLimits>()
        show(configured, limited, { stored += it }) { _, done -> done(null) }
        tap("window_reply_minus")
        compose.onAllNodesWithTag("window_reply_hint").assertCountEquals(0)
        compose.onNodeWithText(localeContext.getString(R.string.save)).performClick()
        compose.runOnIdle { assertNull(stored.last().replyLimitedFrom); assertEquals(19, stored.last().replyPercent) }
    }
}
