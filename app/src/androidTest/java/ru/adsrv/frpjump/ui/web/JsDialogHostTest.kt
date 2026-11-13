package ru.adsrv.frpjump.ui.web

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ui.LocalAppLocked

/** A page's JavaScript dialogs while the app is locked: deferred, shown after unlock, cancelled when the page goes away. */
@RunWith(AndroidJUnit4::class)
class JsDialogHostTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class FakeAnswer : JsAnswer {
        val log = mutableListOf<String>()
        override fun confirm(text: String?) { log += "confirm:$text" }
        override fun cancel() { log += "cancel" }
    }

    /** The app's own strings in the language the run uses (the tests run in English and Russian). */
    private fun text(id: Int) = compose.activity.getString(id)

    private var locked by mutableStateOf(true)
    private val dialogs = JsDialogs()

    private fun show() = compose.setContent {
        CompositionLocalProvider(LocalAppLocked provides locked) { JsDialogHost(dialogs) }
    }

    @Test fun alertWhileLockedWaitsAndAppearsAfterUnlock() {
        show()
        val answer = FakeAnswer()
        compose.runOnIdle { dialogs.show(JsRequest(JsKind.ALERT, "Page says hello", null, "https://a.example", answer)) }
        compose.waitForIdle()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onAllNodes(hasText("Page says hello")).assertCountEquals(0)
        assertEquals("the script is still waiting", emptyList<String>(), answer.log)

        compose.runOnIdle { locked = false }
        compose.waitForIdle()
        compose.onNodeWithText("Page says hello").assertExists()
        compose.onNodeWithText(text(R.string.js_ok)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("confirm:null"), answer.log)
    }

    @Test fun pageGoingAwayWhileLockedCancelsAndNothingShowsAfterUnlock() {
        show()
        val answer = FakeAnswer()
        compose.runOnIdle { dialogs.show(JsRequest(JsKind.CONFIRM, "Sure?", null, "https://a.example", answer)) }
        compose.waitForIdle()
        compose.runOnIdle { dialogs.cancelPending() }
        assertEquals(listOf("cancel"), answer.log)

        compose.runOnIdle { locked = false }
        compose.waitForIdle()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        assertEquals("answered once", listOf("cancel"), answer.log)
    }

    @Test fun unlockedPromptReturnsTheTypedText() {
        locked = false
        show()
        val answer = FakeAnswer()
        compose.runOnIdle { dialogs.show(JsRequest(JsKind.PROMPT, "Name?", "Ann", "https://a.example:8080", answer)) }
        compose.waitForIdle()
        compose.onNodeWithText("Name?").assertExists()
        compose.onNodeWithText(text(R.string.js_ok)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("confirm:Ann"), answer.log)
    }

    @Test fun theTitleIsTheOriginAndAnOpaqueOneIsPage() {
        locked = false
        show()
        compose.runOnIdle { dialogs.show(JsRequest(JsKind.ALERT, "hi", null, "https://a.example:8080", FakeAnswer())) }
        compose.waitForIdle()
        compose.onNodeWithText("https://a.example:8080").assertExists()
        compose.runOnIdle { dialogs.navigated(); dialogs.show(JsRequest(JsKind.ALERT, "hi", null, null, FakeAnswer())) }
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.js_page)).assertExists()
    }

    @Test fun theSecondDialogOffersMuteAndChoosingItAnswersTheNextOnesAtOnce() {
        locked = false
        show()
        val first = FakeAnswer()
        compose.runOnIdle { dialogs.show(JsRequest(JsKind.ALERT, "one", null, "https://a.example", first)) }
        compose.waitForIdle()
        compose.onAllNodes(hasText(text(R.string.js_mute))).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.js_ok)).performClick()
        val second = FakeAnswer()
        compose.runOnIdle { dialogs.show(JsRequest(JsKind.CONFIRM, "two", null, "https://a.example", second)) }
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.js_mute)).performClick()
        compose.onNodeWithText(text(R.string.cancel)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("cancel"), second.log)
        val third = FakeAnswer()
        compose.runOnIdle { dialogs.show(JsRequest(JsKind.ALERT, "three", null, "https://a.example", third)) }
        compose.waitForIdle()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        assertEquals(listOf("confirm:null"), third.log)
    }

    @Test fun leavePageIsAskedAsLeaveOrStay() {
        locked = false
        show()
        val answer = FakeAnswer()
        compose.runOnIdle { dialogs.show(JsRequest(JsKind.BEFORE_UNLOAD, "page text is not shown", null, "https://a.example", answer)) }
        compose.waitForIdle()
        compose.onAllNodes(hasText("page text is not shown")).assertCountEquals(0)
        compose.onNodeWithText(text(R.string.js_stay)).assertExists()
        compose.onNodeWithText(text(R.string.js_leave)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("confirm:null"), answer.log)
    }

    @Test fun aHugePromptDefaultDoesNotEnterTheSavedInstanceState() {
        locked = false
        show()
        val answer = FakeAnswer()
        compose.runOnIdle { dialogs.show(JsRequest(JsKind.PROMPT, "m", "x".repeat(700_000), "https://a.example", answer)) }
        compose.waitForIdle()
        val state = android.os.Bundle()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(compose.activity, state)
        }
        val parcel = android.os.Parcel.obtain()
        parcel.writeBundle(state)
        val size = parcel.dataSize()
        parcel.recycle()
        assertTrue("the saved instance state is $size bytes: a page controls this text and a large state crashes the app", size < 200_000)
    }

    @Test fun theStopOptionIsACheckboxThatExposesItsState() {
        locked = false
        show()
        compose.runOnIdle { dialogs.show(JsRequest(JsKind.ALERT, "one", null, "https://a.example", FakeAnswer())) }
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.js_ok)).performClick()
        compose.runOnIdle { dialogs.show(JsRequest(JsKind.CONFIRM, "two", null, "https://a.example", FakeAnswer())) }
        compose.waitForIdle()
        val box = compose.onNode(androidx.compose.ui.test.hasText(text(R.string.js_mute)).and(androidx.compose.ui.test.isToggleable()))
        box.assertIsOff()
        box.performClick()
        box.assertIsOn()
    }
}
