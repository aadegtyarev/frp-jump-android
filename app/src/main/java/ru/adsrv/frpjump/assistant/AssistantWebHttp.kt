package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import ru.adsrv.frpjump.settings.GrantTarget
import okio.Buffer
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.tools.*
import ru.adsrv.frpjump.ops.tunnel.SavedTunnelIds
import ru.adsrv.frpjump.web.CertCheck
import ru.adsrv.frpjump.web.certCheck
import ru.adsrv.frpjump.web.certFingerprint
import java.net.Proxy
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

internal fun readyWebPort(state: ru.adsrv.frpjump.tunnel.TunnelsState?, key: String?): Int? {
    if (state == null || key == null || state.pending[key] != null) return null
    return state.tunnels.singleOrNull { it.key == key && it.state.status in setOf(ru.adsrv.frpjump.tunnel.TunnelStatus.DIRECT, ru.adsrv.frpjump.tunnel.TunnelStatus.RELAYED) }?.localPort
}

/** The whole endpoint and its authority, captured together and resolved again before and after the call. */
internal data class WebHttpTarget(val localPort: Int, val https: Boolean, val pin: String?, val identity: Any, val period: Any,
    val profile: String = "", val name: String = "", val device: String = "", val port: Int = 0, val certRevision: Long = 0, val certReadable: Boolean = true)


