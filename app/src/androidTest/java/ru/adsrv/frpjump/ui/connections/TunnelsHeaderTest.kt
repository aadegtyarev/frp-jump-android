package ru.adsrv.frpjump.ui.connections

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.FrpJumpTheme
import java.io.File

/** The Tunnels tab's header: one row or two by measuring, the search's expand and collapse, the capsule ("● N | ⏻": the filter half and the Stop all half); synthetic state only. */
@RunWith(AndroidJUnit4::class)
class TunnelsHeaderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val runArgs get() = InstrumentationRegistry.getArguments()

    @org.junit.Before fun display() {
        val landscape = runArgs.getString("composerOrientation") == "landscape"
        compose.activityRule.scenario.onActivity { it.requestedOrientation = if (landscape) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        val expected = if (landscape) android.content.res.Configuration.ORIENTATION_LANDSCAPE else android.content.res.Configuration.ORIENTATION_PORTRAIT
        val until = android.os.SystemClock.uptimeMillis() + 5000
        while (compose.activity.resources.configuration.orientation != expected && android.os.SystemClock.uptimeMillis() < until) android.os.SystemClock.sleep(50)
        runArgs.getString("chatLocale")?.let { lang ->
            @Suppress("DEPRECATION") compose.activity.resources.updateConfiguration(android.content.res.Configuration(compose.activity.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(lang)) }, compose.activity.resources.displayMetrics)
        }
    }

    private fun text(id: Int, vararg args: Any): String = compose.activity.getString(id, *args)
    private val fontScale get() = compose.activity.resources.configuration.fontScale

    private fun shot(name: String) {
        compose.waitForIdle(); android.os.SystemClock.sleep(300); compose.waitForIdle()
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(compose.activity.getExternalFilesDir(null), "$name-${runArgs.getString("chatLocale") ?: "en"}-${runArgs.getString("composerOrientation") ?: "portrait"}-font${runArgs.getString("composerFontScale") ?: "1.0"}.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shot.recycle()
    }

    private var searching by mutableStateOf(false)
    private var query by mutableStateOf("")
    private var onlyOn by mutableStateOf(true)
    private var count by mutableStateOf(2)
    private var busy by mutableStateOf(false)
    private var stopped = 0
    private var gear = 0

    /** A 360 dp wide header, as on the narrowest phones; the font is the device's. */
    private fun host(widthDp: Int = 360) = compose.setContent {
        FrpJumpTheme {
            Box(Modifier.background(AppTheme.colors.ground).systemBarsPadding()) {
                Box(Modifier.width(widthDp.dp)) {
                    TunnelsHeader(
                        searching = searching, query = query, onQuery = { query = it },
                        onOpenSearch = { searching = true }, onCloseSearch = { searching = false; query = "" },
                        focusOnOpen = false, onFocused = {},
                        scopeOn = onlyOn && count > 0, filterOn = onlyOn && count > 0, count = count,
                        onFilter = { onlyOn = it }, stopBusy = busy, onStopAll = { stopped++ }, onSettings = { gear++ },
                    )
                }
            }
        }
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).assertIsDisplayed().getBoundsInRoot()
    private fun near(a: androidx.compose.ui.unit.Dp, b: androidx.compose.ui.unit.Dp) = kotlin.math.abs(a.value - b.value) <= 1f

    @Test fun oneRowAtNormalFontTwoRowsAtDouble() {
        host()
        val search = bounds(TUNNELS_SEARCH_TAG)
        val capsule = bounds(TUNNELS_CAPSULE_TAG)
        val filter = bounds(TUNNELS_FILTER_TAG)
        val stop = bounds(TUNNELS_STOP_ALL_TAG)
        val gear = bounds(ru.adsrv.frpjump.ui.SETTINGS_GEAR_TAG)
        assertTrue("inside the 360 dp header: $search $gear", gear.right.value - search.left.value <= 360.5f)
        assertTrue("the pill is 36 dp or more: $search", search.height.value >= 35.5f)
        // The capsule is one container; its halves are side by side, each a touch target of 48 dp or more, in order.
        assertTrue("the halves are inside the capsule: $capsule $filter $stop", filter.left.value >= capsule.left.value - 0.5f && stop.right.value <= capsule.right.value + 0.5f && filter.right.value <= stop.left.value + 1.5f)
        assertTrue("the halves are 48 dp or more: $filter $stop", filter.height.value >= 47.5f && filter.width.value >= 47.5f && stop.height.value >= 47.5f && stop.width.value >= 47.5f)
        assertTrue("the halves share a row: $filter $stop", near(filter.cy(), stop.cy()))
        if (fontScale < 1.5f) {
            // One row, in this order.
            assertTrue("search, capsule, gear on one row: $search $capsule $gear", near(search.cy(), capsule.cy()) && near(capsule.cy(), gear.cy()))
            assertTrue("in order", search.right.value <= capsule.left.value + 0.5f && capsule.right.value <= gear.left.value + 0.5f)
        } else {
            // Two rows: search and gear, then the capsule.
            assertTrue("search and gear share the first row: $search $gear", near(search.cy(), gear.cy()))
            assertTrue("the capsule is on the second row, below: $capsule $search", capsule.top.value >= search.bottom.value - 1f && capsule.top.value >= gear.bottom.value - 1f)
            assertTrue("the gear is at the end of the first row: $gear", gear.right.value - search.left.value >= 359f)
        }
        // The name of the search is whole on the pill.
        compose.onNodeWithText(text(R.string.connections_search_pill)).assertIsDisplayed()
        shot("tunnels-header")
        // The heading TalkBack lands on, invisible.
        val node = compose.onNodeWithTag(TUNNELS_HEADING_TAG).fetchSemanticsNode()
        assertTrue(node.config.contains(SemanticsProperties.Heading))
        assertEquals(text(R.string.tab_connections), node.config[SemanticsProperties.ContentDescription].single())
    }

    @Test fun theSearchExpandsOverTheHeaderAndCollapsesWithTheQueryCleared() {
        host()
        compose.onNodeWithTag(TUNNELS_SEARCH_TAG).performClick()
        compose.onNodeWithTag(TUNNELS_SEARCH_FIELD_TAG).assertIsDisplayed()
        compose.onNodeWithTag(TUNNELS_SEARCH_BACK_TAG).assertIsDisplayed()
        // The other items are hidden while it is open.
        compose.onNodeWithTag(TUNNELS_FILTER_TAG).assertDoesNotExist()
        compose.onNodeWithTag(TUNNELS_STOP_ALL_TAG).assertDoesNotExist()
        compose.onNodeWithTag(ru.adsrv.frpjump.ui.SETTINGS_GEAR_TAG).assertDoesNotExist()
        // The placeholder names the scope: the tunnels that are on, then all of them.
        compose.onNodeWithText(text(R.string.connections_search_scope_on)).assertIsDisplayed()
        shot("tunnels-header-search")
        onlyOn = false
        compose.onNodeWithText(text(R.string.connections_search_scope_all)).assertIsDisplayed()
        compose.onNodeWithTag(TUNNELS_SEARCH_CLEAR_TAG).assertDoesNotExist()
        compose.onNodeWithTag(TUNNELS_SEARCH_FIELD_TAG).performTextInput("kitchen")
        assertEquals("kitchen", query)
        compose.onNodeWithTag(TUNNELS_SEARCH_CLEAR_TAG).assertIsDisplayed()
        shot("tunnels-header-search-text")
        // ✕ clears the text and keeps the field open.
        compose.onNodeWithTag(TUNNELS_SEARCH_CLEAR_TAG).performClick()
        assertEquals("", query)
        compose.onNodeWithTag(TUNNELS_SEARCH_FIELD_TAG).assertIsDisplayed()
        compose.onNodeWithTag(TUNNELS_SEARCH_FIELD_TAG).performTextInput("shell")
        // ← clears the query and collapses.
        compose.onNodeWithTag(TUNNELS_SEARCH_BACK_TAG).performClick()
        assertEquals("", query)
        compose.onNodeWithTag(TUNNELS_SEARCH_FIELD_TAG).assertDoesNotExist()
        compose.onNodeWithTag(TUNNELS_SEARCH_TAG).assertIsDisplayed()
        compose.onNodeWithTag(TUNNELS_FILTER_TAG).assertIsDisplayed()
    }

    @Test fun theLeftHalfIsTheFilterToggleWithTheCountAndIsDisabledAtZero() {
        host()
        val filter = compose.onNodeWithTag(TUNNELS_FILTER_TAG).assertIsDisplayed().assertIsEnabled()
        assertEquals(text(R.string.connections_only_on, 2), filter.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single())
        filter.assertIsOn()
        filter.performClick()
        assertEquals(false, onlyOn)
        filter.assertIsOff()
        shot("tunnels-header-filter-off")
        filter.performClick()
        assertEquals(true, onlyOn)
        // On, it is shown by the selected (toggled-on) state; the dot and the count are not separate nodes (one label).
        filter.assertIsOn()
        shot("tunnels-header-filter-on")
        count = 0
        compose.waitForIdle()
        val zero = compose.onNodeWithTag(TUNNELS_FILTER_TAG).assertIsNotEnabled()
        assertEquals(text(R.string.connections_only_on, 0), zero.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single())
        shot("tunnels-header-zero")
        // The whole capsule is off at 0: the right half too, and the filter shows all (not on).
        compose.onNodeWithTag(TUNNELS_STOP_ALL_TAG).assertIsNotEnabled()
        compose.onNodeWithTag(TUNNELS_FILTER_TAG).assertIsOff()
    }

    @Test fun theRightHalfIsStopAllInItsPlaceAndDisabledAtZero() {
        host()
        val before = bounds(TUNNELS_STOP_ALL_TAG)
        val stop = compose.onNodeWithTag(TUNNELS_STOP_ALL_TAG).assertIsEnabled()
        assertEquals(text(R.string.disconnect_all_count, 2), stop.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single())
        stop.performClick()
        assertEquals(1, stopped)
        count = 0
        compose.waitForIdle()
        compose.onNodeWithTag(TUNNELS_STOP_ALL_TAG).assertIsNotEnabled()
        // It does not move, and does nothing.
        assertEquals(before, bounds(TUNNELS_STOP_ALL_TAG))
        compose.onNodeWithTag(TUNNELS_STOP_ALL_TAG).performClick()
        assertEquals(1, stopped)
        // The gear opens settings.
        compose.onNodeWithTag(ru.adsrv.frpjump.ui.SETTINGS_GEAR_TAG).performClick()
        assertEquals(1, gear)
    }

    private fun androidx.compose.ui.unit.DpRect.cy() = (top + bottom) / 2
}
