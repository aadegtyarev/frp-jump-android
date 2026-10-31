package ru.adsrv.frpjump.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import ru.adsrv.frpjump.BuildConfig
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** GitHub answered with something other than a release; [status] is the HTTP status. */
class ReleaseCheckException(val status: Int) : IOException("GitHub answered HTTP $status")

/** A download's SHA-256 is not the one published with it. */
class ChecksumMismatchException : IOException("SHA-256 does not match the published one")

/**
 * The project's GitHub Releases, read without a token: the latest release
 * (with the ETag of the last answer, so an unchanged one is a cheap 304) and
 * its files. All of it off the main thread.
 */
class ReleaseSource(
    private val latestUrl: String = LATEST_URL,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    sealed interface Latest {
        /** The same release as the one with [etag]. */
        data object NotModified : Latest

        /** [json] is the release; [etag] to send next time. */
        data class Fresh(val json: String, val etag: String?) : Latest

        /** Nothing is published yet (404). */
        data object None : Latest
    }

    suspend fun latest(etag: String?): Latest = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(latestUrl)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "frp-jump-android/${BuildConfig.VERSION_NAME}")
            .apply { etag?.let { header("If-None-Match", it) } }
            .build()
        http.newCall(request).await().use { response ->
            when {
                response.code == 304 -> Latest.NotModified
                response.code == 404 -> Latest.None
                !response.isSuccessful -> throw ReleaseCheckException(response.code)
                else -> Latest.Fresh(response.body?.string().orEmpty(), response.header("ETag"))
            }
        }
    }

    /** A small text file (the `.sha256` one), at most [limit] bytes. */
    suspend fun text(url: String, limit: Int = 4096): String = withContext(Dispatchers.IO) {
        http.newCall(get(url)).await().use { response ->
            if (!response.isSuccessful) throw ReleaseCheckException(response.code)
            val body = response.body ?: throw IOException("empty answer")
            val bytes = body.byteStream().use { it.readNBytesUpTo(limit + 1) }
            if (bytes.size > limit) throw IOException("$url is larger than $limit bytes")
            String(bytes, Charsets.UTF_8)
        }
    }

    /**
     * Downloads [url] into [target] and returns its SHA-256 (lower-case hex),
     * reporting bytes read and the total (0 when unknown) to [onProgress].
     * A part left by a failure or cancellation is deleted.
     */
    suspend fun download(url: String, target: File, maxBytes: Long, onProgress: (Long, Long) -> Unit): String = withContext(Dispatchers.IO) {
        try {
            http.newCall(get(url)).await().use { response ->
                if (!response.isSuccessful) throw ReleaseCheckException(response.code)
                val body = response.body ?: throw IOException("empty answer")
                val total = body.contentLength().coerceAtLeast(0)
                if (total > maxBytes) throw IOException("$url is larger than $maxBytes bytes")
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        copyHashing(input, output, maxBytes) { read ->
                            ensureActive()
                            onProgress(read, total)
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            target.delete()
            throw e
        }
    }

    private fun get(url: String): Request {
        // Release files redirect to GitHub's storage; the client follows https redirects only.
        require(url.startsWith("https://")) { "not an https URL: $url" }
        return Request.Builder().url(url).build()
    }

    companion object {
        const val LATEST_URL = "https://api.github.com/repos/${BuildConfig.UPDATE_REPOSITORY}/releases/latest"
    }
}

/**
 * Copies [input] to [output] and returns the SHA-256 of what went through
 * (lower-case hex); [onChunk] gets the bytes copied so far after each chunk.
 * More than [maxBytes] is refused.
 */
fun copyHashing(input: InputStream, output: OutputStream, maxBytes: Long, onChunk: (Long) -> Unit = {}): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var read = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        read += n
        if (read > maxBytes) throw IOException("larger than $maxBytes bytes")
        digest.update(buffer, 0, n)
        output.write(buffer, 0, n)
        onChunk(read)
    }
    return digest.digest().toHex()
}

/** Whether a download hashed to [actual] is the file published with checksum [expected]. */
fun checksumMatches(expected: String, actual: String): Boolean = expected.isNotEmpty() && expected.equals(actual, ignoreCase = true)

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun InputStream.readNBytesUpTo(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
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
