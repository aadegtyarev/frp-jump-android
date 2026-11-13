package ru.adsrv.frpjump.ui

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.api.ApiException
import ru.adsrv.frpjump.api.NetworkException
import ru.adsrv.frpjump.api.NetworkException.Reason
import ru.adsrv.frpjump.api.PROTOCOL_VERSION
import ru.adsrv.frpjump.api.ProtocolMismatchException
import ru.adsrv.frpjump.errors.DevicePortNotOpen
import ru.adsrv.frpjump.errors.toDomainError
import ru.adsrv.frpjump.ports.NoFreePortException
import ru.adsrv.frpjump.store.KeyMissingException

/**
 * The words of a typed error are exactly the ones `Throwable.userMessage()` had before it went through them. [legacy] is that
 * function as it was, kept here to pin them. The JVM has no resources, so the source here says which string and which arguments were
 * chosen; `DomainErrorTextsLocaleTest` (instrumented) compares the real EN and RU texts.
 */
class DomainErrorTextsTest {
    /** The resource id and the arguments, in the order they were given: what tells two choices apart without resources. */
    private val chosen = TextSource { id, args -> (listOf("string#$id") + args.map { "<$it>" }).joinToString(" ") }

    /** `Throwable.userMessage()` before the typed errors, verbatim. */
    private fun legacy(e: Throwable, texts: TextSource): String = when (e) {
        is ApiException -> e.detail
        is NetworkException -> when (e.reason) {
            NetworkException.Reason.NOT_HTTPS -> texts.get(R.string.error_not_https, e.host)
            NetworkException.Reason.UNREACHABLE -> texts.get(R.string.error_unreachable, e.host, e.detail.orEmpty())
            NetworkException.Reason.BROKE -> texts.get(R.string.error_broke, e.host, e.detail.orEmpty())
            NetworkException.Reason.REDIRECT ->
                texts.get(R.string.error_redirect, e.host, e.detail ?: texts.get(R.string.error_redirect_unknown))
        }
        is ProtocolMismatchException -> texts.get(
            if (e.serverVersion > PROTOCOL_VERSION) R.string.error_protocol_update_app else R.string.error_protocol_update_server,
            e.serverVersion,
            PROTOCOL_VERSION,
        )
        is SerializationException -> texts.get(R.string.error_unexpected_answer)
        is KeyMissingException -> texts.get(R.string.error_key_missing)
        is NoFreePortException -> texts.get(R.string.error_no_free_port, e.range.first, e.range.last)
        else -> e.message ?: e.javaClass.simpleName
    }

    private val branches = listOf(
        ApiException(409, "server says: busy"),
        NetworkException(Reason.NOT_HTTPS, "http://h.example"),
        NetworkException(Reason.UNREACHABLE, "h.example", "timeout"),
        NetworkException(Reason.UNREACHABLE, "h.example"),
        NetworkException(Reason.BROKE, "h.example", "reset"),
        NetworkException(Reason.BROKE, "h.example"),
        NetworkException(Reason.REDIRECT, "h.example", "other.example"),
        NetworkException(Reason.REDIRECT, "h.example"),
        ProtocolMismatchException(PROTOCOL_VERSION + 1),
        ProtocolMismatchException(PROTOCOL_VERSION - 1),
        SerializationException("bad json"),
        KeyMissingException(),
        NoFreePortException(40000..40099),
        IllegalStateException("plain message"),
        IllegalStateException(),
    )

    @Test fun `every branch picks the same string and arguments as before`() {
        for (e in branches) assertEquals("$e", legacy(e, chosen), e.toDomainError().userText(chosen))
    }

    @Test fun `the branches really differ, so the comparison is not vacuous`() {
        val texts = branches.map { it.toDomainError().userText(chosen) }
        assertEquals(branches.size, texts.toSet().size)
        assertNotEquals(
            ProtocolMismatchException(PROTOCOL_VERSION + 1).toDomainError().userText(chosen),
            ProtocolMismatchException(PROTOCOL_VERSION - 1).toDomainError().userText(chosen),
        )
    }

    @Test fun `userMessage is the typed error's words`() {
        // Without the app's resources the default source gives the ids and arguments too.
        for (e in branches) assertEquals("$e", e.toDomainError().userText(), e.userMessage())
    }

    @Test fun `the device port wording is the one the manager gave`() {
        assertEquals(chosen.get(R.string.device_port_not_open, 80), DevicePortNotOpen(80).userText(chosen))
    }
}
