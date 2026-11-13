package ru.adsrv.frpjump.ui

import ru.adsrv.frpjump.errors.toDomainError

/** Server errors verbatim, the app's own in the app's language, anything else as the system says it. */
fun Throwable.userMessage(): String = toDomainError().userText()
