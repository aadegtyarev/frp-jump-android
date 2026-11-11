package ru.adsrv.frpjump.agent.openai

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import ru.adsrv.frpjump.agent.AgentProvider
import ru.adsrv.frpjump.agent.AgentReply
import ru.adsrv.frpjump.agent.AgentRequest
import ru.adsrv.frpjump.agent.ImageInspector
import ru.adsrv.frpjump.agent.PersistentContinuations
import ru.adsrv.frpjump.agent.blockingCancellable

/** Shared by credential setup and the transport; never put invalid key values in exceptions. */
fun validOpenAiApiKey(key: String): Boolean = key.isNotBlank() && key.length <= 16_384 && key.all { it.code in 33..126 }

/** Non-streaming Chat Completions adapter. No key persistence, tracing, implicit retries or fallback. */
class OpenAiAgentProvider internal constructor(
    private val config: OpenAiConfig,
    private val apiKey: String,
    // Synthetic tests inject an in-memory exchange client; production always uses the shared one below.
    private val template: OkHttpClient,
) : AgentProvider, ImageInspector, PersistentContinuations {
    constructor(config: OpenAiConfig, apiKey: String) : this(config, apiKey, client)
    private val owner = Any()
    init { require(validOpenAiApiKey(apiKey)) { "Invalid API key" } }

    /** App-owned encrypted checkpoint; caller must enforce the original saved provider revision. */
    override fun saveContinuation(value: ru.adsrv.frpjump.agent.AgentContinuation): String = OpenAiCodec.saveContinuation(value, owner)
    override fun restoreContinuation(saved: String): ru.adsrv.frpjump.agent.AgentContinuation = OpenAiCodec.restoreContinuation(saved, owner)

    /** Encoding is CPU only; the caller (chat or image tool) checks start admission before calling. */
    override suspend fun complete(request: AgentRequest): AgentReply {
        require(config.model.isNotBlank()) { "Select a provider model before running" }
        require(request.maxOutputTokens in 1..1_000_000) { "Invalid model output limit" }
        val body = OpenAiCodec.encode(request, config, owner).toByteArray(Charsets.UTF_8)
        if (body.size > config.maxRequestBytes) throw OpenAiProviderException(ProviderFailure.REQUEST_TOO_LARGE)
        return OpenAiCodec.decode(executeChat(body), owner)
    }

    override suspend fun inspectImage(mime: String, bytes: ByteArray, question: String): String {
        require(config.model.isNotBlank() && mime in setOf("image/jpeg", "image/png") && bytes.size <= 1_048_576 && question.length in 1..1000)
        val body = OpenAiCodec.encodeImage(config, mime, bytes, question).toByteArray(Charsets.UTF_8)
        return OpenAiCodec.decodeImage(executeChat(body), owner)
    }

    private suspend fun executeChat(body: ByteArray): String {
        if (body.size > config.maxRequestBytes) throw OpenAiProviderException(ProviderFailure.REQUEST_TOO_LARGE)
        val target = config.baseUrl.newBuilder().addPathSegment("chat").addPathSegment("completions").build()
        val http = Request.Builder().url(target).header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return execute(http)
    }

    /** Called explicitly by the future settings UI; a model ID can also be entered without discovery. */
    suspend fun listModels(): List<ProviderModel> {
        val target = config.baseUrl.newBuilder().addPathSegment("models").build()
        return OpenAiCodec.models(execute(Request.Builder().url(target).header("Authorization", "Bearer $apiKey").get().build()))
    }

    /** The coroutine ends only after the blocking exchange, body read and close have returned; cancellation cancels this exact call. */
    private suspend fun execute(request: Request): String {
        val call = template.newCall(request)
        return blockingCancellable(call::cancel) {
            try { call.execute().use { readBounded(it) } }
            catch (failure: OpenAiProviderException) { throw failure }
            catch (failure: IOException) { throw ioFailure(failure) }
            catch (failure: Exception) { throw ioFailure(IOException(failure)) }
        }
    }

    private fun readBounded(response: Response): String {
        if (!response.isSuccessful) throw OpenAiProviderException(ProviderFailure.HTTP, response.code)
        val body = response.body ?: throw OpenAiProviderException(ProviderFailure.INVALID_RESPONSE)
        if (body.contentLength() > config.maxResponseBytes) throw OpenAiProviderException(ProviderFailure.RESPONSE_TOO_LARGE)
        val buffer = Buffer()
        val source = body.source()
        while (source.read(buffer, minOf(8192L, config.maxResponseBytes + 1L - buffer.size)) != -1L) {
            if (buffer.size > config.maxResponseBytes) throw OpenAiProviderException(ProviderFailure.RESPONSE_TOO_LARGE)
        }
        return try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(buffer.readByteArray())).toString() }
        catch (_: Exception) { throw OpenAiProviderException(ProviderFailure.INVALID_RESPONSE) }
    }

    private companion object {
        fun ioFailure(error: IOException) = OpenAiProviderException(
            if (error is java.io.InterruptedIOException) ProviderFailure.TIMEOUT else ProviderFailure.NETWORK)

        // No logging interceptors, cookies, authenticator or cache. Never retarget/replay keyed calls.
        val client = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                // OkHttp 4 retries 503 + Retry-After:0 even when retryOnConnectionFailure is false.
                if (response.code == 503) response.newBuilder().removeHeader("Retry-After").build() else response
            }
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    }
}
