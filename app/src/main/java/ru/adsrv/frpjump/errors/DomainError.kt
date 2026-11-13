package ru.adsrv.frpjump.errors

import kotlinx.serialization.SerializationException
import ru.adsrv.frpjump.api.ApiException
import ru.adsrv.frpjump.api.NetworkException
import ru.adsrv.frpjump.api.PROTOCOL_VERSION
import ru.adsrv.frpjump.api.ProtocolMismatchException
import ru.adsrv.frpjump.ports.NoFreePortException
import ru.adsrv.frpjump.store.KeyMissingException

/**
 * What the model is told of a failure, by name: a fixed word with no payload. The names are a model-visible format
 * (`docs/agent-contract.md`, "Optional profile information"): change them only on purpose, together with that contract.
 */
enum class ErrorKind {
    /** The server answered with an error. */
    SERVER_ERROR,
    /** The control address is not HTTPS. */
    NOT_HTTPS,
    /** The server could not be reached. */
    UNREACHABLE,
    /** The connection broke during the call. */
    CONNECTION_BROKEN,
    /** The server redirected. */
    REDIRECTED,
    /** The answer could not be read. */
    UNEXPECTED_ANSWER,
    /** Anything else. */
    UNEXPECTED,
    /** The tunnel was granted, but it was not listed in time. */
    NOT_LISTED,
    /** No free local port in the range. */
    NO_FREE_PORT,
    /** The server speaks a newer protocol: the app is to be updated. */
    PROTOCOL_UPDATE_APP,
    /** The server speaks an older protocol: the server is to be updated. */
    PROTOCOL_UPDATE_SERVER,
    /** The profiles could not be loaded. */
    PROFILES_LOAD_FAILED,
}

/**
 * Why something failed, as a value: domain state keeps it instead of localized text, and `ui/DomainErrorTexts.kt` words it when it is
 * shown. A payload is for the owner's screens only; the model gets [kind] alone. Never a Throwable: it must have a stable `equals`.
 */
sealed interface DomainError {
    val kind: ErrorKind

    /** The server answered with an error; [detail] is its `detail` text, shown as is. */
    data class ServerError(val detail: String) : DomainError { override val kind get() = ErrorKind.SERVER_ERROR }

    /** [host] is the URL itself here. */
    data class NotHttps(val host: String) : DomainError { override val kind get() = ErrorKind.NOT_HTTPS }

    data class Unreachable(val host: String, val detail: String?) : DomainError { override val kind get() = ErrorKind.UNREACHABLE }

    data class ConnectionBroken(val host: String, val detail: String?) : DomainError { override val kind get() = ErrorKind.CONNECTION_BROKEN }

    /** [target] is the host it redirects to; null: unknown. */
    data class Redirected(val host: String, val target: String?) : DomainError { override val kind get() = ErrorKind.REDIRECTED }

    data object UnexpectedAnswer : DomainError { override val kind get() = ErrorKind.UNEXPECTED_ANSWER }

    /** The Keystore key is gone. Told to the model as [ErrorKind.UNEXPECTED]. */
    data object KeyMissing : DomainError { override val kind get() = ErrorKind.UNEXPECTED }

    data class NoFreePort(val first: Int, val last: Int) : DomainError { override val kind get() = ErrorKind.NO_FREE_PORT }

    /** The tunnel was granted but not listed in time. */
    data object NotListed : DomainError { override val kind get() = ErrorKind.NOT_LISTED }

    /** Anything else: [text] is what the system says (its message, else its class name), for the owner's screens. */
    data class Other(val text: String) : DomainError { override val kind get() = ErrorKind.UNEXPECTED }
}

/** The server speaks another wire version: the app is to be updated when [serverVersion] is newer than [appVersion], else the server. */
data class ProtocolMismatch(val serverVersion: Int, val appVersion: Int = PROTOCOL_VERSION) : DomainError {
    override val kind get() = if (serverVersion > appVersion) ErrorKind.PROTOCOL_UPDATE_APP else ErrorKind.PROTOCOL_UPDATE_SERVER
}

/** The branches of the owner's error wording, one typed error each; the wording is `ui/DomainErrorTexts.kt`. */
fun Throwable.toDomainError(): DomainError = when (this) {
    is ApiException -> DomainError.ServerError(detail)
    is NetworkException -> when (reason) {
        NetworkException.Reason.NOT_HTTPS -> DomainError.NotHttps(host)
        NetworkException.Reason.UNREACHABLE -> DomainError.Unreachable(host, detail)
        NetworkException.Reason.BROKE -> DomainError.ConnectionBroken(host, detail)
        NetworkException.Reason.REDIRECT -> DomainError.Redirected(host, detail)
    }
    is ProtocolMismatchException -> ProtocolMismatch(serverVersion)
    is SerializationException -> DomainError.UnexpectedAnswer
    is KeyMissingException -> DomainError.KeyMissing
    is NoFreePortException -> DomainError.NoFreePort(range.first, range.last)
    else -> DomainError.Other(message ?: javaClass.simpleName)
}

/** The tunnel is up but the device takes no connections on [port] (not open there yet). */
data class DevicePortNotOpen(val port: Int)
