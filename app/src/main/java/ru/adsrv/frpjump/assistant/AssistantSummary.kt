package ru.adsrv.frpjump.assistant

import ru.adsrv.frpjump.assistant.chat.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.settings.AssistantLimits
import ru.adsrv.frpjump.settings.WindowSplit

/**
 * The shares of the model's context window the history shortening works with, all from the owner's window split (Settings › LLM assistant › Assistant
 * provider › «Окно модели»): the history that starts it, the model's reply (the request's output limit) and the size of a working summary.
 * There is no number of its own here.
 */
internal class ContextBudget(val window: Int, limits: AssistantLimits) {
    val split: WindowSplit = limits.split
    /** The request's output limit, and the reserve in the estimated request: the reply share of the window. */
    val replyTokens: Int = split.replyTokens(window)
    /** The shortening starts when the request (history and the reply reserve) reaches this size (estimated tokens), i.e. when the history reaches its share. */
    val triggerTokens: Long = window.toLong() * split.compactAt / 100
    /**
     * The most a summary may be: its share of the window, in the estimate's own units (a UTF-8 byte counts as a token, as in the trigger and the
     * digest). It is also the summary request's own output limit; the reply share is only the fallback when that is refused.
     */
    val summaryTokens: Int = split.summaryTokens(window)
    /** Old tool output is replaced by notes until the request is down to this size: the history at its share less a summary, and the reply reserve. */
    val pruneTokens: Long = window.toLong() * (split.pruneTo + split.reply) / 100
}

