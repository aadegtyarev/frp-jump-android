package ru.adsrv.frpjump.assistant.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.adsrv.frpjump.agent.ToolCall
import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.assistant.AssistantRequestTool
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.assistant.TerminalKey
import ru.adsrv.frpjump.ssh.TAB_NAME_MAX
import ru.adsrv.frpjump.ssh.TabNameCheck
import ru.adsrv.frpjump.ssh.checkTabName
import ru.adsrv.frpjump.ssh.isolateName

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
    // Typing into one tab twice is two lines: what was typed is part of what tells the calls apart.
    AssistantToolId.TERMINAL_INPUT.wire -> listOf(receiptDetail(call).orEmpty(), call.arguments.text("text").orEmpty(), typedKeys(call.arguments).joinToString(",")).joinToString("\u0000")
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
    /** "profile · user@device #n · name": a tab that has a name, which is only ever shown after its identity, bidi-isolated and in quotes. */
    val tabNamedAs: (String, String, String, String, Int) -> String = { name, profile, user, device, number -> "$profile · $user@$device #$number · \"$name\"" },
    /** [name] between its marks, in the chat's language. */
    val quoted: (String) -> String = { name -> "\"$name\"" },
    /** The tunnel's name in the reply of the call with this id, when it has one. */
    val tunnelName: (String) -> String? = { null },
    /** "user@device #n · name": the line of a rename call. */
    val tabRenamed: (String, String, String, Int) -> String = { name, user, device, number -> "$user@$device #$number · \"$name\"" },
)

/** A tab as a `terminal_tabs` result names it: display text only. */
internal data class TabLabel(val profile: String, val user: String, val device: String, val number: Int, val name: String? = null)

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
            labels[id] = TabLabel(profile, user, device, number, fields["name"]?.takeIf { it.isNotEmpty() })
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
    if (call.name == AssistantToolId.TERMINAL_READ.wire || call.name == AssistantToolId.TERMINAL_INPUT.wire ||
        call.name == AssistantToolId.TERMINAL_SHOW.wire || call.name == AssistantToolId.TERMINAL_CLOSE.wire) return terminalReadDetail(a, words)
    // The call line of an open ends with where the session is, "user@device" (from its reply), isolated; before the reply, the saved connection's name.
    if (call.name == AssistantToolId.TERMINAL_OPEN.wire) return words.tunnelName(call.id)?.let { receiptText(it, RECEIPT_DETAIL_CHARS) }?.takeIf { it.isNotEmpty() }?.let { isolateName(it) } ?: tunnelDetail(call, a, words)
    if (call.name == AssistantToolId.TERMINAL_RENAME.wire) return terminalRenameDetail(a, words)
    if (call.name == AssistantToolId.START_TUNNEL.wire || call.name == AssistantToolId.STOP_TUNNEL.wire) return tunnelDetail(call, a, words)
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
    // A name is shown with the tab's user and device, never alone: the name is the owner's or the model's text, not proof of what the tab is.
    val name = label.name?.let { receiptText(it, TAB_NAME_MAX) }?.takeIf { it.isNotEmpty() }
        ?: return words.tabNamed(part(label.profile), part(label.user), part(label.device), label.number)
    return words.tabNamedAs(isolateName(name), part(label.profile), part(label.user), part(label.device), label.number)
}

/**
 * The tunnel's name between its marks, for the call line of `start_tunnel` and `stop_tunnel`: from the received `profile_information` facts
 * when they name the tunnel, else from this call's own result ([tunnelNames]); none before either is known (the line is then the title alone).
 * Tunnel names are the owner's own text: plain text, bidi-isolated, shown whole (up to [RECEIPT_DETAIL_CHARS]) with no profile in front.
 */
private fun tunnelDetail(call: ToolCall, a: JsonObject, words: ReceiptWords): String? {
    val profileId = a.text("profile_id")?.takeIf { it.isNotBlank() } ?: return null
    val name = a.text("connection_id")?.let { words.connectionName(profileId, it) } ?: words.tunnelName(call.id)
    return name?.let { receiptText(it, RECEIPT_DETAIL_CHARS) }?.takeIf { it.isNotEmpty() }?.let { words.quoted(isolateName(it)) }
}

/**
 * The tunnel's name in each finished `start_tunnel` / `stop_tunnel` call's reply (`Tunnel <name> is on. …`), by call id: where the facts of
 * `profile_information` did not name it. Display only; the replies are the app's own fixed texts.
 */
internal fun tunnelNames(receipts: List<ChatToolReceipt>): Map<String, String> {
    val out = HashMap<String, String>()
    for (receipt in receipts) {
        val call = receipt.call
        if (call.name != AssistantToolId.START_TUNNEL.wire && call.name != AssistantToolId.STOP_TUNNEL.wire && call.name != AssistantToolId.TERMINAL_OPEN.wire) continue
        val text = receipt.result?.text ?: continue
        // A session opened says where: "user@device" ends its call line.
        if (call.name == AssistantToolId.TERMINAL_OPEN.wire) { OPEN_REPLY.find(text)?.groupValues?.get(1)?.let { out[call.id] = it }; continue }
        TUNNEL_REPLY.find(text)?.groupValues?.get(1)?.let { out[call.id] = it }
    }
    return out
}

