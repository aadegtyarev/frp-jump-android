package ru.adsrv.frpjump.ui.ssh

import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ui.FrpJumpTheme
import java.io.File
import java.util.Locale

/**
 * The sheet a tap on a terminal link opens (Р37): the address, "Copy" and "Open"; no "Open" for anything that must not open. The browser
 * is never started here: the test passes its own opener. Screenshots go to the app's external files dir, named by configuration.
 */
@RunWith(AndroidJUnit4::class)
class LinkSheetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val args = InstrumentationRegistry.getArguments()
    private val opened = ArrayList<String>()
    private var dismissed = 0
    private var browserThere = true
    private fun text(id: Int, vararg a: Any) = compose.activity.getString(id, *a)

    @Before fun display() {
        val landscape = args.getString("composerOrientation") == "landscape"
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
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

    private fun shot(name: String) {
        compose.waitForIdle(); SystemClock.sleep(400); compose.waitForIdle()
        val tag = listOf(args.getString("composerOrientation") ?: "portrait", args.getString("composerFontScale") ?: "1.0", args.getString("chatLocale") ?: "en").joinToString("-")
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        File(compose.activity.getExternalFilesDir(null), "link-$name-$tag.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private val shown = mutableStateOf("")
    private var started = false

    /** Shows the sheet for [link]; called again, the same sheet takes the next one. */
    private fun show(link: String) {
        shown.value = link
        if (!started) {
            started = true
            compose.setContent {
                FrpJumpTheme {
                    var open by remember { mutableStateOf(true) }
                    if (open) LinkSheet(shown.value, onDismiss = { dismissed++; open = false }, open = { _: Context, address: String -> opened += address; browserThere })
                }
            }
        }
        compose.waitForIdle()
    }

    private fun address() = compose.onNodeWithTag("link_address", useUnmergedTree = true)
    private fun noOpen() = assertTrue(compose.onAllNodesWithTag("link_open").fetchSemanticsNodes().isEmpty())

    @Test fun anHttpsLinkShowsItsAddressWithCopyAndOpenAndOpensNothingByItself() {
        show("https://deb.example.com/wb8/bullseye")
        address().assertTextEquals("https://deb.example.com/wb8/bullseye")
        compose.onNodeWithTag("link_copy").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("link_open").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("link_note").assertDoesNotExist()
        assertTrue("nothing opens before the tap on Open", opened.isEmpty())
        // The address is read out whole.
        address().assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("https://deb.example.com/wb8/bullseye")))
        shot("https")
    }

    @Test fun openHandsTheAddressToTheBrowserAndClosesTheSheet() {
        show("https://deb.example.com/wb8/bullseye")
        compose.onNodeWithTag("link_open").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("https://deb.example.com/wb8/bullseye"), opened)
            assertEquals(1, dismissed)
        }
    }

    @Test fun withNoBrowserTheSheetStaysOpen() {
        browserThere = false
        show("https://example.com/x")
        compose.onNodeWithTag("link_open").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("https://example.com/x"), opened)
            assertEquals(0, dismissed)
        }
        address().assertTextEquals("https://example.com/x")
        shot("no-browser")
    }

    @Test fun copyPutsTheAddressOnTheClipboardAndCloses() {
        show("https://example.com/a?b=c")
        compose.onNodeWithTag("link_copy").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, dismissed)
            assertTrue(opened.isEmpty())
            val clip = compose.activity.getSystemService(ClipboardManager::class.java).primaryClip
            assertEquals("https://example.com/a?b=c", clip?.getItemAt(0)?.text?.toString())
        }
    }

    @Test fun aNonLatinDomainShowsAndOpensPunycodeAndSaysWhatTheTextWas() {
        show("https://аррӏе.com/login")
        address().assertTextEquals("https://xn--80ak6aa92e.com/login")
        compose.onNodeWithTag("link_note").performScrollTo().assertTextContains("аррӏе.com", substring = true)
        compose.onNodeWithTag("link_open").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("https://xn--80ak6aa92e.com/login"), opened) }
    }

    @Test fun anAddressOfTheRemoteMachineHasOnlyCopyAndSaysSo() {
        show("http://localhost:5173/")
        address().assertTextEquals("http://localhost:5173/")
        compose.onNodeWithTag("link_note").assertTextEquals(text(R.string.link_remote))
        compose.onNodeWithTag("link_copy").performScrollTo().assertIsDisplayed()
        noOpen()
        shot("remote")
    }

    @Test fun otherLoopbackFormsAlsoHaveNoOpen() {
        for (link in listOf("http://127.0.0.1:8080/", "http://[::1]/x", "http://0.0.0.0/", "http://2130706433/")) {
            show(link)
            compose.onNodeWithTag("link_note").assertTextEquals(text(R.string.link_remote))
            noOpen()
        }
    }

    @Test fun anotherSchemeHasOnlyCopyAndSaysOnlyHttpOpens() {
        show("ftp://files.example.com/pub/a.iso")
        address().assertTextEquals("ftp://files.example.com/pub/a.iso")
        compose.onNodeWithTag("link_note").assertTextEquals(text(R.string.link_other_scheme))
        compose.onNodeWithTag("link_copy").performScrollTo().assertIsDisplayed()
        noOpen()
        shot("ftp")
    }

    @Test fun dangerousSchemesAreNeverOffered() {
        for (link in listOf("javascript://%0Aalert(1)", "intent://scan/#Intent;scheme=zxing;end", "file:///etc/passwd", "content://com.android.contacts/contacts")) {
            show(link)
            noOpen()
            compose.onNodeWithTag("link_copy").performScrollTo().assertIsDisplayed()
        }
        assertTrue(opened.isEmpty())
    }

    @Test fun hiddenAndBidiCharactersAreShownAsMarksAndHaveNoOpen() {
        show("https://example.com/‮fdp.exe")
        address().assertTextContains("⟨U+202E⟩", substring = true)
        compose.onNodeWithTag("link_note").assertTextEquals(text(R.string.link_hidden))
        noOpen()
        compose.onNodeWithTag("link_copy").performScrollTo().assertIsDisplayed()
        shot("hidden")
    }

    @Test fun aLongAddressWrapsInFullAndKeepsTheButtonsReachable() {
        val long = "https://example.com/" + (1..40).joinToString("/") { "segment$it" } + "?token=" + "x".repeat(200)
        show(long)
        address().assertTextEquals(long)
        compose.onNodeWithTag("link_copy").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("link_open").performScrollTo().assertIsDisplayed()
        shot("long")
    }

    @Test fun theListOfLinksOnTheScreenOpensTheChosenOne() {
        var picked: String? = null
        val links = listOf("https://deb.example.com/wb8", "http://localhost:5173/", "https://github.com/org/repo")
        compose.setContent { FrpJumpTheme { LinksListSheet(links, onPick = { picked = it }, onDismiss = {}) } }
        compose.waitForIdle()
        assertEquals(3, compose.onAllNodesWithTag("links_item").fetchSemanticsNodes().size)
        shot("list")
        compose.onAllNodesWithTag("links_item")[1].performScrollTo().performClick()
        compose.runOnIdle { assertEquals("http://localhost:5173/", picked) }
    }
}
