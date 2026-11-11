package ru.adsrv.frpjump.agent

/**
 * Optional provider capability: continuations that can be saved as opaque text.
 * Saving works only for continuations owned by this provider instance; restoring binds the saved text to the restoring
 * instance. The caller must restore only under the original provider binding and revision.
 */
interface PersistentContinuations {
    fun saveContinuation(value: AgentContinuation): String
    fun restoreContinuation(saved: String): AgentContinuation
}

/** Optional same-instance image operation; pixels never enter the kernel/history protocol.
 * Returned ordinary model text, including inability/refusal, is unverified and not proof of vision.
 */
interface ImageInspector {
    suspend fun inspectImage(mime: String, bytes: ByteArray, question: String, dispatch: (() -> Unit) -> Unit = { it() }): String
}

enum class ProviderErrorKind { HTTP, NETWORK, TIMEOUT, INCOMPATIBLE_CONTEXT, RESPONSE_TOO_LARGE, REQUEST_TOO_LARGE, INVALID_RESPONSE, INCOMPLETE_RESPONSE }

/** Provider-neutral failure. No response body, endpoint, key, exception cause or user-supplied arguments in diagnostics. */
open class ProviderException(val kind: ProviderErrorKind, val status: Int?, message: String?) : Exception(message)
