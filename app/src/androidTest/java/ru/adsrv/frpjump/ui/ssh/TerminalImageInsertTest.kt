package ru.adsrv.frpjump.ui.ssh

import android.graphics.Bitmap
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.R
import com.termux.terminal.TerminalSession
import ru.adsrv.frpjump.image.ImageInsertState
import ru.adsrv.frpjump.sessions.OpenSessions
import ru.adsrv.frpjump.sessions.SessionCloser
import ru.adsrv.frpjump.snippets.SnippetStore
import ru.adsrv.frpjump.ssh.HostKeyStore
import ru.adsrv.frpjump.ssh.KeyInstaller
import ru.adsrv.frpjump.ssh.PastePlan
import ru.adsrv.frpjump.ssh.SshAuth
import ru.adsrv.frpjump.ssh.SshSession
import ru.adsrv.frpjump.ssh.TerminalScreenListener
import ru.adsrv.frpjump.ssh.SshEnvironment
import ru.adsrv.frpjump.ssh.SshKeyStore
import ru.adsrv.frpjump.ssh.SshPasswords
import ru.adsrv.frpjump.ssh.SshSessions
import ru.adsrv.frpjump.ssh.TmuxNames
import ru.adsrv.frpjump.store.SecretBox
import ru.adsrv.frpjump.store.TextStorage
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.FrpJumpTheme

/**
 * "Insert image" on the real TerminalScreen with a tab that is not open (no sockets), and its bar and offer on their own
 * with the real extra keys under them. Plain AndroidJUnitRunner. Orientation and font scale come from the instrumentation
 * arguments the other terminal tests use: `composerOrientation=landscape`, `composerFontScale=2.0` (set the device's font
 * scale first and restore it to 1.0 afterwards). What an Open tab does (picker, upload, insert) is covered by the JVM job
 * and SFTP tests; there is no SSH server on the device here.
 */
