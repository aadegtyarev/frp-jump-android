package ru.adsrv.frpjump.api

import kotlinx.serialization.encodeToString
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import ru.adsrv.frpjump.BuildConfig
import ru.adsrv.frpjump.agent.admitSideEffect
import ru.adsrv.frpjump.agent.blockingCancellable
import ru.adsrv.frpjump.devices.DisplayChange
import ru.adsrv.frpjump.enroll.validateControlUrl
import ru.adsrv.frpjump.store.Credentials
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The server answered with an error; [detail] is its `detail` text, shown as is. */
class ApiException(val status: Int, val detail: String) : Exception(detail)

/**
 * The server could not be reached, or not safely. [host] is the server's
 * host (the URL itself for [Reason.NOT_HTTPS]); [detail] is the underlying
 * error, or for [Reason.REDIRECT] the host it redirects to (null: unknown).
 * The message is English, for the log; [ru.adsrv.frpjump.ui.userMessage]
 * says it in the app's language.
 */
class NetworkException(
    val reason: Reason,
    val host: String,
    val detail: String? = null,
    cause: Throwable? = null,
) : Exception(englishMessage(reason, host, detail), cause) {
    enum class Reason { NOT_HTTPS, UNREACHABLE, BROKE, REDIRECT }

    private companion object {
        fun englishMessage(reason: Reason, host: String, detail: String?) = when (reason) {
            Reason.NOT_HTTPS -> "Not an https:// server URL: $host"
            Reason.UNREACHABLE -> "Cannot reach $host: $detail"
            Reason.BROKE -> "Connection to $host broke: $detail"
            Reason.REDIRECT -> "$host redirects to ${detail ?: "an unknown address"}; not following it. Check the server URL."
        }
    }
}

/** The server speaks another wire protocol version; the message (English, for the log) says which side to update. */
class ProtocolMismatchException(val serverVersion: Int) : Exception(
    "The server speaks protocol $serverVersion, this app speaks $PROTOCOL_VERSION: update the " +
        (if (serverVersion > PROTOCOL_VERSION) "app." else "server."),
)

const val AGENT_VERSION = "android-${BuildConfig.VERSION_NAME}"

