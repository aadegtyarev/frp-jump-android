package ru.adsrv.frpjump.ui.settings

import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.assistant.ProviderSetupException
import ru.adsrv.frpjump.assistant.ProviderSetupProblem
import ru.adsrv.frpjump.agent.openai.OpenAiProviderException
import ru.adsrv.frpjump.locale.Texts

/** Never use raw server/crypto/storage exception messages on this credentials surface. */
internal fun assistantProviderError(error: Exception): String = when (error) {
    is ProviderSetupException -> Texts.get(when (error.problem) {
        ProviderSetupProblem.INVALID_SETTINGS -> R.string.assistant_provider_invalid
        ProviderSetupProblem.KEY_REQUIRED -> R.string.assistant_provider_key_required
        ProviderSetupProblem.INVALID_KEY -> R.string.assistant_provider_key_invalid
        ProviderSetupProblem.ADDRESS_CHANGED -> R.string.assistant_provider_address_changed
        ProviderSetupProblem.STALE -> R.string.assistant_provider_stale
        ProviderSetupProblem.READ_FAILED -> R.string.assistant_provider_read_failed
        ProviderSetupProblem.WRITE_FAILED -> R.string.assistant_provider_write_failed
        ProviderSetupProblem.CRYPTO_FAILED -> R.string.assistant_provider_crypto_failed
    })
    is OpenAiProviderException -> {
        val status = error.status
        if (status != null) Texts.get(R.string.assistant_provider_http_failed, status) else Texts.get(R.string.assistant_provider_api_failed)
    }
    else -> Texts.get(R.string.assistant_provider_api_failed)
}
