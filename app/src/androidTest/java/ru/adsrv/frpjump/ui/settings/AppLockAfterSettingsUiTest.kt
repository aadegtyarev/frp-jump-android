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
import ru.adsrv.frpjump.settings.AppLockAfter
import ru.adsrv.frpjump.settings.AppSettings
import ru.adsrv.frpjump.ui.FrpJumpTheme

/** Settings › Security › Lock after on the actual Settings screen; the original preferences are restored. */
@RunWith(AndroidJUnit4::class)
class AppLockAfterSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var original: AppSettings? = null
    private val models = ViewModelStore()
    private val app get() = compose.activity.application as FrpJumpApp
    private fun text(id: Int) = compose.activity.getString(id)

    @Before fun display() {
        val args = InstrumentationRegistry.getArguments()
        val landscape = args.getString("composerOrientation") == "landscape"
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        compose.activityRule.scenario.onActivity { it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        val deadline = SystemClock.uptimeMillis() + 5000
        while (compose.activity.resources.configuration.orientation != expected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertEquals(expected, compose.activity.resources.configuration.orientation)
        // The words come from the activity's resources: this run's language (default: the phone's).
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

    private fun show(lockOn: Boolean, after: AppLockAfter = AppLockAfter.SECONDS_30) {
        lateinit var vm: SettingsViewModel
        compose.runOnUiThread {
            if (original == null) original = app.settings.settings.value
            // The poll is off: its "5 min" would collide with this row's value.
            app.settings.update { it.copy(appLock = lockOn, appLockAfter = after, backgroundPoll = ru.adsrv.frpjump.settings.BackgroundPoll.OFF) }
            vm = SettingsViewModel(app)
            models.put("lock-after-settings", vm)
        }
        compose.setContent { FrpJumpTheme { SettingsScreen(vm, null, null, {}, {}, onBack = null, onChat = {}) } }
    }

    /** The five choices as the sheet words them, in [AppLockAfter] order. */
    private fun labels(): List<String> {
        val res = compose.activity.resources
        return listOf(
            text(R.string.lock_after_immediately),
            res.getQuantityString(R.plurals.poll_seconds, 30, 30),
            res.getQuantityString(R.plurals.poll_minutes, 1, 1),
            res.getQuantityString(R.plurals.poll_minutes, 5, 5),
            res.getQuantityString(R.plurals.poll_minutes, 15, 15),
        )
    }

    private fun choice(label: String) = isSelectable() and hasText(label)

    private fun assertNotCut(node: SemanticsNodeInteraction) {
        val results = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        assertTrue("no text layout", results.isNotEmpty())
        // No ellipsis and no clipped line (didOverflowWidth is not used: it reports true for text that fits).
        val r = results.first()
        assertTrue("ellipsized: '${r.layoutInput.text}'", (0 until r.lineCount).none { r.isLineEllipsized(it) })
        assertFalse("clipped: '${r.layoutInput.text}' size=${r.size} constraints=${r.layoutInput.constraints}", r.didOverflowHeight)
        assertTrue("wider than allowed: '${r.layoutInput.text}'", r.size.width <= r.layoutInput.constraints.maxWidth)
    }

    @Test fun rowIsHiddenWhileTheLockIsOff() {
        show(lockOn = false)
        compose.onNodeWithText(text(R.string.setting_app_lock)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.setting_lock_after)).assertDoesNotExist()
    }

    @Test fun rowShowsTheValueAndTheHintNamesIt() {
        show(lockOn = true, after = AppLockAfter.MINUTES_5)
        val row = compose.onNodeWithText(text(R.string.setting_lock_after), useUnmergedTree = true).performScrollTo()
        row.assertIsDisplayed()
        assertNotCut(row)
        assertNotCut(compose.onNodeWithText(labels()[3], useUnmergedTree = true).performScrollTo().assertIsDisplayed())
        val hint = compose.onNodeWithText(compose.activity.getString(R.string.setting_app_lock_hint_after, labels()[3]), useUnmergedTree = true).performScrollTo()
        hint.assertIsDisplayed()
        assertNotCut(hint)
    }

    @Test fun sheetListsTheFiveChoicesAndSavesThePick() {
        show(lockOn = true)
        compose.onNodeWithText(text(R.string.setting_lock_after)).performScrollTo().performClick()
        val choices = labels()
        for (label in choices) compose.onNode(choice(label)).assertExists()
        compose.onNode(choice(choices[1])).assertIsSelected()
        val footer = compose.onNodeWithText(text(R.string.setting_lock_after_hint), useUnmergedTree = true).performScrollTo()
        footer.assertIsDisplayed()
        assertNotCut(footer)
        compose.onNode(choice(choices[0])).performScrollTo().performClick()
        compose.waitUntil(5000) { app.settings.settings.value.appLockAfter == AppLockAfter.IMMEDIATELY }
        // The sheet closed; reopening shows the pick.
        compose.onNodeWithText(text(R.string.setting_lock_after)).performScrollTo().performClick()
        compose.onNode(choice(choices[0])).assertIsSelected()
    }

    @Test fun everyChoiceIsSaved() {
        show(lockOn = true)
        val choices = labels()
        for ((i, after) in AppLockAfter.entries.withIndex().reversed()) {
            compose.onNodeWithText(text(R.string.setting_lock_after)).performScrollTo().performClick()
            compose.onNode(choice(choices[i])).performScrollTo().performClick()
            compose.waitUntil(5000) { app.settings.settings.value.appLockAfter == after }
        }
    }
}