class FrpJumpApi(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // A redirect would resend the bearer token or the enroll token to
        // wherever it points: never follow one, report it instead.
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) : AgentApi {
    /** Unauthenticated `GET /api/agent/version`; rejects incompatible protocols before exposing features. */
    suspend fun version(controlUrl: String): VersionResponse {
        val request = Request.Builder().url(endpoint(controlUrl, "version")).get().build()
        val response = WireJson.decodeFromString<VersionResponse>(call(request))
        if (response.protocolVersion != PROTOCOL_VERSION) throw ProtocolMismatchException(response.protocolVersion)
        return response
    }

    /**
     * Checks the server's protocol version first: the token is single-use, so
     * it is only spent on a server this app can talk to.
     */
    suspend fun enroll(controlUrl: String, token: String, requestedName: String?): EnrollResponse {
        version(controlUrl)
        val body = WireJson.encodeToString(EnrollRequest(token, AGENT_VERSION, requestedName))
        val request = Request.Builder()
            .url(endpoint(controlUrl, "enroll"))
            .post(body.toRequestBody(JSON))
            .build()
        val response = WireJson.decodeFromString<EnrollResponse>(call(request))
        if (response.protocolVersion != PROTOCOL_VERSION) throw ProtocolMismatchException(response.protocolVersion)
        return response
    }

    suspend fun devices(c: Credentials): List<OwnedDevice> {
        val request = authorized(c, endpoint(c.controlUrl, "devices")).get().build()
        return WireJson.decodeFromString(call(request))
    }

    suspend fun deleteDevice(c: Credentials, name: String) {
        val request = authorized(c, endpoint(c.controlUrl, "devices", name, "delete"))
            .post(ByteArray(0).toRequestBody(null))
            .build()
        call(request)
    }

    /**
     * `POST /devices/{name}/display` with only the fields in [change], so an
     * edit of the other field made meanwhile elsewhere survives. The answer
     * is the device as the server now has it.
     */
    suspend fun setDisplay(c: Credentials, name: String, change: DisplayChange): OwnedDevice {
        val request = authorized(c, endpoint(c.controlUrl, "devices", name, "display"))
            .post(displayBody(change).toRequestBody(JSON))
            .build()
        return WireJson.decodeFromString(call(request))
    }

    /** 204 on success; marks the phone online for the owner's other devices. */
    override suspend fun heartbeat(c: Credentials) {
        val body = WireJson.encodeToString(HeartbeatRequest(AGENT_VERSION, PHONE_KIND))
        call(authorized(c, endpoint(c.controlUrl, "heartbeat")).post(body.toRequestBody(JSON)).build())
    }

    /** Throws [ProtocolMismatchException] when the server names a different wire version. */
    override suspend fun desiredState(c: Credentials): DesiredStateResponse {
        val request = authorized(c, endpoint(c.controlUrl, "desired-state")).get().build()
        val response = WireJson.decodeFromString<DesiredStateResponse>(call(request))
        val version = response.protocolVersion
        if (version != null && version != PROTOCOL_VERSION) throw ProtocolMismatchException(version)
        return response
    }

    /** Idempotent on the server: connecting again returns the existing grant. */
    override suspend fun connect(c: Credentials, deviceName: String, targetPort: Int, protocol: String): ConnectResponse {
        val body = WireJson.encodeToString(ConnectRequest(deviceName, targetPort, protocol))
        val request = authorized(c, endpoint(c.controlUrl, "connect")).post(body.toRequestBody(JSON)).build()
        return WireJson.decodeFromString(call(request))
    }

    /** False when the server had no such connection (404): already disconnected. */
    override suspend fun disconnect(c: Credentials, deviceName: String, targetPort: Int): Boolean {
        val body = WireJson.encodeToString(DisconnectRequest(deviceName, targetPort))
        val request = authorized(c, endpoint(c.controlUrl, "disconnect")).post(body.toRequestBody(JSON)).build()
        return try {
            call(request)
            true
        } catch (e: ApiException) {
            if (e.status == 404) false else throw e
        }
    }

    private fun authorized(c: Credentials, url: HttpUrl) =
        Request.Builder().url(url).header("Authorization", "Bearer ${c.apiToken}")

    /** Re-checks the URL on every call, stored ones included: credentials only go over https. */
    private fun endpoint(controlUrl: String, vararg segments: String): HttpUrl {
        val base = validateControlUrl(controlUrl) ?: throw NetworkException(NetworkException.Reason.NOT_HTTPS, controlUrl)
        val builder = base.toHttpUrl().newBuilder().addPathSegments(AGENT_API_PREFIX.trimStart('/'))
        segments.forEach { builder.addPathSegment(it) }
        return builder.build()
    }

    /**
     * The one request path. [admitSideEffect] only checks that the caller is active outside an owned (assistant) run.
     * The blocking exchange runs on IO; cancelling the caller cancels exactly this [Call] and the coroutine ends only after
     * `execute()` has returned and the body is closed. The cancel runs promptly on an IO worker ([blockingCancellable]).
     * In an owned run only the start check of [admitSideEffect] takes the owner's guard; the callers' credential and storage
     * work, the request itself, the body read and cleanup all run outside it.
     */
    private suspend fun call(request: Request): String {
        admitSideEffect(request.url.pathSegments.last())
        val call = http.newCall(request)
        try {
            return blockingCancellable(call::cancel) {
                val response = try {
                    call.execute()
                } catch (e: IOException) {
                    throw e
                } catch (e: RuntimeException) {
                    // What OkHttp's AsyncCall.run did for any failure of an interceptor.
                    throw IOException("canceled due to $e", e)
                }
                response.use { readResponse(request, it) }
            }
        } catch (e: IOException) {
            throw NetworkException(NetworkException.Reason.UNREACHABLE, request.url.host, e.message ?: e.javaClass.simpleName, e)
        }
    }

    private fun readResponse(request: Request, response: Response): String {
        if (response.isRedirect) throw redirectException(request, response.header("Location"))
        val text = try {
            response.body?.string().orEmpty()
        } catch (e: IOException) {
            throw NetworkException(NetworkException.Reason.BROKE, request.url.host, e.message ?: e.javaClass.simpleName, e)
        }
        if (!response.isSuccessful) throw ApiException(response.code, errorDetail(text).ifBlank { "HTTP ${response.code}" })
        return text
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

/** Names where the server tried to send us, never the full URL (it may carry a token). */
internal fun redirectException(request: Request, location: String?): NetworkException =
    NetworkException(NetworkException.Reason.REDIRECT, request.url.host, location?.let { request.url.resolve(it)?.host })