private val OPEN_REPLY = Regex("^Session opened: tab_id \\d+ \\((.+?)\\)\\.")

private val TUNNEL_REPLY = Regex("^Tunnel (.+?) (?:is on[.,]|was already on\\.|is off\\.|was not on\\.|is being stopped\\.|did not start\\.)")

/**
 * "user@device #n · name" for a rename call, the tab's user and device as this turn's `terminal_tabs` result names them; "profile · user@device #n"
 * when the call brings the automatic name back; "tab <id>" alone when no result names the tab (the name is then not shown: it would stand alone).
 */
private fun terminalRenameDetail(a: JsonObject, words: ReceiptWords): String? {
    val id = a.number("tab_id")?.toIntOrNull()?.takeIf { it >= 1 } ?: return null
    val label = words.tabLabel(id) ?: return words.tabPlain(id)
    fun part(raw: String) = receiptText(raw, RECEIPT_PART_CHARS)
    val name = newTabName(a)?.let { receiptText(it, TAB_NAME_MAX) }?.takeIf { it.isNotEmpty() }
        ?: return words.tabNamed(part(label.profile), part(label.user), part(label.device), label.number)
    return words.tabRenamed(isolateName(name), part(label.user), part(label.device), label.number)
}

/** The name a `terminal_rename` call asks for as the tool would take it (trimmed, valid), null for the automatic one or an invalid one. */
private fun newTabName(a: JsonObject): String? = (a.text("name")?.let(::checkTabName) as? TabNameCheck.Valid)?.name

/** One successful `terminal_rename`: the tab as this turn's tab list names it, its name before (null: automatic) and after. */
internal class TabRename(val label: TabLabel, val before: String?, val after: String?)

/**
 * The successful `terminal_rename` calls among [receipts] (in order, by call id) whose tab a `terminal_tabs` result names: what the tab was
 * called before (that result's name, or the name an earlier rename of this turn gave it) and after. A call whose tab no result names has none.
 */
internal fun tabRenames(receipts: List<ChatToolReceipt>): Map<String, TabRename> {
    val labels = tabLabels(receipts)
    val current = HashMap<Int, String?>()
    val out = HashMap<String, TabRename>()
    for (receipt in receipts) {
        val call = receipt.call
        if (call.name != AssistantToolId.TERMINAL_RENAME.wire || receipt.result?.outcome != ToolOutcome.SUCCESS) continue
        val id = call.arguments.number("tab_id")?.toIntOrNull() ?: continue
        val label = labels[id] ?: continue
        val before = if (id in current) current[id] else label.name
        val after = newTabName(call.arguments)
        current[id] = after
        out[call.id] = TabRename(label, before, after)
    }
    return out
}

/**
 * The body of one expanded call, display only: for `ssh_command` the full command as `$ <command>` (hidden characters shown as visible
 * escapes; the label above cuts it); for `terminal_input` the typed text the same way (every Enter marked) and a line with the keys
 * ([keysLine] words it in the chat's language); then [outcome] when there is something to say (null for a success), then the result text (not a successful input's: it only gives the model a cursor).
 * No wire name and no raw arguments: the label says what was called.
 */
internal fun receiptBody(call: ToolCall, outcome: String?, resultText: String, keysLine: (List<String>) -> String = { "Keys: " + it.joinToString(", ") },
    /** For a successful `terminal_rename`: "before → after", worded by the caller; shown ahead of the result. */
    renameLine: String? = null): String {
    val command = if (call.name == AssistantToolId.SSH_COMMAND.wire) call.arguments.text("command")?.let { "$ " + displayCommand(it) } else null
    val typed = if (call.name == AssistantToolId.TERMINAL_INPUT.wire) call.arguments.text("text")?.takeIf { it.isNotEmpty() }?.let { displayTyped(it).trimEnd('\n') } else null
    val keys = if (call.name == AssistantToolId.TERMINAL_INPUT.wire) typedKeys(call.arguments).takeIf { it.isNotEmpty() }?.let(keysLine) else null
    // What a successful input returned is the model's cursor, nothing for the owner: the call shows the input only. A refusal or failure shows its text.
    val inputDone = call.name == AssistantToolId.TERMINAL_INPUT.wire && outcome == null
    return listOfNotNull(command, typed, keys, renameLine, outcome, resultText.takeIf { it.isNotEmpty() && !inputDone }).joinToString("\n")
}

/** The names of the keys a `terminal_input` call asked for, in order; an item that is no known key is left out. Display only. */
private fun typedKeys(a: JsonObject): List<String> =
    (a["keys"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { item ->
        (item as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(TerminalKey::fromWire)?.label
    }.orEmpty()

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
