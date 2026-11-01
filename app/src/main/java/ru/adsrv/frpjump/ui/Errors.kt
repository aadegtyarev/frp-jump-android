package ru.adsrv.frpjump.ui

import kotlinx.serialization.SerializationException
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.api.ApiException
import ru.adsrv.frpjump.api.NetworkException
import ru.adsrv.frpjump.api.PROTOCOL_VERSION
import ru.adsrv.frpjump.api.ProtocolMismatchException
import ru.adsrv.frpjump.locale.Texts
import ru.adsrv.frpjump.ports.NoFreePortException
import ru.adsrv.frpjump.store.KeyMissingException

/** Server errors verbatim, the app's own in the app's language, anything else as the system says it. */
fun Throwable.userMessage(): String = when (this) {
    is ApiException -> detail
    is NetworkException -> when (reason) {
        NetworkException.Reason.NOT_HTTPS -> Texts.get(R.string.error_not_https, host)
        NetworkException.Reason.UNREACHABLE -> Texts.get(R.string.error_unreachable, host, detail.orEmpty())
        NetworkException.Reason.BROKE -> Texts.get(R.string.error_broke, host, detail.orEmpty())
        NetworkException.Reason.REDIRECT ->
            Texts.get(R.string.error_redirect, host, detail ?: Texts.get(R.string.error_redirect_unknown))
    }
    is ProtocolMismatchException -> Texts.get(
        if (serverVersion > PROTOCOL_VERSION) R.string.error_protocol_update_app else R.string.error_protocol_update_server,
        serverVersion,
        PROTOCOL_VERSION,
    )
    is SerializationException -> Texts.get(R.string.error_unexpected_answer)
    is KeyMissingException -> Texts.get(R.string.error_key_missing)
    is NoFreePortException -> Texts.get(R.string.error_no_free_port, range.first, range.last)
    else -> message ?: javaClass.simpleName
}