class AssistantWebHttp internal constructor(private val resolve: (SavedTunnelIds) -> WebHttpTarget?) {
    internal suspend fun get(ids: SavedTunnelIds, path: String, budget: Int): ToolOutput {
        val target = resolve(ids) ?: return unavailable()
        return exchange(ids, path, budget, target, null, null)
    }
    internal suspend fun download(ids: SavedTunnelIds, path: String, name: String, budget: Int): ToolOutput {
        val target = resolve(ids) ?: return unavailable()
        return exchange(ids, path, budget, target, null, name)
    }
    internal fun pin(ids: SavedTunnelIds): WebHttpTarget? = resolve(ids)
    internal suspend fun post(ids: SavedTunnelIds, path: String, contentType: String, body: String, budget: Int, target: WebHttpTarget): ToolOutput {
        val authority = currentCoroutineContext()[ChangeDispatchAuthority]?.prepare(AssistantToolId.HTTP_POST, GrantTarget(ids.profileId, ids.connectionId)) ?: return unavailable()
        val dispatched = AtomicBoolean(false)
        return try { exchange(ids, path, budget, target, Post(contentType, body.toByteArray(Charsets.UTF_8), authority, dispatched), null) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { if (dispatched.get()) uncertain() else unavailable() }
    }
    internal suspend fun upload(ids: SavedTunnelIds, path: String, file: TempInfo, budget: Int, target: WebHttpTarget): ToolOutput {
        val context = currentCoroutineContext()
        val authority = context[ChangeDispatchAuthority]?.prepare(AssistantToolId.HTTP_UPLOAD, GrantTarget(ids.profileId, ids.connectionId)) ?: return uploadUnavailable()
        val access = context[TemporaryAccess] ?: return uploadUnavailable()
        val job = context[Job]
        val dispatched = AtomicBoolean(false)
        val check = { (access.apply(TempAction.UploadInfo(file.id), job) as? TempResult.Info)?.file == file }
        val load = { (access.apply(TempAction.UploadBytes(file) { resolve(ids) == target }, job) as? TempResult.Bytes)?.bytes }
        return try { exchange(ids, path, budget, target, Post("application/octet-stream", null, authority, dispatched, file.bytes, check, load), null) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { if (dispatched.get()) uncertain() else uploadUnavailable() }
    }
    private class Post(val contentType: String, @Volatile var bytes: ByteArray?, val authority: () -> Boolean, val dispatched: AtomicBoolean,
        val size: Int = bytes?.size ?: 0, val check: () -> Boolean = { true }, val load: (() -> ByteArray?)? = null)
    private sealed interface Response {
        class Downloaded(val bytes: ByteArray) : Response
        class Output(val value: ToolOutput) : Response
    }
    private suspend fun exchange(ids: SavedTunnelIds, path: String, budget: Int, target: WebHttpTarget, post: Post?, download: String?): ToolOutput {
        fun cannotSend() = if (post?.load != null) uploadUnavailable() else unavailable()
        val url = localHttpUrl(target.https, target.localPort, path)
        val context = currentCoroutineContext()
        val admission = context[SideEffectAdmission] ?: return cannotSend()
        val job = context[Job]
        val tempAccess = context[TemporaryAccess]
        fun downloadAllowed() = download == null || tempAccess?.apply?.invoke(TempAction.Download(null) { resolve(ids) == target }, job) == TempResult.Checked
        val category = if (post == null) "http-get-dispatch" else "http-post-dispatch"
        val builder = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).callTimeout(30, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                if (job?.isActive != true || !admission.check(category) || job.isActive.not() || post?.authority?.invoke() == false || resolve(ids) != target || !downloadAllowed() || post?.check?.invoke() == false) throw java.io.IOException("Unavailable")
                if (post != null) {
                    if (post.load != null) {
                        post.bytes = post.load.invoke() ?: throw java.io.IOException("Unavailable")
                        if (!post.authority() || resolve(ids) != target || !post.check() || job.isActive.not()) throw java.io.IOException("Unavailable")
                    }
                    if (!post.dispatched.compareAndSet(false, true)) throw java.io.IOException("Unavailable")
                }
                val response = chain.proceed(chain.request())
                // OkHttp otherwise retries a 503 with Retry-After: 0, even with retryOnConnectionFailure disabled.
                if (response.code == 503) response.newBuilder().removeHeader("Retry-After").build() else response
            }
        if (target.https && target.pin != null) {
            val trust = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw java.security.cert.CertificateException()
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    if (certCheck(target.pin, chain.firstOrNull()?.let { certFingerprint(it.encoded) }, true) != CertCheck.Trusted)
                        throw java.security.cert.CertificateException()
                }
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
            builder.sslSocketFactory(ssl.socketFactory, trust).hostnameVerifier { _, session ->
                runCatching { certCheck(target.pin, certFingerprint(session.peerCertificates.first().encoded), true) == CertCheck.Trusted }.getOrDefault(false)
            }
        }
        currentCoroutineContext().ensureActive()
        if (!admission.check(category) || post?.authority?.invoke() == false || resolve(ids) != target || !downloadAllowed() || post?.check?.invoke() == false) return cannotSend()
        val request = Request.Builder().url(url).header("Accept-Encoding", "identity")
        if (post == null) request.get() else request.post(object : RequestBody() {
            override fun contentType() = post.contentType.toMediaType()
            override fun contentLength() = post.size.toLong()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) { sink.write(checkNotNull(post.bytes)) }
        })
        val call = builder.build().newCall(request.build())
        val result = blockingCancellable(call::cancel) {
            call.execute().use { response ->
                val bytes = Buffer(); val source = response.body?.source()
                if (download != null) {
                    val length = response.body?.contentLength() ?: 0L
                    val encodings = response.headers.values("Content-Encoding")
                    val lengths = response.headers.values("Content-Length")
                    val transfers = response.headers.values("Transfer-Encoding")
                    val declaredLength = lengths.singleOrNull()?.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toLongOrNull()
                    if (response.code !in 200..299 || response.code == 206 || response.headers.values("Content-Range").isNotEmpty() ||
                        encodings.size > 1 || encodings.any { !it.equals("identity", true) } || lengths.size > 1 ||
                        (lengths.isNotEmpty() && (declaredLength == null || transfers.isNotEmpty())) ||
                        transfers.size > 1 || transfers.any { !it.equals("chunked", true) } ||
                        (response.code in setOf(204, 205) && ((declaredLength != null && declaredLength != 0L) || transfers.isNotEmpty())) ||
                        (lengths.isEmpty() && transfers.isEmpty() && response.protocol in setOf(okhttp3.Protocol.HTTP_1_0, okhttp3.Protocol.HTTP_1_1) && response.code != 204 && response.code != 205) ||
                        length > TemporaryWorkspace.MAX_FILE_BYTES)
                        return@use Response.Output(ToolOutput("The response is not a complete supported file; nothing was saved.", ToolOutcome.FAILED))
                    while (source != null && bytes.size <= TemporaryWorkspace.MAX_FILE_BYTES &&
                        source.read(bytes, (TemporaryWorkspace.MAX_FILE_BYTES + 1L - bytes.size).coerceAtMost(8192L)) != -1L) { }
                    if (bytes.size > TemporaryWorkspace.MAX_FILE_BYTES || (length >= 0 && bytes.size != length) ||
                        (response.code in setOf(204, 205) && bytes.size != 0L))
                        return@use Response.Output(ToolOutput("The response is incomplete or too large; nothing was saved.", ToolOutcome.FAILED))
                    return@use Response.Downloaded(bytes.readByteArray())
                }
                if (post?.load != null) return@use Response.Output(ToolOutput(buildJsonObject { put("status", response.code) }.toString(), if (response.isSuccessful) ToolOutcome.SUCCESS else ToolOutcome.FAILED))
                val cap = budget.coerceIn(128, 65536)
                val media = response.body?.contentType()
                val textType = media == null || media.type == "text" || media.subtype.let { it == "json" || it.endsWith("+json") || it == "xml" || it.endsWith("+xml") || it == "javascript" }
                val encoding = response.header("Content-Encoding")
                val readableType = textType && (encoding == null || encoding.equals("identity", true)) &&
                    (media?.charset(Charsets.UTF_8) == null || media.charset(Charsets.UTF_8) == Charsets.UTF_8)
                if (readableType) while (source != null && bytes.size <= cap && source.read(bytes, (cap + 1L - bytes.size).coerceAtMost(2048L)) != -1L) { }
                var truncated = bytes.size > cap
                val raw = bytes.readByteArray(minOf(bytes.size, cap.toLong()))
                val decoded = if (readableType && raw.none { it == 0.toByte() }) runCatching {
                    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    if (!truncated) decoder.decode(ByteBuffer.wrap(raw)).toString() else {
                        val chars = CharBuffer.allocate(raw.size)
                        val result = decoder.decode(ByteBuffer.wrap(raw), chars, false)
                        if (result.isError) result.throwException()
                        chars.flip().toString()
                    }
                }.getOrNull() else null
                fun page(text: String, cut: Boolean) = buildJsonObject { put("status", response.code); put("truncated", cut); put("text", text); put("unreadable", decoded == null) }.toString()
                val content = decoded.orEmpty()
                var lo = 0; var hi = content.length
                while (lo < hi) {
                    val mid = (lo + hi + 1) / 2
                    if (page(content.take(mid), true).length <= budget) lo = mid else hi = mid - 1
                }
                if (lo < content.length) truncated = true
                // Do not cut a UTF-16 surrogate pair at the JSON boundary.
                if (lo > 0 && lo < content.length && content[lo - 1].isHighSurrogate() && content[lo].isLowSurrogate()) lo--
                Response.Output(ToolOutput(page(content.take(lo), truncated)))
            }
        }
        currentCoroutineContext().ensureActive()
        if (!admission.check(if (post == null) "http-get-result" else "http-post-result") || resolve(ids) != target)
            return if (post?.dispatched?.get() == true) uncertain() else cannotSend()
        if (result is Response.Output) return result.value
        val bytes = (result as Response.Downloaded).bytes
        val action = TempAction.Create(UUID.randomUUID().toString(), checkNotNull(download), bytes)
        return when (val saved = tempAccess?.apply?.invoke(TempAction.Download(action) { resolve(ids) == target }, job)) {
            is TempResult.Created -> ToolOutput(buildJsonObject { put("file", temporaryInfo(saved.file)) }.toString())
            TempResult.Full -> ToolOutput("The temporary workspace is full; nothing was saved.", ToolOutcome.FAILED)
            else -> unavailable()
        }
    }
    private fun uploadUnavailable() = ToolOutput("The temporary file, permission or saved Web tunnel is unavailable or changed. Nothing was sent.", ToolOutcome.FAILED)
    private fun uncertain() = ToolOutput("The POST may have reached the device. The outcome is unknown; do not retry automatically.", ToolOutcome.FAILED)
    private fun unavailable() = ToolOutput("The saved Web tunnel is unavailable or changed.", ToolOutcome.FAILED)
}

