package ru.adsrv.frpjump.ui

import android.content.res.Configuration
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
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

/** The owner's error texts, in EN and RU, are exactly what `Throwable.userMessage()` said before it went through the typed errors. */
class DomainErrorTextsLocaleTest {
    private fun source(tag: String): TextSource {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) })
        return TextSource { id, args -> context.getString(id, *args) }
    }

    /** `Throwable.userMessage()` before the typed errors, verbatim. */
    private fun legacy(e: Throwable, texts: TextSource): String = when (e) {
        is ApiException -> e.detail
        is NetworkException -> when (e.reason) {
            Reason.NOT_HTTPS -> texts.get(R.string.error_not_https, e.host)
            Reason.UNREACHABLE -> texts.get(R.string.error_unreachable, e.host, e.detail.orEmpty())
            Reason.BROKE -> texts.get(R.string.error_broke, e.host, e.detail.orEmpty())
            Reason.REDIRECT -> texts.get(R.string.error_redirect, e.host, e.detail ?: texts.get(R.string.error_redirect_unknown))
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

    @Test fun every_branch_says_the_same_in_en_and_ru() {
        for (tag in listOf("en", "ru")) {
            val texts = source(tag)
            for (e in branches) assertEquals("$tag $e", legacy(e, texts), e.toDomainError().userText(texts))
            assertEquals("$tag: the tunnel was not listed", texts.get(R.string.tunnel_not_listed), ru.adsrv.frpjump.errors.DomainError.NotListed.userText(texts))
            assertEquals("$tag: the device port", texts.get(R.string.device_port_not_open, 80), DevicePortNotOpen(80).userText(texts))
        }
    }

    @Test fun the_two_languages_really_differ() {
        val en = source("en")
        val ru = source("ru")
        for (e in listOf(branches[1], branches[2], branches[8], branches[9], branches[10], branches[11], branches[12]))
            assertNotEquals("$e", e.toDomainError().userText(en), e.toDomainError().userText(ru))
    }
}
