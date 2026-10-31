package ru.adsrv.frpjump.mqtt

/** A broker login; the password never shows in logs. */
class MqttLogin(val user: String, val password: String) {
    override fun toString() = "MqttLogin($user)"
}

/** One connection attempt: the broker at 127.0.0.1:[port] speaking [version]; anonymous without [login]. */
class ConnectRequest(val port: Int, val clientId: String, val version: MqttVersion, val login: MqttLogin?)

/** Why a broker was not reached or refused the CONNECT. */
enum class ConnectRefusal {
    /** CONNACK "unsupported protocol version". */
    UNSUPPORTED_VERSION,

    /** The connection closed before any CONNACK (also a CONNACK this version cannot read). */
    CLOSED_BEFORE_CONNACK,

    /** CONNACK "not authorized" or "bad user name or password". */
    BAD_LOGIN,

    /** Nothing listens on the port. */
    NO_BROKER,

    /** Any other refusal; the outcome's detail says which. */
    OTHER,
}

sealed interface ConnectOutcome {
    class Opened(val link: MqttLink) : ConnectOutcome

    data class Refused(val refusal: ConnectRefusal, val detail: String? = null) : ConnectOutcome
}

/** What an open connection reports; called on the client library's threads. */
interface LinkEvents {
    fun message(topic: String, payload: ByteArray, retain: Boolean, qos: Int)

    /** The connection went away without [MqttLink.close]. */
    fun lost()
}

/** An open connection to a broker. */
interface MqttLink {
    /** Subscribes [filter] with the session's options; [done] gets whether the broker granted it (SUBACK). */
    fun subscribe(filter: String, done: (granted: Boolean) -> Unit)

    fun unsubscribe(filter: String)

    /**
     * Publishes [payload] to [topic]; [done] gets the broker's answer
     * ([ackAnswer] under MQTT 5 with QoS 1 or 2, else
     * [PublishAnswer.Unconfirmed]) or what failed, once, on any thread.
     */
    fun publish(topic: String, payload: ByteArray, qos: Int, retain: Boolean, done: (PublishAnswer) -> Unit)

    /** Disconnects; no [LinkEvents.lost] follows. */
    fun close()
}

/** Opens connections; the session decides versions, logins and retries. */
fun interface MqttConnector {
    /** Connects as [request] says; [done] is called once, on any thread. */
    fun connect(request: ConnectRequest, events: LinkEvents, done: (ConnectOutcome) -> Unit)
}
