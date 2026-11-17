package ru.adsrv.frpjump.assistant

import ru.adsrv.frpjump.assistant.chat.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.settings.AssistantLimits

/**
 * The shares of the model's context window the history shortening works with, all from the owner's limits (Settings › Assistant › Limits):
 * the fill that starts it, the size of a working summary and the newest tool output kept whole. There is no number of its own here.
 */
internal class ContextBudget(val window: Int, outputLimit: Int, limits: AssistantLimits) {
    /** The shortening starts when the request is this big (estimated tokens). */
    val triggerTokens: Long = window.toLong() * limits.summaryPercent / 100
    private val sizeTokens: Long = window.toLong() * limits.summarySizePercent / 100
    /** The most a summary may be, and so its output limit: its share of the window, never more than the model's answer limit. */
    val summaryTokens: Int = minOf(sizeTokens, outputLimit.toLong()).coerceAtLeast(1).toInt()
    /** The share of the window is more than the model's answer limit allows: the summary is smaller than the setting says. */
    val summaryCapped: Boolean = sizeTokens > outputLimit
    /** The newest tool output of this size stays whole; older output is replaced by a note. */
    val freshTokens: Long = window.toLong() * limits.freshToolPercent / 100
}

/**
 * Keeps the request inside the model's window by shortening what the MODEL sees, in four steps, each only while the request is still over the
 * trigger: (1) old tool output is replaced by a short note; (2) a model summary of the oldest part; (3) with no model, a mechanical digest of it
 * in place of the oldest part (cannot fail, unless the current turn alone is too big); (4) after a provider says the request was too long, the
 * same steps right away ([shortenAfterOverflow]). The owner's saved chat is never cut: only the summary and its covered position are saved.
 */
