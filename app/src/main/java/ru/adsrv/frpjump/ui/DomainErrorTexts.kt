package ru.adsrv.frpjump.ui

import androidx.annotation.StringRes
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.errors.DevicePortNotOpen
import ru.adsrv.frpjump.errors.DomainError
import ru.adsrv.frpjump.errors.ProtocolMismatch
import ru.adsrv.frpjump.locale.Texts

/** Where the words come from: the app's language at the moment of asking ([AppTexts]), or any other source (tests). */
fun interface TextSource {
    fun get(@StringRes id: Int, vararg args: Any): String
}

val AppTexts = TextSource { id, args -> Texts.get(id, *args) }

/** The owner's wording of a [DomainError], in the app's language at the moment it is shown. Server errors verbatim. */
fun DomainError.userText(texts: TextSource = AppTexts): String = when (this) {
    is DomainError.ServerError -> detail
    is DomainError.NotHttps -> texts.get(R.string.error_not_https, host)
    is DomainError.Unreachable -> texts.get(R.string.error_unreachable, host, detail.orEmpty())
    is DomainError.ConnectionBroken -> texts.get(R.string.error_broke, host, detail.orEmpty())
    is DomainError.Redirected -> texts.get(R.string.error_redirect, host, target ?: texts.get(R.string.error_redirect_unknown))
    is ProtocolMismatch -> protocolMismatchText(this, texts)
    DomainError.UnexpectedAnswer -> texts.get(R.string.error_unexpected_answer)
    DomainError.KeyMissing -> texts.get(R.string.error_key_missing)
    is DomainError.NoFreePort -> texts.get(R.string.error_no_free_port, first, last)
    DomainError.NotListed -> texts.get(R.string.tunnel_not_listed)
    is DomainError.Other -> text
}

fun ProtocolMismatch.userText(texts: TextSource = AppTexts): String = protocolMismatchText(this, texts)

private fun protocolMismatchText(mismatch: ProtocolMismatch, texts: TextSource): String = texts.get(
    if (mismatch.serverVersion > mismatch.appVersion) R.string.error_protocol_update_app else R.string.error_protocol_update_server,
    mismatch.serverVersion,
    mismatch.appVersion,
)

fun DevicePortNotOpen.userText(texts: TextSource = AppTexts): String = texts.get(R.string.device_port_not_open, port)
