package ru.adsrv.frpjump.assistant.chat

/** The four buttons of the confirmation card. */
internal enum class CardTap { ALLOW, SESSION, ALWAYS, DECLINE }

/**
 * What a tap on the card answers. "Always" turns the question off for the whole app, so a plain tap answers nothing yet
 * (null: the card asks for a second confirmation) and only a confirmed one answers [ConfirmationDecision.ALWAYS].
 */
internal fun decisionFor(tap: CardTap, alwaysConfirmed: Boolean): ConfirmationDecision? = when (tap) {
    CardTap.ALLOW -> ConfirmationDecision.ONCE
    CardTap.SESSION -> ConfirmationDecision.SESSION
    CardTap.DECLINE -> ConfirmationDecision.DENY
    CardTap.ALWAYS -> if (alwaysConfirmed) ConfirmationDecision.ALWAYS else null
}