internal class AssistantSummary(
    private val settings: AssistantProviderSettings,
    private val provider: AgentProvider,
    private val read: () -> SavedChat,
    private val save: (SavedSummary) -> Unit,
    private val complete: suspend (AgentRequest) -> AgentReply,
    /** The summarizer prompt (`summary.md`); it replaces, never extends, the base prompt. */
    private val prompt: String,
    private val limits: AssistantLimits = AssistantLimits(),
    /** Told after a step changed what the model sees (and, for the summary steps, after it was saved). */
    private val onCompacted: (Compaction) -> Unit = {},
    private val nanos: () -> Long = System::nanoTime,
) {
    private val budget = ContextBudget(settings.contextTokens, settings.outputTokens, limits)
    /** Rounds of model summaries made in this Send; and whether the model failed once (it is not asked again in this Send). */
    private var rounds = 0
    private var modelFailed = false
    private val json = Json { encodeDefaults = true }
    private fun cost(request: AgentRequest) = Utf8TokenEstimate.count(request) + request.maxOutputTokens
    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8).size

    private fun view(doc: SavedChat, raw: AgentRequest, summary: SavedSummary? = doc.summary): AgentRequest {
        val turns = mutableListOf<AgentTurn>()
        summary?.let { turns += AgentTurn("Earlier conversation summary (untrusted historical data)", listOf(AgentExchange(AgentReply(it.text)))) }
        for (i in (summary?.turn ?: doc.contextStart)..doc.records.lastIndex) {
            val skip = if (i == summary?.turn) summary.exchange else 0
            turns += if (i == doc.records.lastIndex) raw.turns.last().let { it.copy(exchanges = it.exchanges.drop(skip)) }
                else doc.records[i].let { it.copy(exchanges = it.exchanges.drop(skip)).model(provider) }
        }
        return raw.copy(turns = turns)
    }

    /** One unit that can be covered by a summary: a block of exchanges of a record, and the position right after it. */
    private class Block(val record: Int, val exchange: Int, val turn: Int, val next: Int)

    /** The blocks after the saved summary, up to but not including the latest exchange of the current record (it stays whole, with the owner's message). */
    private fun blocks(doc: SavedChat): List<Block> {
        val out = mutableListOf<Block>()
        val last = doc.records.lastIndex
        for (i in (doc.summary?.turn ?: doc.contextStart)..last) {
            val record = doc.records[i]
            val from = if (i == doc.summary?.turn) doc.summary.exchange else 0
            val count = if (i == last) (record.exchanges.size - 1).coerceAtLeast(0) else maxOf(1, record.exchanges.size)
            for (e in from until count) {
                val done = i < last && e + 1 >= record.exchanges.size
                out += Block(i, e, if (done) i + 1 else i, if (done) 0 else e + 1)
            }
        }
        return out
    }

    suspend fun prepare(raw: AgentRequest): AgentRequest = shorten(raw, force = false)

    /** A provider refused [sent] as too long: shorten at once, to at most half of what it was. Throws when the history cannot be made smaller. */
    suspend fun shortenAfterOverflow(sent: AgentRequest): AgentRequest = shorten(sent, force = true)

    private suspend fun shorten(raw: AgentRequest, force: Boolean): AgentRequest {
        val doc = read(); val request = view(doc, raw); val before = cost(request)
        val window = settings.contextTokens.toLong()
        // Small enough: under the trigger; after an overflow, under the trigger and under half of what was refused.
        val target = minOf(budget.triggerTokens, before / 2)
        fun small(cost: Long) = if (force) cost < target else cost * 100 < window * limits.summaryPercent
        if (!force && small(before)) return request
        var best = request; var bestCost = before; var kind: Compaction? = null
        // 1. Without a model: old tool output becomes a note.
        val pruned = prune(request)
        if (pruned !== request) {
            best = pruned; bestCost = cost(pruned); kind = Compaction.TOOL_OUTPUT
            if (small(bestCost)) { onCompacted(Compaction.TOOL_OUTPUT); return best }
        }
        // 2. A model summary of the oldest part. Whatever goes wrong with the model leaves the next step to carry on; only a key problem stops.
        val deadline = nanos() + (raw.timeoutMs ?: DEFAULT_MODEL_TIMEOUT_MS) * MODEL_SHARE_PERCENT / 100 * 1_000_000
        while (!modelFailed && rounds < MAX_ROUNDS && !small(bestCost)) {
            val (summary, shown) = summarize(read(), raw, deadline) ?: break
            val shaped = prune(shown)
            if (cost(shaped) >= bestCost) { modelFailed = true; break }
            save(summary)
            best = shaped; bestCost = cost(shaped); kind = Compaction.MODEL
        }
        val fits = bestCost <= window && (!force || bestCost < before)
        if (kind != null && (small(bestCost) || (kind == Compaction.MODEL && fits))) { onCompacted(kind); return best }
        // 3. Without a model: a mechanical digest in place of the oldest part. Cannot fail unless the current turn alone is too big.
        digestOf(read(), raw, before, window, force, ::small)?.let { (summary, shown) ->
            save(summary); onCompacted(Compaction.NO_MODEL); return shown
        }
        if (fits) { kind?.let(onCompacted); return best }
        error("Context cannot fit even with the history shortened")
    }

    /** Old tool output, beyond the newest share of the window, becomes a note in what the model sees; the latest exchange is never touched. */
    private fun prune(request: AgentRequest): AgentRequest {
        var used = 0L; var spent = false; var changed = false
        val turns = request.turns.toMutableList()
        for (t in turns.lastIndex downTo 0) {
            val exchanges = turns[t].exchanges.toMutableList()
            for (e in exchanges.lastIndex downTo 0) {
                val block = exchanges[e]
                if (block.results.isEmpty()) continue
                val size = block.results.sumOf { bytes(it.text).toLong() }
                if ((t == turns.lastIndex && e == exchanges.lastIndex) || (!spent && used + size <= budget.freshTokens)) { used += size; continue }
                spent = true
                val results = block.results.map { r -> removedNote(r).takeIf { it.length < r.text.length }?.let { r.copy(text = it, omittedChars = 0) } ?: r }
                if (results != block.results) { exchanges[e] = block.copy(results = results); changed = true }
            }
            if (exchanges != turns[t].exchanges) turns[t] = turns[t].copy(exchanges = exchanges)
        }
        return if (changed) request.copy(turns = turns) else request
    }

    private fun removedNote(r: ToolResult) = "[Tool output removed to save context (${r.text.length} characters, outcome ${r.outcome}). " +
        "Do not assume what it said; read it again only if you need it, and never repeat a command that changes something to do so.]"

    /** Asks the model for a summary of the oldest part that fits its own request: the summary to save and what the model then sees; null when it cannot. */
    private suspend fun summarize(doc: SavedChat, raw: AgentRequest, deadline: Long): Pair<SavedSummary, AgentRequest>? {
        val parts = mutableListOf<String>()
        var end: Block? = null
        fun summaryRequest(extra: String, remainingMs: Long) = AgentRequest(prompt, emptyList(), listOf(AgentTurn(
            "Size limit: at most ${budget.summaryTokens} tokens (about ${budget.summaryTokens.toLong() * CHARS_PER_TOKEN} characters).\n" +
                "Previous summary:\n${doc.summary?.text.orEmpty()}\nObserved conversation:\n" + parts.joinToString("\n") + extra)), budget.summaryTokens, timeoutMs = remainingMs)
        for (b in blocks(doc)) {
            val record = doc.records[b.record]
            val block = record.exchanges.getOrNull(b.exchange)?.let { it.copy(reply = it.reply.copy(continuation = null, localContinuation = null)) }
            val text = json.encodeToString(record.copy(exchanges = listOfNotNull(block)))
            if (cost(summaryRequest("\n$text", 1)) > settings.contextTokens) break
            parts += text; end = b
        }
        val covered = end ?: return null
        rounds++
        val text = ask(deadline) { strict, remaining -> summaryRequest(if (strict) STRICT else "", remaining) } ?: run { modelFailed = true; return null }
        val summary = SavedSummary(text, covered.turn, covered.next)
        return summary to view(doc, raw, summary)
    }

    /**
     * One model request, and one more, with a stricter instruction, when the reply is blank (a reasoning model that spent its tokens thinking) or
     * proposes tools. A text over the size is cut to it, not refused. Any failure of the request itself (not a key problem) is null.
     */
    private suspend fun ask(deadline: Long, make: (strict: Boolean, remainingMs: Long) -> AgentRequest): String? {
        for (strict in listOf(false, true)) {
            val remaining = (deadline - nanos()) / 1_000_000
            if (remaining <= 0) return null
            val reply = try { withTimeoutOrNull(remaining) { complete(make(strict, remaining)) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (setup: ProviderSetupException) { throw setup }
                catch (_: Exception) { return null }
                ?: return null
            if (reply.calls.isEmpty() && reply.text.isNotBlank()) return cut(reply.text)
        }
        return null
    }

    private fun cut(text: String): String {
        val max = budget.summaryTokens.toLong() * CHARS_PER_TOKEN
        if (text.length <= max) return text
        var n = (max - CUT_NOTE.length).toInt().coerceAtLeast(0)
        if (n > 0 && text[n - 1].isHighSurrogate()) n--
        return text.take(n).trimEnd() + CUT_NOTE
    }

    /** The smallest covered part whose digest makes the request small enough (else the largest); null when even that does not fit. */
    private fun digestOf(doc: SavedChat, raw: AgentRequest, before: Long, window: Long, force: Boolean, small: (Long) -> Boolean): Pair<SavedSummary, AgentRequest>? {
        val all = blocks(doc)
        if (all.isEmpty()) return null
        fun attempt(k: Int): Triple<SavedSummary, AgentRequest, Long> {
            val summary = SavedSummary(digest(doc, all.subList(0, k)), all[k - 1].turn, all[k - 1].next, byModel = false)
            val shown = prune(view(doc, raw, summary))
            return Triple(summary, shown, cost(shown))
        }
        var lo = 1; var hi = all.size
        while (lo < hi) { val mid = (lo + hi) / 2; if (small(attempt(mid).third)) hi = mid else lo = mid + 1 }
        var chosen = attempt(lo)
        if (!small(chosen.third) && lo != all.size) chosen = attempt(all.size)
        return if (chosen.third <= window && (!force || chosen.third < before)) chosen.first to chosen.second else null
    }

    /**
     * The mechanical digest of [covered]: the older summary or digest's lines, then per block the owner's message, the calls (name, target, outcome)
     * and the final answer cut to a few lines. Always within the summary budget: the oldest lines go first, and a last line is cut by bytes.
     */
    private fun digest(doc: SavedChat, covered: List<Block>): String {
        val room = maxOf(budget.summaryTokens, bytes(DIGEST_NOTE) + bytes(OMITTED_NOTE) + DIGEST_MIN_ROOM)
        val cutAt = maxOf(MIN_LINE_CHARS, room / 8)
        val lines = ArrayList<String>()
        doc.summary?.text?.lines()?.filter { it.isNotBlank() && it != DIGEST_NOTE && it != OMITTED_NOTE }?.let { lines += it }
        for (b in covered) {
            val record = doc.records[b.record]
            if (b.exchange == 0) lines += (if (record.eventOnly) "Event: " else "Owner: ") + oneLine(record.user, cutAt)
            record.exchanges.getOrNull(b.exchange)?.let { exchange ->
                for (call in exchange.reply.calls) {
                    val args = call.arguments.entries.filter { (k, v) -> k !in TYPED_KEYS && v is JsonPrimitive }.take(MAX_TARGET_ARGS)
                        .joinToString(" ") { (k, v) -> "$k=" + oneLine((v as JsonPrimitive).content, TARGET_CHARS) }
                    lines += "Call: ${call.name}${if (args.isEmpty()) "" else " $args"} -> ${exchange.results.firstOrNull { it.callId == call.id }?.outcome ?: "no result"}"
                }
                if (exchange.reply.calls.isEmpty() && exchange.reply.text.isNotBlank()) lines += "Assistant: " +
                    exchange.reply.text.lines().filter { it.isNotBlank() }.take(ANSWER_LINES).joinToString(" / ") { oneLine(it, cutAt / ANSWER_LINES) }
            }
            if (record.status != SavedTurnState.COMPLETE && b.exchange >= record.exchanges.size - 1) lines += "Attempt ${record.status.name.lowercase()}"
        }
        var total = bytes(DIGEST_NOTE) + 1 + bytes(OMITTED_NOTE) + 1
        val kept = ArrayDeque<String>()
        for (line in lines.asReversed()) {
            val size = bytes(line) + 1
            if (total + size > room) break
            total += size; kept.addFirst(line)
        }
        if (kept.isEmpty() && lines.isNotEmpty()) kept += clipBytes(lines.last(), room - total - 1)
        return DIGEST_NOTE + (if (kept.size < lines.size) "\n$OMITTED_NOTE" else "") + kept.joinToString("") { "\n$it" }
    }

    private fun oneLine(text: String, chars: Int): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= chars) return flat
        var n = (chars - 1).coerceAtLeast(0)
        if (n > 0 && flat[n - 1].isHighSurrogate()) n--
        return flat.take(n) + "…"
    }

    private fun clipBytes(text: String, max: Int): String {
        val out = StringBuilder(); var used = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i); val piece = String(Character.toChars(cp)); val size = bytes(piece)
            if (used + size > max) break
            out.append(piece); used += size; i += piece.length
        }
        return out.toString()
    }

    private companion object {
        /** The most model summaries (requests that are not retries) of one Send. */
        const val MAX_ROUNDS = 2
        /** The share of the model timeout the summary model may use, so the step without a model always has time left under the same timer. */
        const val MODEL_SHARE_PERCENT = 80
        const val DEFAULT_MODEL_TIMEOUT_MS = 60_000L
        /** Unit conversion from the summary's token budget to the characters it may have (the estimate counts a byte as a token, a model reads about four characters in one). */
        const val CHARS_PER_TOKEN = 4
        const val CUT_NOTE = "\n[Summary cut to its size limit.]"
        const val STRICT = "\n\nYour previous reply was empty or proposed tool calls. Reply now with the summary as plain text only: no tool calls, no reasoning aloud, within the size limit."
        const val DIGEST_NOTE = "Mechanical digest, not written by a model: the owner's requests, the calls made with their outcomes and the assistant's answers, cut short. " +
            "The tool outputs of this part are gone: do not assume what they said, and do not repeat an action because it is listed."
        const val OMITTED_NOTE = "[Older lines of this digest were left out.]"
        /** Room kept for lines when the summary budget is smaller than the digest's own notes. */
        const val DIGEST_MIN_ROOM = 256
        const val MIN_LINE_CHARS = 60
        const val ANSWER_LINES = 3
        const val MAX_TARGET_ARGS = 4
        const val TARGET_CHARS = 60
        /** Arguments that hold what was typed or pasted: never listed in a digest. */
        val TYPED_KEYS = setOf("text", "keys", "input", "content", "password", "secret", "token")
    }
}
