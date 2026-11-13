package ru.adsrv.frpjump.ui.web

import android.os.SystemClock
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The production `configure()` on a real WebView, with real pages (served from 127.0.0.1) that call `alert`, `confirm` and
 * `prompt`: the questions reach [JsDialogs] (so the app, not WebView, shows them), carry the page's origin, block the page's
 * script until answered, are cancelled when the next page starts, and the spam stop answers the rest. The overrides must
 * return true (false would let WebView show its own window over the lock screen).
 */
@RunWith(AndroidJUnit4::class)
class WebJsDialogsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val dialogs = JsDialogs()
    private var web: WebView? = null
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val pages = mutableMapOf<String, String>()
    private val port get() = server.localPort

    init {
        Thread {
            try {
                while (true) {
                    val socket = server.accept()
                    Thread {
                        socket.use {
                            val request = it.getInputStream().bufferedReader().readLine().orEmpty()
                            val path = request.split(" ").getOrElse(1) { "/" }
                            val body = (pages[path] ?: "").toByteArray()
                            it.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                                write(body)
                                flush()
                            }
                        }
                    }.start()
                }
            } catch (_: SocketException) {
            }
        }.start()
    }

    private fun open(path: String, html: String): WebView {
        pages[path] = html
        var view: WebView? = null
        instrumentation.runOnMainSync {
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            view = WebView(context).also { w ->
                configure(
                    w, context, tunnelPort = port,
                    // As WebScreen wires it: a new document starts over.
                    onPage = { _, event -> dialogs.onPage(event) },
                    onTitle = {}, onProgress = {}, onRendererGone = {}, onMainFrameError = { _, _, _ -> },
                    onSslError = { handler, _ -> handler.cancel() }, onChooseFiles = { _, _ -> },
                    onJsDialog = dialogs::show,
                )
                w.loadUrl("http://127.0.0.1:$port$path")
            }
        }
        web = view
        return view!!
    }

    @After fun close() {
        server.close()
        instrumentation.runOnMainSync { web?.destroy() }
    }

    private fun waitFor(what: String, ms: Long = 20_000, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + ms
        while (!check() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertEquals(what, true, check())
    }

    private fun title(): String? { var t: String? = null; instrumentation.runOnMainSync { t = web?.title }; return t }

    private fun pending() = dialogs.pending?.request

    private fun answer(ok: Boolean, text: String? = null, mute: Boolean = false) {
        instrumentation.runOnMainSync { dialogs.answer(dialogs.pending!!.request, ok, text, mute) }
    }

    @Test fun alertConfirmAndPromptAreAskedByTheAppAndBlockTheScriptUntilAnswered() {
        open("/p", "<title>start</title><script>var a=alert('A'); var c=confirm('B'); var p=prompt('C','dflt'); document.title='done:'+c+':'+p;</script>")
        waitFor("the alert") { pending() != null }
        assertEquals(JsKind.ALERT, pending()!!.kind)
        assertEquals("A", pending()!!.message)
        assertEquals("the page's origin, not its title", "http://127.0.0.1:$port", pending()!!.origin)
        assertEquals("the script waits", "start", title())
        answer(true)
        waitFor("the confirm") { pending()?.kind == JsKind.CONFIRM }
        answer(false)
        waitFor("the prompt") { pending()?.kind == JsKind.PROMPT }
        assertEquals("dflt", pending()!!.defaultValue)
        answer(true, "typed")
        waitFor("the script went on with the answers") { title() == "done:false:typed" }
    }

    @Test fun theSecondDialogOffersStopAndChoosingItAnswersTheRestAtOnce() {
        open("/p", "<title>start</title><script>alert('1'); var c=confirm('2'); var d=confirm('3'); var p=prompt('4','x'); alert('5'); document.title='done:'+c+':'+d+':'+p;</script>")
        waitFor("the first dialog") { pending() != null }
        assertEquals("no stop option on the first", false, dialogs.pending!!.canMute)
        answer(true)
        waitFor("the second dialog") { pending()?.message == "2" }
        assertTrue(dialogs.pending!!.canMute)
        // The owner confirms the second and stops the rest: the page's later dialogs get Cancel / OK at once.
        answer(true, mute = true)
        waitFor("the script finished without asking again") { title() == "done:true:false:null" }
        assertNull(pending())
    }

    @Test fun theCountStartsOverWithTheNextPage() {
        pages["/next"] = "<title>next</title>"
        open("/p", "<script>alert('x');</script>")
        waitFor("the alert") { pending() != null }
        assertEquals(1, dialogs.asked)
        // WebView holds a navigation while the page's script waits for its question; answered, the next page loads
        // (a page start is a new document: the count and the stop choice end).
        instrumentation.runOnMainSync { web!!.loadUrl("http://127.0.0.1:$port/next") }
        answer(true)
        waitFor("the next page loaded") { title() == "next" }
        assertEquals("the count started over", 0, dialogs.asked)
        assertNull(pending())
    }
}
