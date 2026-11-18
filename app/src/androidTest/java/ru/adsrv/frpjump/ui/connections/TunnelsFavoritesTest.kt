package ru.adsrv.frpjump.ui.connections

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.click
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.connections.connectionRows
import ru.adsrv.frpjump.connections.groupByDevice
import ru.adsrv.frpjump.connections.tunnelsView
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.tunnel.TunnelsState
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.FrpJumpTheme
import ru.adsrv.frpjump.ui.devices.OpenRow
import ru.adsrv.frpjump.ui.devices.closesOpenRow
import java.io.File

/**
 * The Tunnels list's favourites and swipes: the favourites group on top, a swipe that reveals a button and runs nothing by itself, the action on
 * the button's tap, the menu items and TalkBack's custom actions. The real rows and cards of the Tunnels tab, with synthetic connections.
 */
@RunWith(AndroidJUnit4::class)
class TunnelsFavoritesTest {
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

    private fun shot(name: String) {
        compose.waitForIdle(); android.os.SystemClock.sleep(300); compose.waitForIdle()
        val shot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(compose.activity.getExternalFilesDir(null), "$name-${runArgs.getString("chatLocale") ?: "en"}-${runArgs.getString("composerOrientation") ?: "portrait"}-font${runArgs.getString("composerFontScale") ?: "1.0"}.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shot.recycle()
    }

    private val pi = Connection("c1", "alpha", 22, PortKind.SSH, customName = "Pi shell", user = "pi", favorite = true)
    private val z2m = Connection("c2", "beta", 8080, PortKind.WEB, customName = "z2m")
    private val panel = Connection("c3", "alpha", 80, PortKind.WEB, customName = "panel")

    private var connections by mutableStateOf(listOf(pi, z2m, panel))
    private var assistantOn by mutableStateOf(true)
    private var favoriteCalls = 0
    private var tapCalls = 0
    private var connectCalls = 0
    private var asked: String? = null
    private val scroll = androidx.compose.foundation.ScrollState(0)

    private fun toggle(id: String) { connections = connections.map { if (it.id == id) it.copy(favorite = !it.favorite) else it } }

    private fun host() {
        compose.setContent {
            FrpJumpTheme {
                val openRow = remember { OpenRow() }
                val ui = remember { ConnectionUi() }
                val context = androidx.compose.ui.platform.LocalContext.current
                val actions = RowActions(
                    { connectCalls++ }, {}, { tapCalls++ }, {}, {}, {}, {}, {}, {}, {}, {}, {}, { false }, {}, {},
                    favorite = { row -> favoriteCalls++; toggle(row.connection.id) },
                    ask = if (assistantOn) ({ row -> asked = askDraft(context, row.connection) }) else null,
                )
                val view = tunnelsView(groupByDevice(connectionRows("p1", connections, TunnelsState()), emptyMap()), onlyOn = false, query = "") { it.connection.customName.orEmpty() }
                // Tall, in a scroll of its own: every row is composed at any font size, and a test scrolls to the one it touches.
                Box(Modifier.background(AppTheme.colors.ground).systemBarsPadding().fillMaxSize().verticalScroll(scroll)) {
                    Box(Modifier.fillMaxWidth().height(2400.dp)) {
                        LazyColumn(Modifier.fillMaxSize().closesOpenRow(openRow), contentPadding = PaddingValues(16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                            tunnelRows(view, ConnectionsUiState(), emptyList(), emptyMap(), ui, actions, {}, openRow)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** A tap on the button; where a large font on a short screen cuts it, its click action (the gesture itself is run in the 1.0 portrait pass). */
    private fun tap(tag: String) {
        val node: SemanticsNodeInteraction = compose.onNodeWithTag(tag)
        runCatching { node.assertIsDisplayed() }.fold({ node.performClick() }, { node.performSemanticsAction(SemanticsActions.OnClick) })
    }

    private fun cardTag(c: Connection) = "tunnel_card_${c.id}"
    private fun card(c: Connection) = compose.onNodeWithTag(cardTag(c))
    private fun top(c: Connection) = card(c).fetchSemanticsNode().positionInRoot.y

    /** A drag across the middle of the card, away from the screen's edges. */
    /** Scrolls the host (not the lazy list inside it, which has nothing to scroll) so the card is near the top of the screen. */
    private fun bring(c: Connection) {
        val y = card(c).fetchSemanticsNode().positionInRoot.y
        compose.runOnUiThread { scroll.dispatchRawDelta(y - 150f) }
        compose.waitForIdle()
    }

    private fun swipeCard(c: Connection, toRight: Boolean) = card(c).also { bring(c) }.performTouchInput {
        val y = center.y
        if (toRight) swipe(Offset(width * 0.3f, y), Offset(width * 0.8f, y), 300) else swipe(Offset(width * 0.8f, y), Offset(width * 0.3f, y), 300)
    }

    private val favoritesTitle get() = text(R.string.connections_favorites_spoken)

    @Test fun theFavouritesGroupIsFirstWithTheDeviceAfterTheNameAndMutedStar() {
        host()
        compose.onNodeWithContentDescription(favoritesTitle).assertIsDisplayed()
        assertTrue(compose.onNodeWithContentDescription(favoritesTitle).fetchSemanticsNode().positionInRoot.y < top(pi))
        // The favourite is above the device groups and says its device; the others stay under their devices.
        assertTrue(top(pi) < top(panel))
        assertTrue(top(pi) < top(z2m))
        compose.onNode(hasAnyAncestor(hasTestTag(cardTag(pi))) and hasContentDescription("Pi shell, ${text(R.string.favorite_spoken)} · alpha"), useUnmergedTree = true).assertExists()
        // A card in a device group shows no star and no device.
        compose.onNode(hasAnyAncestor(hasTestTag(cardTag(z2m))) and hasText("z2m"), useUnmergedTree = true).assertExists()
        shot("tunnels-favorites-group")
    }

    @Test fun aSwipeRightRevealsTheStarAndOnlyItsTapRunsIt() {
        host()
        swipeCard(z2m, toRight = true)
        compose.waitForIdle()
        shot("tunnels-swipe-right")
        compose.onNodeWithTag("swipe_favorite").assertExists()
        // Nothing ran by the swipe.
        assertEquals(0, favoriteCalls)
        assertEquals(0, tapCalls)
        assertTrue(top(z2m) > top(panel))
        tap("swipe_favorite")
        compose.waitForIdle()
        assertEquals(1, favoriteCalls)
        // It moved into the favourites group: by device name (alpha's Pi shell, then beta's z2m), above panel.
        assertTrue(top(z2m) < top(panel))
        compose.onNode(hasAnyAncestor(hasTestTag(cardTag(z2m))) and hasContentDescription("z2m, ${text(R.string.favorite_spoken)} · beta"), useUnmergedTree = true).assertExists()
        // And off again from the same swipe: the button now takes it out.
        swipeCard(z2m, toRight = true)
        compose.waitForIdle()
        compose.onNodeWithTag("swipe_favorite").assertExists()
        compose.onNode(hasText(text(R.string.favorite_remove)) or hasContentDescription(text(R.string.favorite_remove))).assertExists()
        tap("swipe_favorite")
        compose.waitForIdle()
        assertEquals(2, favoriteCalls)
        assertTrue(top(z2m) > top(panel))
    }

    @Test fun aSwipeLeftRevealsAskTheAssistantAndItsTapGivesTheDraftWithoutSendingAnything() {
        host()
        swipeCard(panel, toRight = false)
        compose.waitForIdle()
        compose.onNodeWithTag("swipe_ask_assistant").assertExists()
        assertEquals(null, asked)
        shot("tunnels-swipe-left")
        tap("swipe_ask_assistant")
        compose.waitForIdle()
        assertEquals(text(R.string.ask_assistant_about_tunnel, "panel", "alpha") + " ", asked)
        assertEquals(0, tapCalls)
    }

    @Test fun withTheAssistantOffThereIsNoLeftSwipeAndNoAskItems() {
        assistantOn = false
        host()
        swipeCard(panel, toRight = false)
        compose.waitForIdle()
        compose.onNodeWithTag("swipe_ask_assistant").assertDoesNotExist()
        // The right swipe still works.
        swipeCard(panel, toRight = true)
        compose.waitForIdle()
        compose.onNodeWithTag("swipe_favorite").assertExists()
        // TalkBack has the star only.
        assertEquals(listOf(text(R.string.favorite_add)), labelsOfActions(compose.onNode(hasAnyAncestor(hasTestTag(cardTag(panel))) and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))))
    }

    @Test fun aTapOnTheOpenCardOrAnywhereElseClosesItAndRunsNothing() {
        host()
        swipeCard(z2m, toRight = true)
        compose.waitForIdle()
        compose.onNodeWithTag("swipe_favorite").assertExists()
        // A tap on the card itself puts it back instead of its main action.
        card(z2m).performTouchInput { click(Offset(width * 0.5f, center.y)) }
        compose.waitForIdle()
        compose.onNodeWithTag("swipe_favorite").assertDoesNotExist()
        assertEquals(0, tapCalls)
        assertEquals(0, favoriteCalls)
        // On an open card its buttons only put it back too.
        swipeCard(z2m, toRight = true)
        compose.waitForIdle()
        compose.onNode(hasAnyAncestor(hasTestTag(cardTag(z2m))) and hasTestTag("card_on"), useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(0, connectCalls)
        compose.onNodeWithTag("swipe_favorite").assertDoesNotExist()
        // Another row opening closes it; so does a tap elsewhere (the list's heading).
        swipeCard(z2m, toRight = true)
        compose.waitForIdle()
        compose.runOnUiThread { scroll.dispatchRawDelta(-1e6f) }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(favoritesTitle).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("swipe_favorite").assertDoesNotExist()
        assertEquals(0, favoriteCalls)
    }

    private fun labelsOfActions(node: androidx.compose.ui.test.SemanticsNodeInteraction): List<String> =
        node.fetchSemanticsNode().config.getOrNull(SemanticsActions.CustomActions).orEmpty().map { it.label }

    @Test fun bothSwipeActionsAreAlsoCustomActionsOnTheCardForTalkBack() {
        host()
        val node = compose.onNode(hasAnyAncestor(hasTestTag(cardTag(z2m))) and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
        val actions = node.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf(text(R.string.terminal_ask_assistant), text(R.string.favorite_add)), actions.map { it.label })
        compose.runOnUiThread { actions.first { it.label == text(R.string.favorite_add) }.action() }
        compose.waitForIdle()
        assertEquals(1, favoriteCalls)
        val panelActions = compose.onNode(hasAnyAncestor(hasTestTag(cardTag(panel))) and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnUiThread { panelActions.first { it.label == text(R.string.terminal_ask_assistant) }.action() }
        compose.waitForIdle()
        assertEquals(text(R.string.ask_assistant_about_tunnel, "panel", "alpha") + " ", asked)
        // A favourite's action takes it out.
        val piActions = compose.onNode(hasAnyAncestor(hasTestTag(cardTag(pi))) and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(text(R.string.favorite_remove), piActions.last().label)
    }

    @Test fun theMenuHasTheFavouriteAndAskItems() {
        host()
        bring(panel)
        compose.onNode(hasAnyAncestor(hasTestTag(cardTag(panel))) and hasTestTag("card_menu"), useUnmergedTree = true).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("menu_ask_assistant").assertExists()
        compose.onNodeWithTag("menu_favorite").assertExists()
        compose.onNodeWithText(text(R.string.favorite_add)).assertExists()
        shot("tunnels-card-menu")
        tap("menu_favorite")
        compose.waitForIdle()
        assertEquals(1, favoriteCalls)
        compose.onNode(hasAnyAncestor(hasTestTag(cardTag(panel))) and hasContentDescription("panel, ${text(R.string.favorite_spoken)} · alpha"), useUnmergedTree = true).assertExists()
    }
}
