package ru.adsrv.frpjump.ui

import kotlinx.serialization.SerializationException
import ru.adsrv.frpjump.api.ApiException
import ru.adsrv.frpjump.api.NetworkException
import ru.adsrv.frpjump.api.ProtocolMismatchException

/** Server errors verbatim, everything else in plain words. */
fun Throwable.userMessage(): String = when (this) {
    is ApiException -> detail
    is NetworkException, is ProtocolMismatchException -> message.orEmpty()
    is SerializationException -> "Unexpected answer from the server."
    else -> message ?: javaClass.simpleName
}
