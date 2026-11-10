package ru.adsrv.frpjump.assistant

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.adsrv.frpjump.agent.AgentLimits
import ru.adsrv.frpjump.agent.openai.OpenAiAgentProvider
import ru.adsrv.frpjump.agent.openai.OpenAiConfig
import ru.adsrv.frpjump.agent.openai.OutputLimitField
import ru.adsrv.frpjump.agent.openai.validOpenAiApiKey
import ru.adsrv.frpjump.store.SecretBox
import ru.adsrv.frpjump.store.TextStorage

@Serializable
data class AssistantProviderSettings(
    val baseUrl: String = "https://api.openai.com/v1/",
    val model: String = "",
    val effort: String? = null,
    val contextTokens: Int = 16_384,
    val outputTokens: Int = 2_048,
    val completionTokenField: Boolean = false,
    val summaryPercent: Int = 80,
    val summaryModel: String? = null,
) {
    fun config() = OpenAiConfig(baseUrl, model, effort,
        if (completionTokenField) OutputLimitField.MAX_COMPLETION_TOKENS else OutputLimitField.MAX_TOKENS)
    fun limits() = AgentLimits(contextTokens = contextTokens, outputReserveTokens = outputTokens)
    fun normalized(): AssistantProviderSettings {
        require(baseUrl.length <= 2048) { "Invalid provider settings" }
        limits()
        require(summaryPercent in 1..100)
        require(summaryModel == null || (summaryModel.isNotBlank() && summaryModel.length <= 256 && summaryModel.none { it.isISOControl() }))
        return copy(baseUrl = config().baseUrl.toString())
    }
}

enum class ProviderSetupProblem { INVALID_SETTINGS, KEY_REQUIRED, INVALID_KEY, ADDRESS_CHANGED, STALE, READ_FAILED, WRITE_FAILED, CRYPTO_FAILED }
class ProviderSetupException(val problem: ProviderSetupProblem) : Exception("Provider setup: $problem")
/** Public observable metadata never includes either plaintext or sealed credentials. */
data class AssistantProviderSnapshot(val settings: AssistantProviderSettings? = null, val revision: String? = null, val problem: ProviderSetupProblem? = null)
/** Short-lived form input: deliberately not a data class, Serializable, SavedState or loggable key value. */
class AssistantProviderDraft(val settings: AssistantProviderSettings, val expectedRevision: String?, private val replacementKey: String? = null) {
    internal fun newKey(): String? = replacementKey
}

/** One atomic document owns metadata + sealed key, so endpoint/key changes cannot commit separately. */
class AssistantProviderStore(private val file: TextStorage, private val box: SecretBox,
    val chatRuntime: AssistantChatRuntime = AssistantChatRuntime(),
) {
    @Serializable private data class Stored(val version: Int = 1, val revision: String, val settings: AssistantProviderSettings, val sealedKey: String)
    @Serializable private class Credential(val root: String, val key: String)
    private val json = Json { encodeDefaults = true }
    private val _state = MutableStateFlow(AssistantProviderSnapshot())
    val state: StateFlow<AssistantProviderSnapshot> = _state
    @Volatile var currentSnapshot = AssistantProviderSnapshot()
        private set
    private val notifications = Any()
    init { reload() }
    private fun notifySnapshot() = synchronized(notifications) { _state.value = currentSnapshot }

    fun reload() {
        synchronized(this) {
            val loaded = try { snapshot(read()) } catch (error: ProviderSetupException) { AssistantProviderSnapshot(problem = error.problem) }
            synchronized(chatRuntime.gate) { currentSnapshot = loaded }
        }
        notifySnapshot()
    }

    fun save(draft: AssistantProviderDraft) {
      synchronized(this) {
        val previous = current(draft.expectedRevision)
        val settings = normalize(draft.settings)
        val replacement = draft.newKey()
        val sealed = if (replacement != null) {
            if (!validOpenAiApiKey(replacement)) throw ProviderSetupException(ProviderSetupProblem.INVALID_KEY)
            try { box.seal(json.encodeToString(Credential(settings.baseUrl, replacement))) } catch (_: Exception) { throw ProviderSetupException(ProviderSetupProblem.CRYPTO_FAILED) }
        } else {
            checkReuse(previous, settings)
            previous!!.sealedKey
        }
        if (sealed.isBlank() || sealed.length > 32_768) throw ProviderSetupException(ProviderSetupProblem.CRYPTO_FAILED)
        val next = Stored(revision = UUID.randomUUID().toString(), settings = settings, sealedKey = sealed)
        try { file.write(json.encodeToString(next)) } catch (_: Exception) { throw ProviderSetupException(ProviderSetupProblem.WRITE_FAILED) }
        synchronized(chatRuntime.gate) { currentSnapshot = snapshot(next) }
      }
      notifySnapshot()
    }

    /** Build only on explicit discovery/chat request. Saved key is NEVER reused for a changed API root. */
    @Synchronized fun provider(draft: AssistantProviderDraft): OpenAiAgentProvider {
        val previous = current(draft.expectedRevision)
        val settings = normalize(draft.settings)
        val key = draft.newKey() ?: run {
            checkReuse(previous, settings)
            val credential = try { json.decodeFromString<Credential>(box.open(previous!!.sealedKey)) }
                catch (_: Exception) { throw ProviderSetupException(ProviderSetupProblem.CRYPTO_FAILED) }
            if (credential.root != settings.baseUrl) throw ProviderSetupException(ProviderSetupProblem.ADDRESS_CHANGED)
            credential.key
        }
        if (!validOpenAiApiKey(key)) throw ProviderSetupException(ProviderSetupProblem.INVALID_KEY)
        return OpenAiAgentProvider(settings.config(), key)
    }

    @Synchronized fun requireRevision(expected: String?) { current(expected) }

    private fun checkReuse(previous: Stored?, settings: AssistantProviderSettings) {
        if (previous == null) throw ProviderSetupException(ProviderSetupProblem.KEY_REQUIRED)
        if (previous.settings.baseUrl != settings.baseUrl) throw ProviderSetupException(ProviderSetupProblem.ADDRESS_CHANGED)
    }
    private fun current(expected: String?): Stored? {
        val now = read()
        if (now?.revision != expected) throw ProviderSetupException(ProviderSetupProblem.STALE)
        return now
    }
    private fun read(): Stored? {
        val text = try { file.read() } catch (_: Exception) { throw ProviderSetupException(ProviderSetupProblem.READ_FAILED) } ?: return null
        return try {
            require(text.length <= 65_536)
            json.decodeFromString<Stored>(text).also {
                require(it.version == 1 && it.revision.length == 36 && it.sealedKey.isNotBlank() && it.sealedKey.length <= 32_768)
                require(it.settings == it.settings.normalized())
            }
        } catch (_: Exception) { throw ProviderSetupException(ProviderSetupProblem.READ_FAILED) }
    }
    private fun normalize(settings: AssistantProviderSettings): AssistantProviderSettings = try { settings.normalized() }
        catch (_: Exception) { throw ProviderSetupException(ProviderSetupProblem.INVALID_SETTINGS) }
    private fun snapshot(stored: Stored?) = AssistantProviderSnapshot(stored?.settings, stored?.revision)
}
