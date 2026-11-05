package ru.adsrv.frpjump.agent.openai

import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
    // A deterministic test probe at the actual callback exit; null in production.
    internal var beforeCallbackExit: (() -> Unit)? = null
    internal var beforeResponseRead: (() -> Unit)? = null
    internal var beforeAwaitCallbackExit: (() -> Unit)? = null
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

    private suspend fun execute(request: Request): String {
        val exited = CompletableDeferred<Unit>()
        try {
            return suspendCancellableCoroutine { continuation ->
                val call = client.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                var inlineCallback = false
                try {
                    call.enqueue(object : Callback {
                        private fun callback(block: () -> Unit) {
                            // Normally the dispatcher worker completes exited after the entire
                            // AsyncCall.run returns, not merely after resuming the coroutine.
                            if (!callbackExit.register(exited)) inlineCallback = true
                            try { block() } finally { beforeCallbackExit?.invoke() }
                        }
                        override fun onFailure(call: Call, e: IOException) = callback {
                            continuation.resumeWithException(ioFailure(e))
                        }
                        override fun onResponse(call: Call, response: Response) = callback {
                            response.use {
                                beforeResponseRead?.invoke()
                                if (!continuation.isActive) return@callback
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
                                    return@callback
                                } catch (failure: IOException) {
                                    continuation.resumeWithException(ioFailure(failure))
                                    return@callback
                                }
                                continuation.resume(text)
                            }
                        }
                    })
                } catch (failure: Throwable) {
                    exited.complete(Unit) // enqueue failed without an owned callback
                    throw failure
                } finally {
                    // OkHttp can report executor rejection synchronously from enqueue.
                    if (inlineCallback) exited.complete(Unit)
                }
            }
        } finally {
            // Cancellation must cancel the Call promptly, but not release its caller's
            // admission until callback/body cleanup and dispatcher execution have exited.
            beforeAwaitCallbackExit?.invoke()
            withContext(NonCancellable) { exited.await() }
        }
    }

    private companion object {
        fun ioFailure(error: IOException) = OpenAiProviderException(
            if (error is java.io.InterruptedIOException) ProviderFailure.TIMEOUT else ProviderFailure.NETWORK)

        private class CallbackExitExecutor : ThreadPoolExecutor(
            0, Int.MAX_VALUE, 60, TimeUnit.SECONDS, SynchronousQueue(),
            { task -> Thread(task, "assistant-http").apply { isDaemon = true } },
        ) {
            private val completion = ThreadLocal<MutableList<CompletableDeferred<Unit>>>()
            private val worker = ThreadLocal<Boolean>()
            override fun beforeExecute(thread: Thread, task: Runnable) {
                super.beforeExecute(thread, task)
                worker.set(true)
            }
            fun register(exited: CompletableDeferred<Unit>): Boolean {
                if (worker.get() != true) return false
                val pending = completion.get() ?: mutableListOf<CompletableDeferred<Unit>>().also { completion.set(it) }
                pending.add(exited)
                return true
            }
            override fun afterExecute(task: Runnable, failure: Throwable?) {
                val exited = completion.get()
                completion.remove()
                worker.remove()
                try { super.afterExecute(task, failure) } finally { exited?.forEach { it.complete(Unit) } }
            }
        }
        val callbackExit = CallbackExitExecutor()
        // No logging interceptors, cookies, authenticator or cache. Never retarget/replay keyed calls.
        val client = OkHttpClient.Builder().dispatcher(Dispatcher(callbackExit)).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false)
            .addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                // OkHttp 4 retries 503 + Retry-After:0 even when retryOnConnectionFailure is false.
                if (response.code == 503) response.newBuilder().removeHeader("Retry-After").build() else response
            }
            .connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    }
}