@RunWith(AndroidJUnit4::class)
class TerminalImageInsertTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val root = SupervisorJob().apply { cancel() }
    private val scope = CoroutineScope(root + Dispatchers.Main)
    private lateinit var sessions: SshSessions
    private val clipboard get() = compose.activity.getSystemService(ClipboardManager::class.java)

    private class Memory : TextStorage {
        var text: String? = null
        override fun read() = text
        override fun write(text: String) { this.text = text }
        override fun delete() { text = null }
    }

    private val unusedBox = object : SecretBox {
        override fun seal(plain: String): String = error("No secrets in this test")
        override fun open(sealed: String): String = error("No secrets in this test")
        override fun destroy() = Unit
    }

    @Before fun configureDisplay() {
        val args = InstrumentationRegistry.getArguments()
        val landscape = args.getString("composerOrientation") == "landscape"
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (compose.activity.resources.configuration.orientation != expected && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertEquals(expected, compose.activity.resources.configuration.orientation)
        assertEquals(args.getString("composerFontScale")?.toFloat() ?: 1f, compose.activity.resources.configuration.fontScale, 0.01f)
    }

    @After fun cleanup() {
        compose.runOnIdle { if (::sessions.isInitialized) sessions.closeAll(); clipboard.clearPrimaryClip() }
        root.cancel()
    }

    private fun startScreen() {
        val app = compose.activity.application as FrpJumpApp
        val env = SshEnvironment(SshKeyStore(Memory(), unusedBox), SshPasswords(Memory(), unusedBox), { "synthetic" }, TmuxNames(Memory()), visible = MutableStateFlow(true))
        compose.runOnUiThread {
            sessions = SshSessions(app, app.notices, env, flowOf(emptySet()), scope)
            sessions.open("synthetic-profile", HostKeyStore(Memory()), "A", 22, 9, "u", SshAuth.Key)
        }
        val all = OpenSessions(sessions.through, sessions::throughNow, flowOf(emptyList()), { emptyList() }, app.pendingUndo.hidden)
        val closer = SessionCloser(app.pendingUndo, closeTerminal = { sessions.close(it) }, closeMqtt = {})
        val login = KeyLoginFlow(KeyInstaller(env, sessions), scope)
        val snippets = SnippetStore(Memory(), Memory())
        val firstId = sessions.sessions.value.first().id
        compose.setContent {
            FrpJumpTheme {
                TerminalScreen(sessions, all, snippets, app.pendingUndo, closer, emptyMap(), initialId = firstId,
                    onBack = {}, onOpenFiles = {}, rememberLogin = { _, _ -> }, keyLogin = login, keyLoginTarget = { null })
            }
        }
        compose.waitForIdle()
    }

    /** A screenshot as the other terminal tests save them: image-<name>-<orientation>-<font scale>.png in the external cache dir. */
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val config = compose.activity.resources.configuration
        runCatching { instrumentation.uiAutomation.waitForIdle(350, 3_000) }
        val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(instrumentation.targetContext.externalCacheDir, "${if (name.startsWith("entry-")) "" else "image-"}$name-${config.orientation}-${config.fontScale}.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }

    private fun imageClip() {
        val description = ClipDescription("synthetic", arrayOf("image/png"))
        clipboard.setPrimaryClip(ClipData(description, ClipData.Item(Uri.parse("content://synthetic.invalid/1"))))
    }

    @Test fun theMenuItemIsThereAndOffWhileTheTabIsNotOpen() {
        startScreen()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.more)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.terminal_image_insert)).assertIsDisplayed().assertIsNotEnabled()
        capture("menu-disabled")
    }

    @Test fun theMenuItemIsOnWhenItIsEnabled() {
        // The tab-open case in a menu of its own: an Open tab needs an SSH server, which this device test does not have.
        var clicks = 0
        compose.setContent {
            FrpJumpTheme {
                Box {
                    DropdownMenu(expanded = true, onDismissRequest = {}) {
                        DropdownMenuItem(text = { Text(compose.activity.getString(R.string.snippets)) }, onClick = {})
                        InsertImageMenuItem(enabled = true, onClick = { clicks++ })
                    }
                }
            }
        }
        compose.onNodeWithText(compose.activity.getString(R.string.terminal_image_insert)).assertIsDisplayed().assertIsEnabled()
        capture("menu-enabled")
        compose.onNodeWithText(compose.activity.getString(R.string.terminal_image_insert)).performClick()
        assertEquals(1, clicks)
    }

    /** What the screen is told by the selection menu's paste, in place of the screen's own listener. */
    private class Heard : TerminalScreenListener {
        val images = mutableListOf<SshSession>()
        val asks = mutableListOf<PastePlan.Ask>()
        override fun onScreenChanged(session: TerminalSession) = Unit
        override fun onCursorStateChanged(visible: Boolean) = Unit
        override fun onPasteAsk(session: SshSession, ask: PastePlan.Ask) { asks += ask }
        override fun onPasteImage(session: SshSession) { images += session }
    }

    @Test fun theSelectionMenuPasteOfAnImageOnlyClipOffersTheImageAndNeverPastesItsUri() {
        startScreen()
        val heard = Heard()
        compose.runOnIdle {
            sessions.listener = heard
            val tab = sessions.sessions.value.first()
            imageClip()
            tab.terminal.session.onPasteTextFromClipboard()
        }
        assertEquals("the image is offered", 1, heard.images.size)
        assertTrue("and no text paste is planned from the content URI", heard.asks.isEmpty())
        // The same call with text takes the text path: a two-line paste without bracketed paste asks first.
        compose.runOnIdle {
            clipboard.setPrimaryClip(ClipData.newPlainText("synthetic", "one\ntwo"))
            sessions.sessions.value.first().terminal.session.onPasteTextFromClipboard()
        }
        assertEquals("text is never offered as an image", 1, heard.images.size)
        assertEquals(1, heard.asks.size)
    }

    @Test fun anImageOnlyClipboardDoesNotEnablePasteOnATabThatIsNotOpen() {
        compose.runOnUiThread { imageClip() }
        startScreen()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.terminal_paste)).assertIsNotEnabled()
    }

    @Test fun aTextClipboardKeepsPasteOn() {
        compose.runOnUiThread { clipboard.setPrimaryClip(ClipData.newPlainText("synthetic", "text")) }
        startScreen()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.terminal_paste)).assertIsEnabled()
    }

    @Test fun aClipboardWithTextAndAnImageIsATextClipboard() {
        compose.runOnUiThread {
            val description = ClipDescription("synthetic", arrayOf("text/plain", "image/png"))
            clipboard.setPrimaryClip(ClipData(description, ClipData.Item("text")))
        }
        startScreen()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.terminal_paste)).assertIsEnabled()
    }

    private fun overflowed(node: SemanticsNode): Boolean {
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.any { it.hasVisualOverflow }
    }

    /**
     * The terminal screen's frame around the bar: the system-bar and keyboard insets applied as the other terminal tests do
     * (so nothing draws under the status bar and the keys rise above the keyboard), a black terminal area above, the bar,
     * and the real extra keys under it. [withKeyboard] adds a focused field and asks for the keyboard.
     */
    @Composable
    private fun Frame(state: ImageInsertState, cancels: MutableList<Unit>, withKeyboard: Boolean = false) {
        val focus = remember { FocusRequester() }
        val keyboard = LocalSoftwareKeyboardController.current
        if (withKeyboard) LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
        Column(Modifier.fillMaxSize().background(AppTheme.colors.termBar).safeDrawingPadding().imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                if (withKeyboard) BasicTextField("", {}, Modifier.size(1.dp).focusRequester(focus))
            }
            ImageProgressBar(state, onCancel = { cancels += Unit })
            ExtraKeys(remember { Modifiers() }, onKeyboard = {}, onPaste = {}, onKey = {}, textMode = false, onTextMode = {}, textEditor = {}, canOfferImage = true)
        }
    }

    private fun bar(state: ImageInsertState, cancels: MutableList<Unit>, withKeyboard: Boolean = false) {
        compose.setContent { FrpJumpTheme { Frame(state, cancels, withKeyboard) } }
        compose.waitForIdle()
    }

    @Test fun pasteIsOnWithItsImageDescriptionWhenTheClipboardHasAPictureAndAnImageMayBeOffered() {
        compose.runOnUiThread { imageClip() }
        bar(ImageInsertState.Encoding, mutableListOf())
        val paste = compose.onNodeWithContentDescription(compose.activity.getString(R.string.terminal_paste)).assertIsEnabled()
        assertEquals(compose.activity.getString(R.string.terminal_image_clipboard_has_image), paste.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        capture("bar-preparing-above-keys")
    }

    @Test fun theBarSaysPreparingThenTheStepOfTheUploadAndCancelWorks() {
        val cancels = mutableListOf<Unit>()
        val state = mutableStateOf<ImageInsertState>(ImageInsertState.Encoding)
        compose.setContent { FrpJumpTheme { Frame(state.value, cancels) } }
        compose.onNodeWithText(compose.activity.getString(R.string.terminal_image_preparing)).assertIsDisplayed()
        capture("bar-preparing")
        compose.runOnUiThread { state.value = ImageInsertState.Uploading(459, 1000) }
        compose.onNodeWithText(compose.activity.getString(R.string.terminal_image_uploading, 40)).assertIsDisplayed()
        compose.runOnUiThread { state.value = ImageInsertState.Uploading(1000, 1000) }
        compose.onNodeWithText(compose.activity.getString(R.string.terminal_image_uploading, 100)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.cancel)).performClick()
        assertEquals(1, cancels.size)
    }

    @Test fun theBarFitsAndHidesNoKeyAtTheCurrentOrientationAndFontScale() {
        bar(ImageInsertState.Uploading(450, 1000), mutableListOf())
        val label = compose.activity.getString(R.string.terminal_image_uploading, 40)
        val text = compose.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode()
        capture("bar-uploading-above-keys")
        assertFalse("the bar's words are cut", overflowed(text))
        val cancel = compose.onNodeWithText(compose.activity.getString(R.string.cancel)).assertIsDisplayed().fetchSemanticsNode()
        // The disclosure popup has its own window, so compare on-screen rectangles.
        val more = compose.onNodeWithTag("terminal_more_keys").fetchSemanticsNode()
        fun onScreen(node: androidx.compose.ui.semantics.SemanticsNode) =
            Rect(node.positionOnScreen, androidx.compose.ui.geometry.Size(node.size.width.toFloat(), node.size.height.toFloat()))
        assertFalse("Cancel ${onScreen(cancel)} is under the keys' disclosure ${onScreen(more)}", onScreen(cancel).overlaps(onScreen(more)))
        val keys = compose.onNodeWithTag("terminal_main_keys").assertIsDisplayed().fetchSemanticsNode()
        val barBox: Rect = text.boundsInRoot
        assertTrue("the bar sits above the keys", barBox.bottom <= keys.boundsInRoot.top + 1f)
        // Every main key is still on screen.
        val width = compose.activity.resources.displayMetrics.widthPixels
        assertTrue(keys.boundsInRoot.left >= 0f && keys.boundsInRoot.right <= width + 1f)
    }

    @Test fun theOfferAsksAndInsertsOnlyOnTheButton() {
        val inserted = mutableListOf<Unit>()
        val dismissed = mutableListOf<Unit>()
        compose.setContent { FrpJumpTheme { ImageOfferSheet(onInsert = { inserted += Unit }, onDismiss = { dismissed += Unit }) } }
        compose.onNodeWithText(compose.activity.getString(R.string.terminal_image_offer_title)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.terminal_image_offer_text)).assertIsDisplayed()
        capture("offer")
        assertTrue(inserted.isEmpty())
        compose.onNodeWithText(compose.activity.getString(R.string.terminal_image_insert)).performClick()
        compose.waitForIdle()
        assertEquals(1, inserted.size)
    }

    @Test fun theBatchBarSaysTheImageOfTheBatchAndFitsWithCancelAndTheDisclosure() {
        val cancels = mutableListOf<Unit>()
        val state = mutableStateOf<ImageInsertState>(ImageInsertState.Preparing(2, 5))
        compose.setContent { FrpJumpTheme { Frame(state.value, cancels) } }
        compose.onNodeWithText(compose.activity.getString(R.string.terminal_images_preparing, 2, 5)).assertIsDisplayed()
        compose.runOnUiThread { state.value = ImageInsertState.Uploading(450, 1000, 2, 5) }
        val label = compose.activity.getString(R.string.terminal_images_uploading, 2, 5, 40)
        val text = compose.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode()
        capture("multi-bar-uploading-above-keys")
        assertFalse("the bar's words are cut", overflowed(text))
        val cancel = compose.onNodeWithText(compose.activity.getString(R.string.cancel)).assertIsDisplayed().fetchSemanticsNode()
        val more = compose.onNodeWithTag("terminal_more_keys").fetchSemanticsNode()
        fun onScreen(node: androidx.compose.ui.semantics.SemanticsNode) =
            Rect(node.positionOnScreen, androidx.compose.ui.geometry.Size(node.size.width.toFloat(), node.size.height.toFloat()))
        assertFalse("Cancel ${onScreen(cancel)} is under the keys' disclosure ${onScreen(more)}", onScreen(cancel).overlaps(onScreen(more)))
        val keys = compose.onNodeWithTag("terminal_main_keys").assertIsDisplayed().fetchSemanticsNode()
        assertTrue("the bar sits above the keys", text.boundsInRoot.bottom <= keys.boundsInRoot.top + 1f)
        compose.onNodeWithText(compose.activity.getString(R.string.cancel)).performClick()
        assertEquals(1, cancels.size)
    }

    @Test fun theOfferIsTheSameForSeveralImagesAndInsertsOnlyOnTheButton() {
        val inserted = mutableListOf<Unit>()
        compose.setContent { FrpJumpTheme { ImageOfferSheet(onInsert = { inserted += Unit }, onDismiss = {}) } }
        compose.onNodeWithText(compose.activity.getString(R.string.terminal_image_offer_title)).assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.terminal_image_offer_text)).assertIsDisplayed()
        capture("multi-offer")
        assertTrue(inserted.isEmpty())
        compose.onNodeWithText(compose.activity.getString(R.string.terminal_image_insert)).performClick()
        compose.waitForIdle()
        assertEquals(1, inserted.size)
    }

    @Test fun theBarAndKeysStayVisibleWithTheKeyboardUp() {
        bar(ImageInsertState.Uploading(450, 1000), mutableListOf(), withKeyboard = true)
        // The keyboard needs a moment, and a device with a hardware keyboard may not show it: the screenshot says which.
        Thread.sleep(1_500)
        compose.waitForIdle()
        capture("bar-uploading-keyboard")
        compose.onNodeWithText(compose.activity.getString(R.string.cancel)).assertIsDisplayed()
        compose.onNodeWithTag("terminal_main_keys").assertIsDisplayed()
        val text = compose.onNodeWithText(compose.activity.getString(R.string.terminal_image_uploading, 40)).assertIsDisplayed().fetchSemanticsNode()
        assertFalse("the bar's words are cut", overflowed(text))
    }

    /** The real extra keys with Paste counted: what a tap pastes and how often the picker is asked for. */
    private fun keysWithCounts(canOfferImage: Boolean, pastes: MutableList<Unit>, pickers: MutableList<Unit>) {
        compose.setContent {
            FrpJumpTheme {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Box(Modifier.weight(1f))
                    ExtraKeys(remember { Modifiers() }, onKeyboard = {}, onPaste = { pastes += Unit }, onKey = {}, textMode = false, onTextMode = {},
                        textEditor = {}, canOfferImage = canOfferImage, onInsertImage = { pickers += Unit })
                }
            }
        }
        compose.waitForIdle()
    }

    private val pasteKey get() = compose.onNodeWithContentDescription(compose.activity.getString(R.string.terminal_paste))

    @Test fun aLongPressOnPasteOpensThePickerAlsoWithAnEmptyClipboardAndATapOnlyPastes() {
        val pastes = mutableListOf<Unit>()
        val pickers = mutableListOf<Unit>()
        compose.runOnUiThread { clipboard.clearPrimaryClip() }
        keysWithCounts(true, pastes, pickers)
        capture("entry-paste-empty-clipboard")
        pasteKey.performClick()
        assertEquals("a tap with an empty clipboard pastes nothing and opens no picker", 0, pastes.size + pickers.size)
        assertEquals(compose.activity.getString(R.string.terminal_image_clipboard_empty_offer), pasteKey.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        pasteKey.performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(1, pickers.size)
        assertEquals(0, pastes.size)
    }

    @Test fun aTapOnPasteWithTextPastesAndNeverOpensThePicker() {
        val pastes = mutableListOf<Unit>()
        val pickers = mutableListOf<Unit>()
        compose.runOnUiThread { clipboard.setPrimaryClip(ClipData.newPlainText("synthetic", "text")) }
        keysWithCounts(true, pastes, pickers)
        capture("entry-paste-text-clipboard")
        pasteKey.performClick()
        assertEquals(1, pastes.size)
        assertEquals(0, pickers.size)
    }

    @Test fun aLongPressOnPasteDoesNothingWhenNoImageMayBeOffered() {
        val pastes = mutableListOf<Unit>()
        val pickers = mutableListOf<Unit>()
        compose.runOnUiThread { clipboard.setPrimaryClip(ClipData.newPlainText("synthetic", "text")) }
        keysWithCounts(false, pastes, pickers)
        pasteKey.performTouchInput { longClick() }
        compose.waitForIdle()
        assertEquals(0, pickers.size)
        assertEquals("a long press is not a tap", 0, pastes.size)
    }

    @Test fun withNoImageOfferedPasteHasNoLongClickActionOrLabel() {
        compose.runOnUiThread { clipboard.setPrimaryClip(ClipData.newPlainText("synthetic", "text")) }
        keysWithCounts(false, mutableListOf(), mutableListOf())
        val config = pasteKey.fetchSemanticsNode().config
        assertFalse("no long-click action", config.contains(SemanticsActions.OnLongClick))
        assertTrue(config.contains(SemanticsActions.OnClick))
        compose.runOnUiThread { clipboard.clearPrimaryClip() }
        compose.waitForIdle()
        pasteKey.assertIsNotEnabled()
        assertFalse(pasteKey.fetchSemanticsNode().config.contains(SemanticsActions.OnLongClick))
    }

    @Test fun pasteHasATalkBackLongClickLabelInsertImage() {
        compose.runOnUiThread { clipboard.clearPrimaryClip() }
        keysWithCounts(true, mutableListOf(), mutableListOf())
        assertEquals(compose.activity.getString(R.string.terminal_image_insert), pasteKey.fetchSemanticsNode().config[SemanticsActions.OnLongClick].label)
    }

    /** The real editor in a harness with a fixed draft: what Insert and the image button call, and what the draft is after. */
    @Composable
    private fun EditorFrame(
        text: String, canImage: Boolean, calls: MutableList<String>, state: ImageInsertState?, expanded: Boolean,
    ) {
        val focus = remember { FocusRequester() }
        var value by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
        val editor: @Composable () -> Unit = {
            TerminalTextComposer(
                1, value, { value = it }, focus, canInsert = true, onInsert = { calls += "insert" }, onExpand = {}, expanded = expanded,
                onKeys = {}, onInsertAndEnter = { calls += "insert-enter" }, canInsertImage = canImage, onInsertImage = { calls += "image" },
            )
        }
        Column(Modifier.fillMaxSize().background(AppTheme.colors.termBar).safeDrawingPadding().imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black))
            if (expanded) {
                FullscreenTerminalText(onCollapse = {}) {
                    state?.let { ImageProgressBar(it, onCancel = { calls += "cancel" }, keysShown = false) }
                    editor()
                }
            } else {
                state?.let { ImageProgressBar(it, onCancel = { calls += "cancel" }, keysShown = false) }
                ExtraKeys(remember { Modifiers() }, onKeyboard = {}, onPaste = {}, onKey = {}, textMode = true, onTextMode = {}, textEditor = editor)
            }
        }
    }

    private fun editor(canImage: Boolean = true, state: ImageInsertState? = null, expanded: Boolean = false, text: String = "draft text"): MutableList<String> {
        val calls = mutableListOf<String>()
        compose.setContent { FrpJumpTheme { EditorFrame(text, canImage, calls, state, expanded) } }
        compose.waitForIdle()
        return calls
    }

    /** Compose idle, then the window (insets and IME animations outlive Compose's idle), then Compose again. */
    private fun settleWindow() {
        compose.waitForIdle()
        runCatching { InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 4_000) }
        compose.waitForIdle()
    }

    private val imageButton get() = compose.onNodeWithTag("terminal_text_image")

    @Test fun theEditorsImageButtonIsLabelledOnWhenAvailableAndOpensOnlyThePicker() {
        val calls = editor()
        imageButton.assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.terminal_image_insert)).assertIsDisplayed()
        capture("entry-editor-compact")
        imageButton.performClick()
        compose.waitForIdle()
        assertEquals("the button neither inserts the draft nor submits it", listOf("image"), calls)
        compose.onNodeWithTag("terminal_text_editor").assertTextEquals("draft text")
    }

    @Test fun theEditorsImageButtonIsOffWhenTheTabIsNotOpenOrBusy() {
        val calls = editor(canImage = false)
        imageButton.assertIsDisplayed().assertIsNotEnabled().performClick()
        assertTrue(calls.isEmpty())
    }

    @Test fun theExpandedEditorHasTheImageButtonToo() {
        val calls = editor(expanded = true)
        imageButton.assertIsDisplayed().assertIsEnabled()
        capture("entry-editor-expanded")
        imageButton.performClick()
        assertEquals(listOf("image"), calls)
        compose.onNodeWithTag("terminal_text_editor").assertTextEquals("draft text")
    }

    private fun checkBarFitsWithEditor(expanded: Boolean, shot: String) {
        val calls = editor(state = ImageInsertState.Uploading(450, 1000), expanded = expanded)
        // The editor asks for the keyboard when it appears; measure only once the window has settled under it (a bar measured
        // while the keyboard is still sliding in is squeezed for a moment). The assertions are unchanged.
        settleWindow()
        val label = compose.activity.getString(R.string.terminal_image_uploading, 40)
        val text = compose.onNodeWithText(label).assertIsDisplayed().fetchSemanticsNode()
        assertFalse("the bar's words are cut", overflowed(text))
        val cancel = compose.onNodeWithText(compose.activity.getString(R.string.cancel)).assertIsDisplayed()
        compose.onNodeWithTag("terminal_text_insert").assertIsDisplayed()
        compose.onNodeWithTag("terminal_text_editor").assertIsDisplayed()
        imageButton.assertIsDisplayed()
        capture(shot)
        // Cancel sits at the end of the panel: no disclosure reserve in text mode.
        val width = compose.activity.resources.displayMetrics.widthPixels
        val right = cancel.fetchSemanticsNode().let { it.positionOnScreen.x + it.size.width }
        val slack = 24 * compose.activity.resources.displayMetrics.density
        assertTrue("Cancel ends at ${right}px of ${width}px; no disclosure reserve is wanted here", right >= width - slack - 40 * compose.activity.resources.displayMetrics.density)
        cancel.performClick()
        assertEquals(listOf("cancel"), calls)
        // The bar is above the editor, and the Insert action is not covered by it.
        val bar = text.boundsInRoot
        val insert = compose.onNodeWithTag("terminal_text_insert").fetchSemanticsNode().boundsInRoot
        assertTrue("the bar must not overlap Insert", bar.bottom <= insert.top + 1f || bar.top >= insert.bottom - 1f)
    }

    @Test fun theBarAndCancelFitInTheCompactEditorWithoutHidingInsertOrTheField() = checkBarFitsWithEditor(false, "entry-editor-bar-compact")

    @Test fun theBarAndCancelFitInTheExpandedEditorWithoutHidingInsertOrTheField() = checkBarFitsWithEditor(true, "entry-editor-bar-expanded")

    @Test fun theEditorsControlsAreNotWrappedOrCutAtTheCurrentOrientationAndFontScale() {
        editor()
        // The editor asks for the keyboard when it appears; what an earlier test left (an IME still moving, a window not yet
        // gone) must settle first, or the row is measured above a keyboard that is on its way. The assertions below are unchanged.
        settleWindow()
        val tags = listOf("terminal_mode_keys", "terminal_text_image", "terminal_text_expand", "terminal_text_insert")
        compose.waitUntil(5_000) { tags.all { runCatching { compose.onNodeWithTag(it).assertIsDisplayed() }.isSuccess } }
        val word = compose.onNodeWithText(compose.activity.getString(R.string.terminal_text_insert), useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
        val results = mutableListOf<TextLayoutResult>()
        word.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        assertTrue("Insert is on one line", results.all { it.lineCount == 1 })
        val box = compose.onNodeWithTag("terminal_text_insert").fetchSemanticsNode().boundsInRoot
        assertTrue("Insert is cut by its button", word.boundsInRoot.left >= box.left - 1f && word.boundsInRoot.right <= box.right + 1f)
        // Every control is on screen and the five do not overlap.
        val width = compose.activity.resources.displayMetrics.widthPixels
        val boxes = listOf("terminal_mode_keys", "terminal_text_image", "terminal_text_expand", "terminal_text_insert").map {
            compose.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().let { n -> Rect(n.positionOnScreen, androidx.compose.ui.geometry.Size(n.size.width.toFloat(), n.size.height.toFloat())) }
        }
        boxes.forEach { assertTrue("$it is off screen", it.left >= 0f && it.right <= width + 1f) }
        boxes.zipWithNext().forEach { (a, b) -> assertFalse("$a overlaps $b", a.overlaps(b)) }
    }
}
