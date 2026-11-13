package ru.adsrv.frpjump.ui.web

import android.webkit.JsPromptResult
import android.webkit.JsResult
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.net.IDN
import java.net.URI
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ui.AppAlertDialog

/** Where a page's `alert`, `confirm`, `prompt` (or leave-page question) is answered; fakeable in tests. */
interface JsAnswer {
    fun confirm(text: String? = null)
    fun cancel()
}

enum class JsKind { ALERT, CONFIRM, PROMPT, BEFORE_UNLOAD }

/** [origin]: [jsOrigin] of the asking frame; null when it has none (shown as "Page"). */
class JsRequest(val kind: JsKind, val message: String, val defaultValue: String?, val origin: String?, val answer: JsAnswer)

/**
 * The origin of the frame that asks, as the dialog's title: `scheme://host[:port]`, lower case, an
 * internationalised host in its ASCII (punycode) form so it cannot look like another one. Null for a URL
 * with no host (`about:`, `data:`, `file:`, `null`, empty): the dialog then says "Page". Never the page's
 * own title, so a page cannot pose as one of the app's dialogs.
 */
fun jsOrigin(url: String?): String? {
    val text = url?.trim().orEmpty()
    if (text.isEmpty()) return null
    val uri = try { URI(text) } catch (_: Exception) { return fallbackOrigin(text) }
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host ?: return fallbackOrigin(text)
    return format(scheme, host, uri.port)
}

private fun fallbackOrigin(text: String): String? {
    // URI refuses some valid-looking hosts (a non-ASCII one); read the authority by hand.
    val m = Regex("""^([A-Za-z][A-Za-z0-9+.-]*)://(?:[^/?#@]*@)?(\[[0-9A-Fa-f:.]+]|[^/?#:]+)(?::(\d{1,5}))?(?:[/?#].*)?$""").find(text) ?: return null
    val port = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: -1
    return format(m.groupValues[1].lowercase(), m.groupValues[2], port)
}

private fun format(scheme: String, rawHost: String, port: Int): String? {
    if (rawHost.isEmpty()) return null
    val host = if (rawHost.startsWith("[")) rawHost.lowercase() else try { IDN.toASCII(rawHost, IDN.ALLOW_UNASSIGNED).lowercase() } catch (_: Exception) { return null }
    return "$scheme://$host" + if (port >= 0) ":$port" else ""
}

/** The page's text, plain, and not so long that it hides the buttons or the origin. */
fun jsMessage(text: String?): String = capped(text.orEmpty(), MAX_TEXT, ellipsis = true)

/** A prompt's default value: a page may send any length, so it is cut like the message (no "…" added to a value). */
fun jsPromptDefault(text: String?): String = capped(text.orEmpty(), MAX_TEXT, ellipsis = false)

private const val MAX_TEXT = 1000

/** At most [max] UTF-16 units, never ending inside a surrogate pair. */
private fun capped(text: String, max: Int, ellipsis: Boolean): String {
    if (text.length <= max) return text
    var end = max
    if (Character.isHighSurrogate(text[end - 1])) end--
    return text.substring(0, end) + if (ellipsis) "…" else ""
}

/**
 * The one JavaScript dialog a page waits for (its script is blocked until it is answered).
 * It is shown by [JsDialogHost], an app dialog that stays out while the app is locked, so a call made
 * while locked waits and its dialog appears after unlock. When the page or the screen goes away
 * first, [cancelPending] answers it the way WebView does (cancel).
 *
 * Spam stop, as browsers do: from the second dialog of one document on, the owner may choose not to see
 * more of them ([JsPending.canMute]); after that the page's dialogs are answered at once (alert: OK; confirm
 * and prompt: cancel; leave-page question: leave) until the page navigates or reloads ([navigated]).
 */
class JsDialogs {
    var pending by mutableStateOf<JsPending?>(null)
        private set

    /** Dialogs this document has asked for. */
    var asked = 0
        private set

    var muted = false
        private set

    fun show(request: JsRequest) {
        if (muted) {
            autoAnswer(request)
            return
        }
        // A page cannot ask twice at once; a leftover is from a page that is gone.
        cancelPending()
        asked++
        pending = JsPending(request, canMute = asked >= 2)
    }

    /**
     * The owner's answer to [request], the one on screen: [ok] confirms (with [text] for a prompt), otherwise
     * cancels; [mute]: no more dialogs from this page. An answer to a request that is no longer the shown one is ignored.
     */
    fun answer(request: JsRequest, ok: Boolean, text: String? = null, mute: Boolean = false) {
        val current = pending?.request ?: return
        if (current !== request) return
        pending = null
        if (mute) muted = true
        if (ok) current.answer.confirm(text) else current.answer.cancel()
    }

    fun cancelPending() {
        val current = pending?.request ?: return
        pending = null
        current.answer.cancel()
    }

    /** A new document (navigation or reload): what was waiting is cancelled and the count starts over. */
    fun navigated() {
        cancelPending()
        asked = 0
        muted = false
    }

    private fun autoAnswer(request: JsRequest) {
        when (request.kind) {
            JsKind.ALERT, JsKind.BEFORE_UNLOAD -> request.answer.confirm(null)
            JsKind.CONFIRM, JsKind.PROMPT -> request.answer.cancel()
        }
    }
}

class JsPending(val request: JsRequest, val canMute: Boolean)

/** A main-frame load starting is a new document: the count, the mute and a waiting question end. */
internal fun JsDialogs.onPage(event: PageEvent) {
    if (event == PageEvent.STARTED) navigated()
}

fun JsResult.asAnswer(): JsAnswer {
    val result = this
    return object : JsAnswer {
        override fun confirm(text: String?) {
            if (text != null && result is JsPromptResult) result.confirm(text) else result.confirm()
        }

        override fun cancel() = result.cancel()
    }
}

@Composable
fun JsDialogHost(dialogs: JsDialogs) {
    val pending = dialogs.pending ?: return
    val request = pending.request
    // Not saveable: the page controls the default's length, and a saved instance state that large crashes the app.
    // A recreation cancels the request anyway.
    var text by remember(request) { mutableStateOf(jsPromptDefault(request.defaultValue)) }
    var mute by remember(request) { mutableStateOf(false) }
    val leaving = request.kind == JsKind.BEFORE_UNLOAD
    AppAlertDialog(
        onDismissRequest = { dialogs.answer(request, false, mute = mute) },
        // The page's origin, not a title the page sets: this window is the page's, not the app's.
        title = { Text(request.origin ?: stringResource(R.string.js_page)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (leaving) stringResource(R.string.js_leave_text) else jsMessage(request.message))
                if (request.kind == JsKind.PROMPT) {
                    OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                if (pending.canMute) {
                    Row(
                        Modifier.fillMaxWidth().toggleable(value = mute, role = Role.Checkbox, onValueChange = { mute = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = mute, onCheckedChange = null)
                        Text(stringResource(R.string.js_mute), Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { dialogs.answer(request, true, text.takeIf { request.kind == JsKind.PROMPT }, mute) }) {
                Text(stringResource(if (leaving) R.string.js_leave else R.string.js_ok))
            }
        },
        dismissButton = if (request.kind == JsKind.ALERT) null else ({
            TextButton(onClick = { dialogs.answer(request, false, mute = mute) }) {
                Text(stringResource(if (leaving) R.string.js_stay else R.string.cancel))
            }
        }),
    )
}