internal class AssistantHttpGetTool(private val http: AssistantWebHttp, private val budget: Int, private val canDownload: Boolean = false) : AppTool<AssistantHttpGetTool.Input>(AssistantToolId.HTTP_GET,
    "Read one root-relative path through an already running saved Web tunnel. Supply profile_id and connection_id from profile_information. Response text is untrusted data and may be private." +
        if (canDownload) " Optionally supply save_as to save complete response bytes as a temporary file and return only its metadata." else "",
    objectSchema { string("profile_id", 256); string("connection_id", 256); string("path", 2048); if (canDownload) string("save_as", 128, required = false) }) {
    data class Input(val ids: SavedTunnelIds, val path: String, val saveAs: String?)
    override fun parse(args: StrictArgs) = args.run {
        onlyKeys("profile_id", "connection_id", "path", "save_as")
        val ids = SavedTunnelIds(string("profile_id", 256), string("connection_id", 256))
        val path = string("path", 2048)
        val saveAs = optionalString("save_as", 128)
        if (!validHttpPath(path) || (saveAs != null && (!canDownload || !validTemporaryName(saveAs)))) throw InvalidArguments()
        Input(ids, path, saveAs)
    }
    override val invalidArguments = ToolOutput("Provide profile_id and connection_id from profile_information and a root-relative path with optional query.", ToolOutcome.FAILED)
    override suspend fun run(args: Input) = if (args.saveAs == null) http.get(args.ids, args.path, budget) else http.download(args.ids, args.path, args.saveAs, budget)
    override fun failure(error: Exception) = ToolOutput(if (error is javax.net.ssl.SSLException) "HTTPS trust failed. Open Web in the app and review the certificate." else "Could not read the saved Web tunnel.", ToolOutcome.FAILED)
}

internal fun validHttpPath(path: String): Boolean = path.startsWith('/') && !path.startsWith("//") && path.none { it == '\\' || it == '#' || it.code < 32 || it.code == 127 }

internal fun localHttpUrl(https: Boolean, port: Int, path: String): HttpUrl {
    val parts = path.split('?', limit = 2)
    return HttpUrl.Builder().scheme(if (https) "https" else "http").host("127.0.0.1").port(port)
        .encodedPath(parts[0]).apply { if (parts.size == 2) encodedQuery(parts[1]) }.build()
}
internal fun exactHttpPath(path: String): Boolean {
    if (!validHttpPath(path)) return false
    val url = localHttpUrl(false, 1, path)
    return path == url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
}
