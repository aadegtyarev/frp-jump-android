package ru.adsrv.frpjump.ui.settings

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
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

    @Test fun summaryFieldsSaveWithoutDiscoveryAndRejectInvalidThreshold() {
        var saved: AssistantProviderDraft? = null
        val args=InstrumentationRegistry.getArguments()
        val config=Configuration(compose.activity.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(args.getString("chatLocale")?:"en")) }
        val context=compose.activity.createConfigurationContext(config)
        fun save()=compose.onNodeWithText(context.getString(R.string.save))
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides context,
                androidx.compose.ui.platform.LocalConfiguration provides config) {
                FrpJumpTheme(mode=if(args.getString("chatDark")=="true") ru.adsrv.frpjump.settings.ThemeMode.DARK else ru.adsrv.frpjump.settings.ThemeMode.LIGHT) {
                    AssistantProviderScreen(configured, { draft,done -> saved=draft;done(null) }, { _,_ -> error("No implicit discovery") }, {}, {})
                }
            }
        }
        field("assistant_summary_percent").performScrollTo().assertTextContains("80")
        field("assistant_summary_percent").performTextReplacement("0")
        save().assertIsNotEnabled()
        field("assistant_summary_percent").performTextReplacement("90")
        field("assistant_summary_model").performScrollTo().performTextReplacement("summary-model")
        save().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(90,saved!!.settings.summaryPercent)
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
            AssistantProviderScreen(AssistantProviderSnapshot(), { draft, done -> captured = draft; complete = done }, { _, _ -> error("No discovery") }, {}, { backs++ })
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
            AssistantProviderScreen(AssistantProviderSnapshot(), { _, _ -> }, { _, done -> count++; complete = done; {} }, {}, {})
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
            AssistantProviderScreen(configured, { _, _ -> }, { _, _ -> {} }, {}, {})
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
        restore.setContent { FrpJumpTheme { AssistantProviderScreen(configured, { _, _ -> }, { _, _ -> {} }, {}, {}) } }
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
            if (shown.value) AssistantProviderScreen(configured, { _, _ -> }, { _, done -> callbacks += done; { cancels++ } }, {}, {})
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
        restore.setContent { FrpJumpTheme { AssistantProviderScreen(snapshot.value, { _, _ -> }, { _, _ -> {} }, {}, {}) } }
        field("assistant_model").performTextReplacement("old-draft")
        compose.runOnIdle { snapshot.value = configured.copy(revision = "22222222-2222-2222-2222-222222222222") }
        restore.emulateSavedInstanceStateRestore()
        save().assertIsNotEnabled()
        models().performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.assistant_provider_stale)).assertExists()
    }
}
