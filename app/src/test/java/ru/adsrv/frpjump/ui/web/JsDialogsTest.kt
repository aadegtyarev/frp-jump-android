package ru.adsrv.frpjump.ui.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one waiting JavaScript dialog: answered once, cancelled when replaced or when the page goes away; spam stop; origin title. */
class JsDialogsTest {
    private class FakeAnswer : JsAnswer {
        val log = mutableListOf<String>()
        override fun confirm(text: String?) { log += "confirm:$text" }
        override fun cancel() { log += "cancel" }
    }

    private val dialogs = JsDialogs()

    private var last: JsRequest? = null

    private fun request(kind: JsKind, answer: JsAnswer, default: String? = null) =
        JsRequest(kind, "m", default, "https://a.example", answer).also { last = it }

    /** The owner answers the dialog on screen (or, with none, the last one made: a stale answer). */
    private fun answerShown(ok: Boolean, text: String? = null, mute: Boolean = false) =
        dialogs.answer(dialogs.pending?.request ?: last!!, ok, text, mute)

    @Test fun answerIsGivenOnce() {
        val a = FakeAnswer()
        dialogs.show(request(JsKind.PROMPT, a, "d"))
        answerShown(true, "x")
        answerShown(false)
        dialogs.cancelPending()
        assertEquals(listOf("confirm:x"), a.log)
        assertNull(dialogs.pending)
    }

    @Test fun cancelAnswersWithCancel() {
        val a = FakeAnswer()
        dialogs.show(request(JsKind.CONFIRM, a))
        dialogs.cancelPending()
        assertEquals(listOf("cancel"), a.log)
        assertNull(dialogs.pending)
    }

    @Test fun aNewQuestionCancelsALeftoverOne() {
        val old = FakeAnswer()
        val new = FakeAnswer()
        dialogs.show(request(JsKind.ALERT, old))
        dialogs.show(request(JsKind.ALERT, new))
        assertEquals(listOf("cancel"), old.log)
        assertEquals(emptyList<String>(), new.log)
    }

    @Test fun theMuteChoiceIsOfferedFromTheSecondDialogOn() {
        dialogs.show(request(JsKind.ALERT, FakeAnswer()))
        assertFalse(dialogs.pending!!.canMute)
        answerShown(true)
        dialogs.show(request(JsKind.ALERT, FakeAnswer()))
        assertTrue(dialogs.pending!!.canMute)
        assertEquals(2, dialogs.asked)
        answerShown(true)
        dialogs.show(request(JsKind.ALERT, FakeAnswer()))
        assertTrue(dialogs.pending!!.canMute)
    }

    @Test fun afterMuteTheDialogsAreAnsweredAtOncePerKind() {
        dialogs.show(request(JsKind.ALERT, FakeAnswer()))
        answerShown(true)
        dialogs.show(request(JsKind.CONFIRM, FakeAnswer()))
        answerShown(false, mute = true)
        assertTrue(dialogs.muted)

        val alert = FakeAnswer()
        val confirm = FakeAnswer()
        val prompt = FakeAnswer()
        val unload = FakeAnswer()
        dialogs.show(request(JsKind.ALERT, alert))
        dialogs.show(request(JsKind.CONFIRM, confirm))
        dialogs.show(request(JsKind.PROMPT, prompt, "d"))
        dialogs.show(request(JsKind.BEFORE_UNLOAD, unload))
        assertEquals(listOf("confirm:null"), alert.log)
        assertEquals(listOf("cancel"), confirm.log)
        assertEquals(listOf("cancel"), prompt.log)
        assertEquals("a muted page may be left", listOf("confirm:null"), unload.log)
        assertNull("nothing is shown", dialogs.pending)
    }

    @Test fun muteChosenWithOkAnswersThatDialogToo() {
        dialogs.show(request(JsKind.ALERT, FakeAnswer()))
        answerShown(true)
        val second = FakeAnswer()
        dialogs.show(request(JsKind.CONFIRM, second))
        answerShown(true, mute = true)
        assertEquals(listOf("confirm:null"), second.log)
        assertTrue(dialogs.muted)
    }

    @Test fun aNewDocumentStartsTheCountAndTheMuteOver() {
        dialogs.show(request(JsKind.ALERT, FakeAnswer()))
        answerShown(true)
        dialogs.show(request(JsKind.ALERT, FakeAnswer()))
        answerShown(true, mute = true)
        val waiting = FakeAnswer()
        dialogs.navigated()
        assertFalse(dialogs.muted)
        assertEquals(0, dialogs.asked)
        dialogs.show(request(JsKind.ALERT, waiting))
        assertEquals("shown again after the navigation", emptyList<String>(), waiting.log)
        assertFalse("the first dialog of the new page offers no mute", dialogs.pending!!.canMute)
    }

