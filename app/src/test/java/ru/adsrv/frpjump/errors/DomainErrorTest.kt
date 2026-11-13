package ru.adsrv.frpjump.errors

import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.IOException
import ru.adsrv.frpjump.api.ApiException
import ru.adsrv.frpjump.api.NetworkException
import ru.adsrv.frpjump.api.NetworkException.Reason
import ru.adsrv.frpjump.api.PROTOCOL_VERSION
import ru.adsrv.frpjump.api.ProtocolMismatchException
import ru.adsrv.frpjump.ports.NoFreePortException
import ru.adsrv.frpjump.store.KeyMissingException

/** Each failure branch maps to one typed error that keeps the payload the screens need and names the kind the model is told. */
class DomainErrorTest {
    private val io = IOException("io-detail")

    @Test fun `every exception branch becomes its typed error with the payload the screens need`() {
        assertEquals(DomainError.ServerError("no such device"), ApiException(404, "no such device").toDomainError())
        assertEquals(DomainError.NotHttps("http://h.example"), NetworkException(Reason.NOT_HTTPS, "http://h.example").toDomainError())
        assertEquals(DomainError.Unreachable("h.example", "timeout"), NetworkException(Reason.UNREACHABLE, "h.example", "timeout", io).toDomainError())
        assertEquals(DomainError.Unreachable("h.example", null), NetworkException(Reason.UNREACHABLE, "h.example").toDomainError())
        assertEquals(DomainError.ConnectionBroken("h.example", "reset"), NetworkException(Reason.BROKE, "h.example", "reset").toDomainError())
        assertEquals(DomainError.Redirected("h.example", "other.example"), NetworkException(Reason.REDIRECT, "h.example", "other.example").toDomainError())
        assertEquals(DomainError.Redirected("h.example", null), NetworkException(Reason.REDIRECT, "h.example").toDomainError())
        assertEquals(ProtocolMismatch(PROTOCOL_VERSION + 1, PROTOCOL_VERSION), ProtocolMismatchException(PROTOCOL_VERSION + 1).toDomainError())
        assertEquals(DomainError.UnexpectedAnswer, SerializationException("bad json").toDomainError())
        assertEquals(DomainError.KeyMissing, KeyMissingException().toDomainError())
        assertEquals(DomainError.NoFreePort(40000, 40099), NoFreePortException(40000..40099).toDomainError())
        assertEquals(DomainError.Other("boom"), IllegalStateException("boom").toDomainError())
        // Without a message: the class name, as the old wording did.
        assertEquals(DomainError.Other("IllegalStateException"), IllegalStateException().toDomainError())
    }

    @Test fun `each typed error names the kind the model is told`() {
        assertEquals(ErrorKind.SERVER_ERROR, DomainError.ServerError("x").kind)
        assertEquals(ErrorKind.NOT_HTTPS, DomainError.NotHttps("x").kind)
        assertEquals(ErrorKind.UNREACHABLE, DomainError.Unreachable("x", null).kind)
        assertEquals(ErrorKind.CONNECTION_BROKEN, DomainError.ConnectionBroken("x", null).kind)
        assertEquals(ErrorKind.REDIRECTED, DomainError.Redirected("x", null).kind)
        assertEquals(ErrorKind.UNEXPECTED_ANSWER, DomainError.UnexpectedAnswer.kind)
        assertEquals(ErrorKind.UNEXPECTED, DomainError.KeyMissing.kind)
        assertEquals(ErrorKind.UNEXPECTED, DomainError.Other("x").kind)
        assertEquals(ErrorKind.NOT_LISTED, DomainError.NotListed.kind)
        assertEquals(ErrorKind.NO_FREE_PORT, DomainError.NoFreePort(1, 2).kind)
    }

    @Test fun `the protocol mismatch kind follows which side is newer`() {
        assertEquals(ErrorKind.PROTOCOL_UPDATE_APP, ProtocolMismatch(PROTOCOL_VERSION + 1).kind)
        assertEquals(ErrorKind.PROTOCOL_UPDATE_APP, ProtocolMismatchException(PROTOCOL_VERSION + 5).toDomainError().kind)
        assertEquals(ErrorKind.PROTOCOL_UPDATE_SERVER, ProtocolMismatch(PROTOCOL_VERSION - 1).kind)
        assertEquals(ErrorKind.PROTOCOL_UPDATE_SERVER, ProtocolMismatchException(0).toDomainError().kind)
        // Both versions are explicit: the same server version reads differently against another app version.
        assertEquals(ErrorKind.PROTOCOL_UPDATE_SERVER, ProtocolMismatch(serverVersion = 2, appVersion = 3).kind)
        assertEquals(ErrorKind.PROTOCOL_UPDATE_APP, ProtocolMismatch(serverVersion = 3, appVersion = 2).kind)
    }

    @Test fun `a kind carries no payload`() {
        val secret = "private-host-detail-message"
        val errors = listOf(
            DomainError.ServerError(secret), DomainError.NotHttps(secret), DomainError.Unreachable(secret, secret),
            DomainError.ConnectionBroken(secret, secret), DomainError.Redirected(secret, secret), DomainError.Other(secret),
            DomainError.UnexpectedAnswer, DomainError.KeyMissing, DomainError.NotListed, DomainError.NoFreePort(40000, 40099),
            ProtocolMismatch(7),
        )
        for (error in errors) assertFalse("$error", error.kind.name.contains("private") || error.kind.name.contains("40000"))
        // The kind is a name of the enum, never built from a value.
        for (error in errors) assertEquals("$error", error.kind, ErrorKind.valueOf(error.kind.name))
    }

    @Test fun `typed errors are values, so state and flows can compare them`() {
        assertEquals(DomainError.Unreachable("h", "d"), DomainError.Unreachable("h", "d"))
        assertEquals(DomainError.Unreachable("h", "d").hashCode(), DomainError.Unreachable("h", "d").hashCode())
        assertNotEquals(DomainError.Unreachable("h", "d"), DomainError.Unreachable("h", "e"))
        assertNotEquals(DomainError.Unreachable("h", "d"), DomainError.ConnectionBroken("h", "d"))
        assertEquals(ProtocolMismatch(2), ProtocolMismatch(2))
        // Two failures with the same words are the same error: no Throwable identity.
        assertEquals(IOException("same").toDomainError(), IOException("same").toDomainError())
    }
}
