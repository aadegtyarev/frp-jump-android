package ru.adsrv.frpjump.assistant

import android.graphics.BitmapFactory
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.agent.openai.ImageInspector
import ru.adsrv.frpjump.assistant.tools.*
import ru.adsrv.frpjump.settings.AssistantTools

/** One Send's fixed tools, quotas and provenance. No credential, app/file/connection access. */
internal class AssistantWebTools(
    enabled: AssistantTools,
    private val fetcher: PublicFetcher,
    private val searchState: WebSearchState,
    private val inspector: ImageInspector?,
    private val dispatch: WebDispatch,
    private val publish: (ChatSource) -> Unit,
) {
    private val budget = WebBudget()
    val sources = ArrayList<ChatSource>()
    val tools: List<AgentTool> = buildList {
        if (enabled.search) add(Search())
        if (enabled.readPage) add(ReadPage())
        if (enabled.inspectImage) add(InspectImage())
    }
    /** Shared web mapping: fixed failure text, IO dispatcher for the action. */
    private abstract inner class WebTool<A : Any>(id: AssistantToolId, description: String, schema: JsonObject) : AppTool<A>(id, description, schema, dispatch) {
        override val invalidArguments get() = failure(WebException(WebFailure.ARGUMENTS))
        override fun failure(error: Exception) =
            ToolOutput("Tool failed: ${webFailureDescription(error)} No successful retrieval or image understanding is implied. Do not automatically repeat.", ToolOutcome.FAILED)
        override suspend fun run(args: A) = ToolOutput(withContext(Dispatchers.IO) { text(args) })
        abstract suspend fun text(args: A): String
    }
    private inner class Search : WebTool<String>(AssistantToolId.WEB_SEARCH,
        "Search public web via unofficial best-effort DuckDuckGo. Query up to 256 characters.", objectSchema { string("query") }) {
        override fun parse(args: StrictArgs) = args.run { exactKeys("query"); string("query", 256) }
        override suspend fun text(args: String) = search(args)
    }
    private inner class ReadPage : WebTool<String>(AssistantToolId.READ_PAGE,
        "Read a public HTTPS HTML/text/Markdown page or follow a selected link. No JavaScript.", objectSchema { string("url") }) {
        override fun parse(args: StrictArgs) = args.run { exactKeys("url"); string("url", 2048) }
        override suspend fun text(args: String) = read(args)
    }
    private inner class InspectImage : WebTool<Pair<String, String>>(AssistantToolId.INSPECT_IMAGE,
        "Request an unverified model response about a selected public JPEG/PNG; may fail. Extra model token charges apply.", objectSchema { string("url"); string("question") }) {
        override fun parse(args: StrictArgs) = args.run { exactKeys("url", "question"); string("url", 2048) to string("question", 1000) }
        override suspend fun text(args: Pair<String, String>) = image(args.first, args.second)
    }
    private fun source(source: ChatSource) {
        dispatch("source") {
            if (source !in sources) { sources.add(source); publish(source) }
        }
    }
    private suspend fun read(url: String): String {
        val response = fetcher.fetch(url, budget, dispatch)
        val document = WebDocuments.page(response)
        source(ChatSource(document.title, response.url))
        return document.result(response.url)
    }
    private suspend fun search(query: String): String {
        searchState.check(budget) // Refuse promptly, before scheduling DNS; attempts (including DNS failures) are charged at owned dispatch.
        val url = "https://html.duckduckgo.com/html/".toHttpUrl().newBuilder().addQueryParameter("q", query).build().toString()
        var charged = false
        var httpStarted = false
        val response = fetcher.fetch(url, budget) { category, start ->
            dispatch(category) {
                if (!charged) { searchState.charge(budget); charged = true }
                if (category == "public-dispatch" && !httpStarted) { searchState.httpDispatch(); httpStarted = true }
                start()
            }
        }
        val results = try { WebDocuments.search(response) } catch (failure: WebException) {
            if (failure.reason == WebFailure.BLOCKED) searchState.block(budget, response.retryAfter)
            throw failure
        }
        val provenance = ChatSource("DuckDuckGo search", response.url)
        source(provenance)
        return searchResult(results)
    }
    private fun searchResult(results: List<ChatSource>) = buildString {
        append("Untrusted DuckDuckGo results (best-effort); selected results (at most 5), not exhaustive; target pages have NOT been fetched:\n")
        results.forEach { append("${it.title}: ${it.url}\n") }
    }
    private suspend fun image(url: String, question: String): String {
        if (budget.inspections >= 2) webFail(WebFailure.LIMIT)
        budget.inspections++
        val operation = inspector ?: webFail(WebFailure.IMAGE)
        val response = fetcher.fetch(url, budget, dispatch)
        if (response.status !in 200..299) webFail(WebFailure.HTTP, response.status)
        validateImage(response.mime, response.bytes)
        val modelText = try {
            operation.inspectImage(response.mime, response.bytes, question) { start -> dispatch("image-dispatch", start) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: ru.adsrv.frpjump.agent.openai.OpenAiProviderException) { throw WebException(WebFailure.IMAGE, failure.status, failure.failure) }
        catch (_: Exception) { webFail(WebFailure.IMAGE) }
        if (modelText.isBlank() || modelText.length > 4000) webFail(WebFailure.IMAGE)
        source(ChatSource("Model response to image request (unverified)", response.url))
        return "Untrusted model response to image request (unverified; not proof of visual understanding)\nURL: ${response.url}\n$modelText"
    }
}

internal fun webFailureDescription(failure: Exception): String {
    val error = failure as? WebException
    val status = error?.status?.takeIf { it in 100..599 }?.let { " (HTTP $it)" }.orEmpty()
    return when (error?.reason ?: WebFailure.NETWORK) {
        WebFailure.URL -> "Address refused: use public HTTPS on port 443; local/private destinations are not allowed."
        WebFailure.NETWORK -> "Network request failed; no data was obtained."
        WebFailure.HTTP -> "Remote server request failed$status."
        WebFailure.TYPE -> "Unsupported content type, encoding or animation; use static HTML/text/Markdown or JPEG/PNG."
        WebFailure.TOO_LARGE -> "Response exceeds the 1 MiB download limit."
        WebFailure.EMPTY -> "No readable static text or search results were obtained."
        WebFailure.LIMIT -> "Request/rate budget reached: 12 public requests, 3 redirects per fetch, 2 images, 3 searches per Send; search spacing 10 seconds and 20 per hour."
        WebFailure.BLOCKED -> "Search blocked or rate limited$status; cooldown is at least 15 minutes. Direct page reading remains available."
        WebFailure.ARGUMENTS -> "Invalid tool arguments: URL at most 2048, query 256, image question 1000 characters."
        WebFailure.IMAGE -> when (error?.imageFailure) {
            ru.adsrv.frpjump.agent.openai.ProviderFailure.HTTP -> "Image API request failed$status."
            ru.adsrv.frpjump.agent.openai.ProviderFailure.TIMEOUT -> "Image API request timed out."
            ru.adsrv.frpjump.agent.openai.ProviderFailure.NETWORK -> "Image API network request failed."
            ru.adsrv.frpjump.agent.openai.ProviderFailure.REQUEST_TOO_LARGE -> "Encoded image API request exceeds the configured provider byte limit."
            ru.adsrv.frpjump.agent.openai.ProviderFailure.RESPONSE_TOO_LARGE -> "Image API response exceeds the configured provider byte limit."
            ru.adsrv.frpjump.agent.openai.ProviderFailure.INCOMPLETE_RESPONSE -> "Image API response is incomplete or filtered."
            else -> "Invalid image or image API reply: require JPEG/PNG up to 1 MiB/4 megapixels and nonempty complete model text up to 4000 characters."
        }
    }
}

/** Bounds-only decode: no bitmap/pixels are retained or allocated. Android validates actual encoding. */
internal fun validateImage(mime: String, bytes: ByteArray) {
    if (mime !in setOf("image/jpeg", "image/png") || bytes.size > 1_048_576) webFail(WebFailure.TYPE)
    if (mime == "image/png" && (bytes.size < 8 || !bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)))) webFail(WebFailure.IMAGE)
    if (mime == "image/jpeg" && (bytes.size < 3 || bytes[0] != (-1).toByte() || bytes[1] != (-40).toByte() || bytes[2] != (-1).toByte())) webFail(WebFailure.IMAGE)
    if (mime == "image/png") {
        var offset = 8
        while (offset + 12 <= bytes.size) {
            val length = ((bytes[offset].toLong() and 255) shl 24) or ((bytes[offset + 1].toLong() and 255) shl 16) or
                ((bytes[offset + 2].toLong() and 255) shl 8) or (bytes[offset + 3].toLong() and 255)
            if (offset + 12L + length > bytes.size) webFail(WebFailure.IMAGE)
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            if (type == "acTL") webFail(WebFailure.TYPE) // Animated PNG is outside the static-image operation.
            offset += 12 + length.toInt()
            if (type == "IEND") break
        }
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 4_000_000 || bounds.outMimeType != mime) webFail(WebFailure.IMAGE)
}