    @Test fun navigationCancelsWhatWaits() {
        val a = FakeAnswer()
        dialogs.show(request(JsKind.CONFIRM, a))
        dialogs.navigated()
        assertEquals(listOf("cancel"), a.log)
        assertNull(dialogs.pending)
    }

    @Test fun originIsSchemeHostAndPort() {
        assertEquals("https://example.com", jsOrigin("https://example.com/a/b?c#d"))
        assertEquals("http://192.168.1.5:8080", jsOrigin("http://192.168.1.5:8080/x"))
        assertEquals("https://example.com:443", jsOrigin("https://example.com:443/"))
        assertEquals("http://[::1]:3000", jsOrigin("http://[::1]:3000/"))
        assertEquals("https://example.com", jsOrigin("HTTPS://Example.COM/Path"))
        assertEquals("http://127.0.0.1:41234", jsOrigin("http://127.0.0.1:41234"))
    }

    @Test fun originDoesNotShowUserInfoOrALookalikeHost() {
        assertEquals("https://evil.example", jsOrigin("https://app.frp-jump.local@evil.example/"))
        assertEquals("http://xn--e1afmkfd.xn--p1ai", jsOrigin("http://пример.рф/"))
        assertEquals("http://xn--e1afmkfd.xn--p1ai:8080", jsOrigin("http://пример.рф:8080/x"))
        assertEquals("http://xn--e1afmkfd.xn--p1ai", jsOrigin("http://xn--e1afmkfd.xn--p1ai/"))
    }

    @Test fun anOpaqueOrEmptyOriginHasNoNameSoTheDialogSaysPage() {
        listOf(null, "", "  ", "null", "about:blank", "about:", "data:text/html,<b>x</b>", "file:///sdcard/x.html", "javascript:alert(1)", "blob:null/1234", "https://").forEach {
            assertNull("[$it]", jsOrigin(it))
        }
    }

    @Test fun theMessageIsPlainAndBounded() {
        assertEquals("<a href=\"x\">Enter your password</a>", jsMessage("<a href=\"x\">Enter your password</a>"))
        assertEquals("", jsMessage(null))
        assertEquals(1001, jsMessage("a".repeat(5000)).length)
        assertTrue(jsMessage("a".repeat(5000)).endsWith("…"))
    }

    @Test fun anAnswerToAnotherRequestThanTheOneOnScreenIsIgnored() {
        val old = FakeAnswer()
        val shown = FakeAnswer()
        val oldRequest = request(JsKind.CONFIRM, old)
        dialogs.show(oldRequest)
        dialogs.show(request(JsKind.CONFIRM, shown))
        dialogs.answer(oldRequest, true)
        assertEquals("the replaced request was cancelled once, not confirmed", listOf("cancel"), old.log)
        assertEquals(emptyList<String>(), shown.log)
        assertEquals("the one on screen is still waiting", "m", dialogs.pending?.request?.message)
    }

    @Test fun theMessageAndThePromptDefaultAreCutWithoutSplittingAPair() {
        val pair = "\uD83D\uDE00"
        val long = "a".repeat(999) + pair + "tail"
        assertEquals("a".repeat(999) + "…", jsMessage(long))
        assertEquals("a".repeat(999), jsPromptDefault(long))
        assertEquals(1000, jsPromptDefault("x".repeat(700_000)).length)
        assertEquals("short", jsPromptDefault("short"))
        assertEquals("", jsPromptDefault(null))
    }

    @Test fun onlyAPageStartIsANewDocument() {
        dialogs.show(request(JsKind.ALERT, FakeAnswer()))
        answerShown(true)
        dialogs.show(request(JsKind.ALERT, FakeAnswer()))
        answerShown(true, mute = true)
        dialogs.onPage(PageEvent.FINISHED)
        dialogs.onPage(PageEvent.HISTORY)
        assertTrue("a finished load or an in-page route is the same document", dialogs.muted)
        val waiting = FakeAnswer()
        dialogs.muted.let { dialogs.onPage(PageEvent.STARTED) }
        assertFalse(dialogs.muted)
        assertEquals(0, dialogs.asked)
        dialogs.show(request(JsKind.CONFIRM, waiting))
        dialogs.onPage(PageEvent.STARTED)
        assertEquals("a waiting question goes with its page", listOf("cancel"), waiting.log)
    }
}
