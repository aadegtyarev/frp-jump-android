package ru.adsrv.frpjump.assistant.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.adsrv.frpjump.agent.ToolCall
import ru.adsrv.frpjump.assistant.AssistantToolId

/** Consecutive receipts of one tool, shown as one row with a counter. */
internal data class ReceiptGroup(val tool: String, val items: List<ChatToolReceipt>)

/** Consecutive calls of the same tool become one group; a different tool in between starts a new one. Order is kept. */
internal fun groupReceipts(receipts: List<ChatToolReceipt>): List<ReceiptGroup> {
    val groups = ArrayList<ReceiptGroup>()
    for (receipt in receipts) {
        val last = groups.lastOrNull()
        if (last != null && last.tool == receipt.call.name) groups[groups.lastIndex] = last.copy(items = last.items + receipt)
        else groups += ReceiptGroup(receipt.call.name, listOf(receipt))
    }
    return groups
}

internal const val RECEIPT_DETAIL_CHARS = 40

/**
 * A short, one-line, display-only note on what the model asked a tool for, or null when the call has nothing to show (an unknown tool, or
 * an older saved turn whose arguments are empty). The text is the model's own, so it is cleaned by [receiptText]; it is never made clickable.
 */
internal fun receiptDetail(call: ToolCall): String? {
    val a = call.arguments
    val raw = when (call.name) {
        AssistantToolId.SSH_COMMAND.wire -> a.text("command")
        AssistantToolId.WEB_SEARCH.wire -> a.text("query")
        AssistantToolId.READ_PAGE.wire -> a.text("url")?.let(::hostAndPath)
        AssistantToolId.INSPECT_IMAGE.wire -> a.text("url")?.let(::imageName)
        AssistantToolId.PROFILE_INFORMATION.wire -> listOfNotNull(a.text("query")?.takeIf { it.isNotBlank() }, a.text("profile_id")?.takeIf { it.isNotBlank() })
            .joinToString(" · ")
        else -> null
    }
    return raw?.let { receiptText(it, RECEIPT_DETAIL_CHARS) }?.takeIf { it.isNotEmpty() }
}

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun uriOf(url: String): java.net.URI? = runCatching { java.net.URI(url.trim()) }.getOrNull()

/** Host and path without user info, query and fragment. */
private fun hostAndPath(url: String): String {
    val uri = uriOf(url)
    val host = uri?.host ?: return url
    return host + uri.rawPath.orEmpty().let { if (it == "/") "" else it }
}

/** The file name of the image address, or its host when there is none. */
private fun imageName(url: String): String {
    val uri = uriOf(url) ?: return url
    return uri.rawPath.orEmpty().substringAfterLast('/').takeIf { it.isNotEmpty() } ?: uri.host ?: url
}

/**
 * Model text for one line of display: control, format (bidi among them), invisible, separator-like and unassigned code points are removed or
 * turned into a single space, runs of blanks are collapsed, and the result is cut at [max] code points (never inside a surrogate pair) with an
 * ellipsis. Display only; the call itself is unchanged.
 */
internal fun receiptText(raw: String, max: Int): String {
    val out = StringBuilder()
    var pendingSpace = false
    var i = 0
    while (i < raw.length) {
        val cp = raw.codePointAt(i)
        i += Character.charCount(cp)
        val type = Character.getType(cp).toByte()
        when {
            cp == ' '.code || cp == '\n'.code || cp == '\r'.code || cp == '\t'.code || type == Character.SPACE_SEPARATOR ||
                type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR || cp == 0x85 -> if (out.isNotEmpty()) pendingSpace = true
            type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE || type == Character.PRIVATE_USE ||
                type == Character.UNASSIGNED || isDefaultIgnorable(cp) || cp == 0x2800 -> Unit
            else -> { if (pendingSpace) { out.append(' '); pendingSpace = false }; out.appendCodePoint(cp) }
        }
    }
    val text = out.toString()
    if (text.codePointCount(0, text.length) <= max) return text
    return text.substring(0, text.offsetByCodePoints(0, max)).trimEnd() + "…"
}
