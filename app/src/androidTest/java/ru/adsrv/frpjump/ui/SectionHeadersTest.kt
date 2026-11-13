package ru.adsrv.frpjump.ui

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ui.devices.DevicesHeader
import ru.adsrv.frpjump.ui.devices.PROFILE_SWITCHER_TAG
import ru.adsrv.frpjump.ui.devices.TUNNELS_CHIP_TAG
import ru.adsrv.frpjump.ui.profiles.ProfileItem
import java.util.Locale

/**
 * The section headers: the gear at the right end of Devices, Connections and Sessions (label, button role, taps), the tunnel chip as
 * the glyph and the number read as the plural text, no power button on Devices, the Connections title whole (under the icons when
 * it does not fit one row), and the profile switcher's width at 360 dp with one tunnel. The language and the font scale are set on
 * the composition, so the check does not depend on the device's settings.
 */
@RunWith(AndroidJUnit4::class)
class SectionHeadersTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var localized: android.content.Context

    private fun host(widthDp: Int, lang: String, fontScale: Float, content: @Composable () -> Unit) {
        val config = Configuration(compose.activity.resources.configuration).apply { setLocale(Locale(lang)) }
        localized = compose.activity.createConfigurationContext(config)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                LocalDensity provides Density(density.density, fontScale)) {
                FrpJumpTheme { Box(Modifier.width(widthDp.dp)) { content() } }
            }
        }
        compose.waitForIdle()
    }

    private fun profile(tunnels: Int) = ProfileItem("p1", "A profile with a rather long name", "green", null, false, tunnels, current = true, removed = false)

    private fun devices(tunnels: Int, search: Boolean, onSettings: () -> Unit = {}, onTunnels: () -> Unit = {}) = @Composable {
        DevicesHeader(profile(tunnels), {}, onTunnels, onSettings, onSearch = if (search) ({}) else null)
    }

    private fun near(a: androidx.compose.ui.unit.Dp, b: androidx.compose.ui.unit.Dp) = kotlin.math.abs(a.value - b.value) <= 0.5f

    private fun string(id: Int) = localized.getString(id)

    private fun devicesChecks(lang: String, fontScale: Float, minSwitcherDp: Int) {
        var settings = 0
        var tunnels = 0
        host(360, lang, fontScale, devices(1, search = true, onSettings = { settings++ }, onTunnels = { tunnels++ }))
        // The gear: label, button, last on the right.
        val gear = compose.onNodeWithTag(SETTINGS_GEAR_TAG).assertIsDisplayed()
        assertEquals(string(R.string.tab_settings), gear.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.single())
        assertEquals(Role.Button, gear.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        val chip = compose.onNodeWithTag(TUNNELS_CHIP_TAG).assertIsDisplayed()
        val spoken = localized.resources.getQuantityString(R.plurals.tunnels, 1, 1)
        assertEquals(spoken, chip.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.single())
        assertEquals(Role.Button, chip.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        val search = compose.onNodeWithContentDescription(string(R.string.devices_search)).assertIsDisplayed().getBoundsInRoot()
        val g = gear.getBoundsInRoot()
        assertTrue("the gear is last: $g after $search", g.left >= search.right)
        assertTrue("the gear is 44 dp: $g", near(g.width, 44.dp) && near(g.height, 44.dp))
        assertTrue("the gear ends at the 16 dp gutter: $g", near(g.right, 344.dp))
        // No power button on Devices.
        compose.onNodeWithContentDescription(string(R.string.disconnect_all_tunnels)).assertDoesNotExist()
        // The switcher keeps its width.
        val switcher = compose.onNodeWithTag(PROFILE_SWITCHER_TAG).getBoundsInRoot().width
        assertTrue("the switcher is $switcher, needs at least $minSwitcherDp dp", switcher >= minSwitcherDp.dp)
        chip.performClick()
        gear.performClick()
        assertEquals(1, tunnels)
        assertEquals(1, settings)
    }

    @Test fun devicesEnAtNormalFont() = devicesChecks("en", 1f, 126)
    @Test fun devicesRuAtNormalFont() = devicesChecks("ru", 1f, 126)
    @Test fun devicesEnAtDoubleFont() = devicesChecks("en", 2f, 66)
    @Test fun devicesRuAtDoubleFont() = devicesChecks("ru", 2f, 66)

    @Test fun devicesWithNoTunnelsHaveNoChip() {
        host(360, "en", 1f, devices(0, search = false))
        compose.onNodeWithTag(TUNNELS_CHIP_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SETTINGS_GEAR_TAG).assertIsDisplayed()
    }

    @Composable private fun connectionsTitle() {
        SectionTitleRow(string(R.string.tab_connections), Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            IconButton(onClick = {}, modifier = Modifier.size(44.dp)) {
                Icon(PowerGlyph, contentDescription = string(R.string.disconnect_all_tunnels), modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = {}, modifier = Modifier.size(44.dp)) {
                Icon(SearchGlyph, contentDescription = string(R.string.connections_search), modifier = Modifier.size(22.dp))
            }
            SettingsGear {}
        }
    }

    private fun connectionsChecks(lang: String, fontScale: Float, stacked: Boolean) {
        host(360, lang, fontScale) { connectionsTitle() }
        val title = compose.onNodeWithTag(SECTION_TITLE_TAG).assertIsDisplayed()
        val results = mutableListOf<TextLayoutResult>()
        title.fetchSemanticsNode().config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        val r = results.first()
        assertEquals("the title is on one line", 1, r.lineCount)
        assertTrue("the title is cut: width ${r.size.width}, text ${r.getLineRight(0) - r.getLineLeft(0)}, overflow w=${r.didOverflowWidth} h=${r.didOverflowHeight}",
            r.getLineRight(0) <= r.size.width + 0.5f && r.getLineLeft(0) >= -0.5f && !r.didOverflowHeight && !r.isLineEllipsized(0))
        val t = title.getBoundsInRoot()
        val gear = compose.onNodeWithTag(SETTINGS_GEAR_TAG).assertIsDisplayed().getBoundsInRoot()
        assertTrue("the gear ends at the title's gutter: $gear", near(gear.right, 340.dp))
        if (stacked) assertTrue("the title $t is under the icons $gear", t.top.value >= gear.bottom.value - 1f)
        else assertTrue("the title $t is on the icons' row $gear", t.top < gear.bottom && t.right <= gear.left)
    }

    @Test fun connectionsTitleIsOnOneRowAtNormalFont() = connectionsChecks("en", 1f, stacked = false)
    @Test fun connectionsTitleIsOnOneRowAtNormalFontRu() = connectionsChecks("ru", 1f, stacked = false)
    @Test fun connectionsTitleIsUnderTheIconsAtDoubleFont() = connectionsChecks("en", 2f, stacked = true)
    @Test fun connectionsTitleIsUnderTheIconsAtDoubleFontRu() = connectionsChecks("ru", 2f, stacked = true)

    @Test fun sessionsHeaderHasTheGear() {
        var opened = 0
        host(360, "en", 1f) {
            SectionTitleRow(string(R.string.tab_sessions), Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { SettingsGear { opened++ } }
        }
        compose.onNodeWithContentDescription(string(R.string.tab_settings)).assertIsDisplayed().performClick()
        assertEquals(1, opened)
    }
}
