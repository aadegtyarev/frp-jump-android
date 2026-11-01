package ru.adsrv.frpjump.mqtt

/**
 * What the publish sheet's Send sends: [payload] as UTF-8 to [topic]; [json]
 * is the format it was written in. [field] is the topic in the sheet's
 * field, under which the send is remembered: [topic] is another one when a
 * rule sends the value to a command topic.
 */
data class PublishRequest(val topic: String, val payload: String, val qos: Int, val retain: Boolean, val json: Boolean, val field: String = topic)

/** Whether [answer] means the message left the phone: what the sheet remembers. */
fun wasSent(answer: PublishAnswer): Boolean = answer == PublishAnswer.Accepted || answer == PublishAnswer.Unconfirmed

/** What Send answers, shown under the button. */
sealed interface PublishAnswer {
    /** MQTT 5, QoS 1 or 2: PUBACK / PUBREC with a success reason code. */
    data object Accepted : PublishAnswer

    /** MQTT 5, QoS 1 or 2: an error reason [code], with the broker's reason string when it sent one. */
    data class Refused(val code: Int, val reasonString: String?) : PublishAnswer

    /** QoS 0, or 3.1.1 (whose brokers drop a refused publish silently): written, nothing more is known. */
    data object Unconfirmed : PublishAnswer

    data class Failed(val failure: PublishFailure, val detail: String? = null) : PublishAnswer
}

enum class PublishFailure {
    /** The session had no connection when Send was tapped. */
    NOT_CONNECTED,

    /** The connection dropped before the broker answered. */
    LOST,

    /** No answer in [PUBLISH_TIMEOUT_MS]. */
    TIMEOUT,

    /** Anything else; the answer's detail names it. */
    OTHER,
}

/** How long Send waits for the broker's answer. */
const val PUBLISH_TIMEOUT_MS = 10_000L

/** Characters of a broker's reason string shown at most. */
private const val REASON_CHARS = 200

/** The answer for an MQTT 5 PUBACK or PUBREC with reason [code] and [reasonString]: codes from 0x80 on refuse. */
fun ackAnswer(code: Int, reasonString: String?): PublishAnswer =
    if (code < 0x80) PublishAnswer.Accepted else PublishAnswer.Refused(code, reasonString)

/**
 * The broker's reason for [refused] in one line: the reason code's name in
 * the words of the MQTT 5 spec ("not authorized"), then the reason string
 * when the broker sent one that says more, made safe to show.
 */
fun refusalText(refused: PublishAnswer.Refused): String {
    val name = reasonCodeName(refused.code)
    val said = refused.reasonString?.let { oneLineText(it, REASON_CHARS) }?.takeIf { it.isNotEmpty() && !it.equals(name, ignoreCase = true) }
    return if (said == null) name else "$name ($said)"
}

/** The MQTT 5 name of a PUBACK / PUBREC error reason code, else its number. */
fun reasonCodeName(code: Int): String = when (code) {
    0x80 -> "unspecified error"
    0x83 -> "implementation specific error"
    0x87 -> "not authorized"
    0x90 -> "topic name invalid"
    0x91 -> "packet identifier in use"
    0x97 -> "quota exceeded"
    0x99 -> "payload format invalid"
    else -> "reason code 0x%02X".format(code)
}

/** A topic brokers reserve (`$SYS/…`): warned about, still sent. */
fun isReservedTopic(topic: String): Boolean = topic.startsWith('$')
