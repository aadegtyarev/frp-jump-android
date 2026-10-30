package ru.adsrv.frpjump.api

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import ru.adsrv.frpjump.BuildConfig
import ru.adsrv.frpjump.enroll.validateControlUrl
import ru.adsrv.frpjump.store.Credentials
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** The server answered with an error; [detail] is its `detail` text, shown as is. */
class ApiException(val status: Int, val detail: String) : Exception(detail)

/** The server could not be reached. */
class NetworkException(message: String, cause: Throwable? = null) : Exception(message, cause)

class ProtocolMismatchException(val serverVersion: Int) : Exception(
    if (serverVersion > PROTOCOL_VERSION) {
        "The server speaks protocol $serverVersion, this app speaks $PROTOCOL_VERSION: update the app."
    } else {
        "The server speaks protocol $serverVersion, this app speaks $PROTOCOL_VERSION: update the server."
    },
)

const val AGENT_VERSION = "android-${BuildConfig.VERSION_NAME}"

class FrpJumpApi(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun enroll(controlUrl: String, token: String, requestedName: String?): EnrollResponse {
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

    /** 204 on success; marks the phone online for the owner's other devices. */
    suspend fun heartbeat(c: Credentials) {
        val body = WireJson.encodeToString(HeartbeatRequest(AGENT_VERSION))
        call(authorized(c, endpoint(c.controlUrl, "heartbeat")).post(body.toRequestBody(JSON)).build())
    }

    /** Throws [ProtocolMismatchException] when the server names a different wire version. */
    suspend fun desiredState(c: Credentials): DesiredStateResponse {
        val request = authorized(c, endpoint(c.controlUrl, "desired-state")).get().build()
        val response = WireJson.decodeFromString<DesiredStateResponse>(call(request))
        val version = response.protocolVersion
        if (version != null && version != PROTOCOL_VERSION) throw ProtocolMismatchException(version)
        return response
    }

    /** Idempotent on the server: connecting again returns the existing grant. */
    suspend fun connect(c: Credentials, deviceName: String, targetPort: Int, protocol: String): ConnectResponse {
        val body = WireJson.encodeToString(ConnectRequest(deviceName, targetPort, protocol))
        val request = authorized(c, endpoint(c.controlUrl, "connect")).post(body.toRequestBody(JSON)).build()
        return WireJson.decodeFromString(call(request))
    }

    /** False when the server had no such connection (404): already disconnected. */
    suspend fun disconnect(c: Credentials, deviceName: String, targetPort: Int): Boolean {
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
        val base = validateControlUrl(controlUrl) ?: throw NetworkException("Not an https:// server URL: $controlUrl")
        val builder = base.toHttpUrl().newBuilder().addPathSegments(AGENT_API_PREFIX.trimStart('/'))
        segments.forEach { builder.addPathSegment(it) }
        return builder.build()
    }

    private suspend fun call(request: Request): String {
        val response = try {
            http.newCall(request).await()
        } catch (e: IOException) {
            throw NetworkException("Cannot reach ${request.url.host}: ${e.message ?: e.javaClass.simpleName}", e)
        }
        response.use {
            val text = try {
                it.body?.string().orEmpty()
            } catch (e: IOException) {
                throw NetworkException("Connection to ${request.url.host} broke: ${e.message}", e)
            }
            if (!it.isSuccessful) throw ApiException(it.code, errorDetail(text).ifBlank { "HTTP ${it.code}" })
            return text
        }
    }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response) { response.close() }
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
}