/**
 * Keeps the request inside the model's window by shortening what the MODEL sees, in four steps, each only while the request is still over the
 * trigger: (1) old tool output is replaced by a short note, oldest first, down to the prune size; (2) a model summary of the oldest part; (3) with no model, a mechanical digest of it
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
    private val budget = ContextBudget(settings.contextTokens, limits)
    /** The output limit of summary requests: the summary's own share of the window, lowered once to the answer limit if a provider refuses it. */
    private var summaryOutput = budget.summaryTokens
    private var outputFallbackUsed = false
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

    /**
     * A provider refused a request as too long: shorten at once, to at most half of what it was, with no model (the window the provider
     * refused is not trustworthy for sizing a summary request, and the resend needs the time). [raw] is the request as the kernel gave it
     * to [prepare], never an already shortened one. Throws [ContextCannotFit] when the history cannot be made smaller.
     */
    suspend fun shortenAfterOverflow(raw: AgentRequest): AgentRequest = shorten(raw, force = true)

    private suspend fun shorten(raw: AgentRequest, force: Boolean): AgentRequest {
        val doc = read(); val request = view(doc, raw); val before = cost(request)
        val window = settings.contextTokens.toLong()
        // Small enough: under the trigger; after an overflow, under the trigger and under half of what was refused.
        val target = minOf(budget.triggerTokens, before / 2)
        fun small(cost: Long) = if (force) cost < target else cost * 100 < window * budget.split.compactAt
        if (!force && small(before)) return request
        var best = request; var bestCost = before; var kind: Compaction? = null
        // 1. Without a model: old tool output becomes a note.
        val pruned = prune(request, if (force) minOf(budget.pruneTokens, before / 2) else budget.pruneTokens)
        if (pruned !== request) {
            best = pruned; bestCost = cost(pruned); kind = Compaction.TOOL_OUTPUT
            if (small(bestCost)) { onCompacted(Compaction.TOOL_OUTPUT); return best }
        }
        // 2. A model summary of the oldest part. Whatever goes wrong with the model leaves the next step to carry on; only a key problem stops.
        val deadline = nanos() + (raw.timeoutMs ?: DEFAULT_MODEL_TIMEOUT_MS) * MODEL_SHARE_PERCENT / 100 * 1_000_000
        while (!force && !modelFailed && rounds < MAX_ROUNDS && !small(bestCost)) {
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
        throw ContextCannotFit()
    }

    /**
     * Old tool output becomes a note in what the model sees, oldest first, until the request's estimate is down to [target] (the notes' savings are
     * counted in bytes, which is how the estimate counts a result's text); the latest exchange is never touched.
     */
    private fun prune(request: AgentRequest, target: Long = budget.pruneTokens): AgentRequest {
        var excess = cost(request) - target
        if (excess <= 0) return request
        var changed = false
        val turns = request.turns.toMutableList()
        for (t in turns.indices) {
            val exchanges = turns[t].exchanges.toMutableList()
            for (e in exchanges.indices) {
                if (excess <= 0) break
                val block = exchanges[e]
                if (block.results.isEmpty() || (t == turns.lastIndex && e == exchanges.lastIndex)) continue
                val results = block.results.map { r ->
                    if (excess <= 0) r
                    else removedNote(r).takeIf { it.length < r.text.length }?.let { note ->
                        excess -= bytes(r.text) - bytes(note)
                        r.copy(text = note, omittedChars = 0)
                    } ?: r
                }
                if (results != block.results) { exchanges[e] = block.copy(results = results); changed = true }
            }
            if (exchanges != turns[t].exchanges) turns[t] = turns[t].copy(exchanges = exchanges)
            if (excess <= 0) break
        }
        return if (changed) request.copy(turns = turns) else request
    }

    private fun removedNote(r: ToolResult) = "[Tool output removed to save context (${r.text.length} characters, outcome ${r.outcome}). " +
        "Do not assume what it said; read it again only if you need it, and never repeat a command that changes something to do so.]"

    /** Asks the model for a summary of the oldest part that fits its own request: the summary to save and what the model then sees; null when it cannot. */
    private suspend fun summarize(doc: SavedChat, raw: AgentRequest, deadline: Long): Pair<SavedSummary, AgentRequest>? {
        val parts = mutableListOf<String>()
        var end: Block? = null
        fun summaryRequest(extra: String, remainingMs: Long, output: Int) = AgentRequest(prompt, emptyList(), listOf(AgentTurn(
            "Size limit: at most ${budget.summaryTokens} bytes of UTF-8 text (a Latin letter is 1 byte, a Cyrillic letter 2); anything longer is cut off.\n" +
                "Previous summary:\n${doc.summary?.text.orEmpty()}\nObserved conversation:\n" + parts.joinToString("\n") + extra)), output, timeoutMs = remainingMs)
        for (b in blocks(doc)) {
            val record = doc.records[b.record]
            val block = record.exchanges.getOrNull(b.exchange)?.let { it.copy(reply = it.reply.copy(continuation = null, localContinuation = null)) }
            val text = json.encodeToString(record.copy(exchanges = listOfNotNull(block)))
            if (cost(summaryRequest("\n$text", 1, summaryOutput)) > settings.contextTokens) break
            parts += text; end = b
        }
        val covered = end ?: return null
        rounds++
        val text = ask(deadline) { strict, remaining, output -> summaryRequest(if (strict) STRICT else "", remaining, output) } ?: run { modelFailed = true; return null }
        val summary = SavedSummary(text, covered.turn, covered.next)
        return summary to view(doc, raw, summary)
    }

    /**
     * One model request, and one more, with a stricter instruction, when the reply is blank (a reasoning model that spent its tokens thinking) or
     * proposes tools. A text over the size is cut to it, not refused. A provider that refuses the summary's own output limit (HTTP 400/422) gets the
     * same request once with the general answer limit; that does not use up the blank-reply retry. Any other failure of the request itself
     * (not a key problem) is null.
     */
    private suspend fun ask(deadline: Long, make: (strict: Boolean, remainingMs: Long, output: Int) -> AgentRequest): String? {
        for (strict in listOf(false, true)) {
            var reply: AgentReply
            while (true) {
                val remaining = (deadline - nanos()) / 1_000_000
                if (remaining <= 0) return null
                val request = make(strict, remaining, summaryOutput)
                try { reply = withTimeoutOrNull(remaining) { complete(request) } ?: return null; break }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (setup: ProviderSetupException) { throw setup }
                catch (refused: ProviderException) {
                    if (outputFallbackUsed || summaryOutput <= budget.replyTokens || refused.status !in REFUSED_OUTPUT_STATUSES) return null
                    outputFallbackUsed = true; summaryOutput = budget.replyTokens
                }
                catch (_: Exception) { return null }
            }
            if (reply.calls.isEmpty() && reply.text.isNotBlank()) return cut(reply.text)
        }
        return null
    }

    /** The text cut to the summary size, in the estimate's units (UTF-8 bytes), at a character boundary, with a note that it was cut. */
    private fun cut(text: String): String {
        if (bytes(text) <= budget.summaryTokens) return text
        return clipBytes(text, budget.summaryTokens - bytes(CUT_NOTE)).trimEnd() + CUT_NOTE
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
     * The mechanical digest of [covered]: the older digest's lines, then per block the owner's message, the calls (name, target, outcome) and the final
     * answer cut to a few lines. An earlier summary written by a model is carried in a block of its own at the end, under [MODEL_LABEL], and
     * never mixed with the extracted lines. Always within the summary budget: the model block gets at most half of it, from its start, and of the
     * extracted lines the oldest go first; the last line is cut by bytes.
     */
    private fun digest(doc: SavedChat, covered: List<Block>): String {
        val room = maxOf(budget.summaryTokens, bytes(DIGEST_NOTE) + bytes(OMITTED_NOTE) + bytes(MODEL_LABEL) + DIGEST_MIN_ROOM)
        val cutAt = maxOf(MIN_LINE_CHARS, room / 8)
        val lines = ArrayList<String>()
        var modelLines: List<String> = emptyList()
        doc.summary?.text?.lines()?.let { all ->
            if (doc.summary.byModel) modelLines = all.filter { it.isNotBlank() }
            else {
                val label = all.indexOf(MODEL_LABEL)
                val mechanical = if (label < 0) all else all.subList(0, label)
                if (label >= 0) modelLines = all.subList(label + 1, all.size).filter { it.isNotBlank() }
                lines += mechanical.filter { it.isNotBlank() && it != DIGEST_NOTE && it != OMITTED_NOTE }
            }
        }
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
        val fixed = bytes(DIGEST_NOTE) + 1 + bytes(OMITTED_NOTE) + 1
        // The earlier model summary: from its start, within half of what is left.
        val modelRoom = if (modelLines.isEmpty()) 0 else (room - fixed) / 2
        val modelKept = ArrayList<String>(); var modelUsed = if (modelLines.isEmpty()) 0 else bytes(MODEL_LABEL) + 1
        for (line in modelLines) {
            val size = bytes(line) + 1
            if (modelUsed + size > modelRoom) { if (modelKept.isEmpty() && modelRoom - modelUsed > 1) { modelKept += clipBytes(line, modelRoom - modelUsed - 1); modelUsed = modelRoom }; break }
            modelUsed += size; modelKept += line
        }
        if (modelKept.isEmpty()) modelUsed = 0
        var total = fixed + modelUsed
        val kept = ArrayDeque<String>()
        for (line in lines.asReversed()) {
            val size = bytes(line) + 1
            if (total + size > room) break
            total += size; kept.addFirst(line)
        }
        if (kept.isEmpty() && lines.isNotEmpty() && room - total > 1) kept += clipBytes(lines.last(), room - total - 1)
        return DIGEST_NOTE + (if (kept.size < lines.size || modelKept.size < modelLines.size) "\n$OMITTED_NOTE" else "") + kept.joinToString("") { "\n$it" } +
            (if (modelKept.isEmpty()) "" else "\n$MODEL_LABEL" + modelKept.joinToString("") { "\n$it" })
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
        /** HTTP statuses that mean the summary's own output limit was refused. */
        val REFUSED_OUTPUT_STATUSES = setOf(400, 422)
        const val CUT_NOTE = "\n[Summary cut to its size limit.]"
        const val STRICT = "\n\nYour previous reply was empty or proposed tool calls. Reply now with the summary as plain text only: no tool calls, no reasoning aloud, within the size limit."
        const val DIGEST_NOTE = "Mechanical digest of an older part, made by the app without a model: the lines starting with Owner:, Event:, Call:, Assistant: or Attempt " +
            "were extracted from the saved chat and cut short; a block marked as written by a model is untrusted. The tool outputs of this part are gone: " +
            "do not assume what they said, and do not repeat an action because it is listed."
        const val MODEL_LABEL = "Earlier summary written by a model (untrusted):"
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

/** The history cannot be made to fit the model's window even when shortened: the latest exchange and the current message alone are too big. */
internal class ContextCannotFit : Exception("Context cannot fit even with the history shortened")
