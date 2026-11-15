package ru.adsrv.frpjump.assistant.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.adsrv.frpjump.agent.ToolCall
import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.assistant.AssistantRequestTool
import ru.adsrv.frpjump.assistant.AssistantToolId

/** Consecutive receipts of one tool, shown as one row with a counter. */
internal data class ReceiptGroup(val tool: String, val items: List<ChatToolReceipt>)

/**
 * Consecutive calls of the same tool become one group; a different tool in between starts a new one. Order is kept. With [sameDetail] a
 * call joins the group only when its detail ([receiptKey]) is equal too, so calls told apart by their parameters stay on their own lines.
 */
internal fun groupReceipts(receipts: List<ChatToolReceipt>, sameDetail: Boolean = false): List<ReceiptGroup> {
    val groups = ArrayList<ReceiptGroup>()
    for (receipt in receipts) {
        val last = groups.lastOrNull()
        if (last != null && last.tool == receipt.call.name && (!sameDetail || receiptKey(last.items.last().call) == receiptKey(receipt.call)))
            groups[groups.lastIndex] = last.copy(items = last.items + receipt)
        else groups += ReceiptGroup(receipt.call.name, listOf(receipt))
    }
    return groups
}

/** What tells two calls of one tool apart: the arguments the detail line is made of. */
private fun receiptKey(call: ToolCall): String = when (call.name) {
    AssistantToolId.PROFILE_INFORMATION.wire -> listOf("query", "profile_id", "offset").joinToString("\u0000") { call.arguments.text(it) ?: call.arguments.number(it).orEmpty() }
    else -> receiptDetail(call).orEmpty()
}

/**
 * What the detail lines need from the screen, all display text: the words of a profile-information detail (the chat's language), the human
 * title of a tool by its wire name, and the names the received facts give to a profile and to a connection ("profile", "connection" ids).
 * A test passes its own; the defaults know nothing.
 */
internal class ReceiptWords(
    val all: String, val from: (Int) -> String, val toolTitle: (String) -> String? = { null },
    val profileName: (String) -> String? = { null }, val connectionName: (String, String) -> String? = { _, _ -> null },
    /** What this turn's `terminal_tabs` results name a tab (by tab_id); null when none does. */
    val tabLabel: (Int) -> TabLabel? = { null },
    /** "profile · user@device #n" in the chat's language, and the plain "tab <id>" for a tab no result names. */
    val tabNamed: (String, String, String, Int) -> String = { profile, user, device, number -> "$profile · $user@$device #$number" },
    val tabPlain: (Int) -> String = { "tab $it" },
)

/** A tab as a `terminal_tabs` result names it: display text only. */
internal data class TabLabel(val profile: String, val user: String, val device: String, val number: Int)

/**
 * The tabs named by the successful `terminal_tabs` results among [receipts], by tab_id (the later result wins). Reads the result's own
 * line format (see `tabsText`); a line it does not understand names nothing.
 */
internal fun tabLabels(receipts: List<ChatToolReceipt>): Map<Int, TabLabel> {
    val labels = HashMap<Int, TabLabel>()
    for (receipt in receipts) {
        val result = receipt.result?.takeIf { it.name == AssistantToolId.TERMINAL_TABS.wire && it.outcome == ToolOutcome.SUCCESS } ?: continue
        for (line in result.text.lineSequence()) {
            if (!line.startsWith("tab_id: ")) continue
            // The first occurrence of a key wins: whatever follows the fixed fields can never overwrite them.
            val fields = HashMap<String, String>()
            for (part in line.split(" | ")) fields.putIfAbsent(part.substringBefore(": "), part.substringAfter(": ", ""))
            val id = fields["tab_id"]?.toIntOrNull() ?: continue
            val profile = fields["profile"]?.substringBeforeLast(" (profile_id: ") ?: continue
            val user = fields["user"] ?: continue
            val device = fields["device"] ?: continue
            val number = fields["tab"]?.toIntOrNull() ?: continue
            labels[id] = TabLabel(profile, user, device, number)
        }
    }
    return labels
}

internal const val RECEIPT_DETAIL_CHARS = 40

/**
 * A short, one-line, display-only note on what the model asked a tool for, or null when the call has nothing to show (an unknown tool, or
 * an older saved turn whose arguments are empty). The text is the model's own, so it is cleaned by [receiptText]; it is never made clickable.
 */
internal fun receiptDetail(call: ToolCall, words: ReceiptWords = ReceiptWords("all", { "from record $it" })): String? {
    val a = call.arguments
    if (call.name == AssistantToolId.PROFILE_INFORMATION.wire) return profileInformationDetail(a, words)
    if (call.name == AssistantToolId.TERMINAL_READ.wire) return terminalReadDetail(a, words)
    val raw = when (call.name) {
        // The target's name only where the received facts name it (never its ids), then the command; the cut keeps the command's start.
        AssistantToolId.SSH_COMMAND.wire -> a.text("command")?.let { command ->
            val target = words.connectionName(a.text("profile_id").orEmpty(), a.text("connection_id").orEmpty())?.let { receiptText(it, RECEIPT_PART_CHARS) }?.takeIf { it.isNotEmpty() }
            if (target == null) command else "$target · ${receiptText(command, RECEIPT_DETAIL_CHARS)}"
        }
        AssistantRequestTool.WIRE -> a.text("name")?.let(words.toolTitle)
        AssistantToolId.WEB_SEARCH.wire -> a.text("query")
        AssistantToolId.READ_PAGE.wire -> a.text("url")?.let(::hostAndPath)
        AssistantToolId.INSPECT_IMAGE.wire -> a.text("url")?.let(::imageName)
        else -> null
    }
    return raw?.let { receiptText(it, RECEIPT_DETAIL_CHARS) }?.takeIf { it.isNotEmpty() }
}

/**
 * «query» · the profile's name (else the first 8 characters of its id) · "from record N" past the first page; "all" when there is neither
 * a query nor a profile. Every part is cut on its own, so that the page part is never the one lost.
 */
private fun profileInformationDetail(a: JsonObject, words: ReceiptWords): String {
    val query = a.text("query")?.let { receiptText(it, RECEIPT_PART_CHARS) }?.takeIf { it.isNotEmpty() }
    val profile = a.text("profile_id")?.takeIf { it.isNotBlank() }?.let { id -> (words.profileName(id) ?: id.trim().take(8)).let { receiptText(it, RECEIPT_PART_CHARS) } }
    val offset = a.number("offset")?.toIntOrNull()?.takeIf { it > 0 }
    return listOfNotNull(query?.let { "«$it»" }, profile).ifEmpty { listOf(words.all) }.plus(listOfNotNull(offset?.let { words.from(it + 1) })).joinToString(" · ")
}

/**
 * "profile · user@device #n" when this turn's `terminal_tabs` result names the tab, else "tab <id>"; null when the call has no usable
 * tab_id. Every part is cut on its own, so that the number is never the part lost.
 */
private fun terminalReadDetail(a: JsonObject, words: ReceiptWords): String? {
    val id = a.number("tab_id")?.toIntOrNull()?.takeIf { it >= 1 } ?: return null
    val label = words.tabLabel(id) ?: return words.tabPlain(id)
    fun part(raw: String) = receiptText(raw, RECEIPT_PART_CHARS)
    return words.tabNamed(part(label.profile), part(label.user), part(label.device), label.number)
}

private const val RECEIPT_PART_CHARS = 24

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonObject.number(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.content

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
