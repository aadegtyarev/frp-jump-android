package ru.adsrv.frpjump.agent.openai

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import ru.adsrv.frpjump.agent.AgentProvider
import ru.adsrv.frpjump.agent.AgentReply
import ru.adsrv.frpjump.agent.AgentRequest

/** Shared by credential setup and the transport; never put invalid key values in exceptions. */
fun validOpenAiApiKey(key: String): Boolean = key.isNotBlank() && key.length <= 16_384 && key.all { it.code in 33..126 }

/** Non-streaming Chat Completions adapter. No key persistence, tracing, implicit retries or fallback. */
class OpenAiAgentProvider(private val config: OpenAiConfig, private val apiKey: String) : AgentProvider {
    private val owner = Any()
    init { require(validOpenAiApiKey(apiKey)) { "Invalid API key" } }

    override suspend fun complete(request: AgentRequest): AgentReply {
        require(config.model.isNotBlank()) { "Select a provider model before running" }
        require(request.maxOutputTokens in 1..1_000_000) { "Invalid model output limit" }
        val body = OpenAiCodec.encode(request, config, owner).toByteArray(Charsets.UTF_8)
        if (body.size > config.maxRequestBytes) throw OpenAiProviderException(ProviderFailure.REQUEST_TOO_LARGE)
        val target = config.baseUrl.newBuilder().addPathSegment("chat").addPathSegment("completions").build()
        val http = Request.Builder().url(target).header("Authorization", "Bearer $apiKey")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return OpenAiCodec.decode(execute(http), owner)
    }

    /** Called explicitly by the future settings UI; a model ID can also be entered without discovery. */
    suspend fun listModels(): List<ProviderModel> {
        val target = config.baseUrl.newBuilder().addPathSegment("models").build()
        return OpenAiCodec.models(execute(Request.Builder().url(target).header("Authorization", "Bearer $apiKey").get().build()))
    }

    private suspend fun execute(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(OpenAiProviderException(ProviderFailure.NETWORK))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!continuation.isActive) return
                    val text = try {
                        if (!response.isSuccessful) throw OpenAiProviderException(ProviderFailure.HTTP, response.code)
                        val body = response.body ?: throw OpenAiProviderException(ProviderFailure.INVALID_RESPONSE)
                        if (body.contentLength() > config.maxResponseBytes) throw OpenAiProviderException(ProviderFailure.RESPONSE_TOO_LARGE)
                        val buffer = Buffer()
                        val source = body.source()
                        while (source.read(buffer, minOf(8192L, config.maxResponseBytes + 1L - buffer.size)) != -1L) {
                            if (buffer.size > config.maxResponseBytes) throw OpenAiProviderException(ProviderFailure.RESPONSE_TOO_LARGE)
                        }
                        try { Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(buffer.readByteArray())).toString() }
                        catch (_: Exception) { throw OpenAiProviderException(ProviderFailure.INVALID_RESPONSE) }
                    } catch (failure: OpenAiProviderException) {
                        continuation.resumeWithException(failure)
                        return
                    } catch (_: IOException) {
                        continuation.resumeWithException(OpenAiProviderException(ProviderFailure.NETWORK))
                        return
                    }
                    continuation.resume(text)
                }
            }
        })
    }

    private companion object {
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
