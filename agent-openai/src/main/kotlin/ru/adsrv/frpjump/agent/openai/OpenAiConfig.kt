package ru.adsrv.frpjump.agent.openai

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** API root, e.g. https://api.openai.com/v1. Never a URL containing a key or user information. */
class OpenAiConfig(
    baseUrl: String,
    /** May be empty for discovery; completing a conversation requires a selected model. */
    val model: String = "",
    /** Null means provider default; explicit values are sent, never silently ignored or retried. */
    val reasoningEffort: String? = null,
    val outputLimitField: OutputLimitField = OutputLimitField.MAX_TOKENS,
    val maxResponseBytes: Int = 1_048_576,
    val maxRequestBytes: Int = 2_097_152,
) {
    val baseUrl: HttpUrl
    init {
        val url = baseUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid provider API root")
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "Provider API root must not contain credentials, query or fragment" }
        require(url.isHttps || url.host in setOf("127.0.0.1", "::1", "localhost")) { "Remote provider requires HTTPS" }
        require(model.length <= 256 && model.none { it.isISOControl() }) { "Invalid model ID" }
        require(reasoningEffort == null || reasoningEffort.matches(Regex("[a-z][a-z0-9_]{0,31}"))) { "Invalid effort value" }
        require(maxResponseBytes in 1024..8_388_608 && maxRequestBytes in 1024..8_388_608)
        this.baseUrl = if (url.encodedPath.endsWith('/')) url else url.newBuilder().addPathSegment("").build()
    }
}
enum class OutputLimitField(val wire: String) { MAX_TOKENS("max_tokens"), MAX_COMPLETION_TOKENS("max_completion_tokens") }
enum class ProviderFailure { HTTP, NETWORK, TIMEOUT, INCOMPATIBLE_CONTEXT, RESPONSE_TOO_LARGE, REQUEST_TOO_LARGE, INVALID_RESPONSE, INCOMPLETE_RESPONSE }
/** No response body, endpoint, key, exception cause or user-supplied arguments in diagnostics. */
class OpenAiProviderException(val failure: ProviderFailure, val status: Int? = null) : Exception("Provider failure: $failure${status?.let { " ($it)" }.orEmpty()}")
data class ProviderModel(val id: String)
