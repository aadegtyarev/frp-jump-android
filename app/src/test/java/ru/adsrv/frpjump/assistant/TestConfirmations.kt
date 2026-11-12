package ru.adsrv.frpjump.assistant

import ru.adsrv.frpjump.assistant.chat.ConfirmationSupport
import ru.adsrv.frpjump.settings.ConfirmationMode
import ru.adsrv.frpjump.settings.ConfirmationPolicy

/** For tests that are not about the owner's question: changing calls run as if the owner had turned the question off. */
internal object TestConfirmations {
    val off: ConfirmationSupport get() = ConfirmationSupport(policy = { ConfirmationPolicy(global = ConfirmationMode.OFF) }, persistOff = { true })
}
