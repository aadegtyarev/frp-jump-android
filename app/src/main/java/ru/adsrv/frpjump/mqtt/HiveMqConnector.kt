package ru.adsrv.frpjump.mqtt

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.exceptions.ConnectionClosedException
import com.hivemq.client.mqtt.exceptions.ConnectionFailedException
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.exceptions.Mqtt3ConnAckException
import com.hivemq.client.mqtt.mqtt3.message.connect.connack.Mqtt3ConnAckReturnCode
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5ConnAckException
import com.hivemq.client.mqtt.mqtt5.message.connect.connack.Mqtt5ConnAckReasonCode
import com.hivemq.client.mqtt.mqtt5.message.subscribe.Mqtt5RetainHandling
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [MqttConnector] on the HiveMQ client: one client per attempt, without
 * the library's own reconnect (the session does that). Clean start with
 * session expiry 0 (MQTT 5) or clean session (3.1.1), keep alive
 * [KEEP_ALIVE_S]. Subscriptions take QoS 2, so a message keeps the QoS it
 * was published with; under MQTT 5 with Retain As Published and Retain
 * Handling 0.
 */
class HiveMqConnector(private val host: String = "127.0.0.1") : MqttConnector {
    override fun connect(request: ConnectRequest, events: LinkEvents, done: (ConnectOutcome) -> Unit) {
        val flags = Flags()
        val builder = MqttClient.builder()
            .identifier(request.clientId)
            .serverHost(host)
            .serverPort(request.port)
            .transportConfig()
            .socketConnectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .mqttConnectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .applyTransportConfig()
            .addDisconnectedListener { if (flags.opened.get() && !flags.closed.getAndSet(true)) events.lost() }
        when (request.version) {
            MqttVersion.V5 -> connect5(builder.useMqttVersion5().buildAsync(), request.login, events, flags, done)
            MqttVersion.V3_1_1 -> connect3(builder.useMqttVersion3().buildAsync(), request.login, events, flags, done)
        }
    }

    private fun connect5(client: Mqtt5AsyncClient, login: MqttLogin?, events: LinkEvents, flags: Flags, done: (ConnectOutcome) -> Unit) {
        client.publishes(MqttGlobalPublishFilter.ALL) { events.message(it.topic.toString(), it.payloadAsBytes, it.isRetain, it.qos.code) }
        val connect = client.connectWith().cleanStart(true).sessionExpiryInterval(0).keepAlive(KEEP_ALIVE_S)
        val send = login?.let { connect.simpleAuth().username(it.user).password(it.password.toByteArray()).applySimpleAuth() } ?: connect
        send.send().whenComplete { _, error ->
            if (error != null) return@whenComplete done(refusal(error))
            done(ConnectOutcome.Opened(Link5(client, flags)))
            opened(client.state.isConnected, events, flags)
        }
    }

    private fun connect3(client: Mqtt3AsyncClient, login: MqttLogin?, events: LinkEvents, flags: Flags, done: (ConnectOutcome) -> Unit) {
        client.publishes(MqttGlobalPublishFilter.ALL) { events.message(it.topic.toString(), it.payloadAsBytes, it.isRetain, it.qos.code) }
        val connect = client.connectWith().cleanSession(true).keepAlive(KEEP_ALIVE_S)
        val send = login?.let { connect.simpleAuth().username(it.user).password(it.password.toByteArray()).applySimpleAuth() } ?: connect
        send.send().whenComplete { _, error ->
            if (error != null) return@whenComplete done(refusal(error))
            done(ConnectOutcome.Opened(Link3(client, flags)))
            opened(client.state.isConnected, events, flags)
        }
    }

    /** From now on a disconnect is a loss; one that came between the CONNACK and here is reported now. */
    private fun opened(connected: Boolean, events: LinkEvents, flags: Flags) {
        flags.opened.set(true)
        if (!connected && !flags.closed.getAndSet(true)) events.lost()
    }

    private class Flags {
        val opened = AtomicBoolean(false)

        /** Closed by the session or reported lost: either way said once. */
        val closed = AtomicBoolean(false)
    }

    private class Link5(private val client: Mqtt5AsyncClient, private val flags: Flags) : MqttLink {
        override fun subscribe(filter: String, done: (granted: Boolean) -> Unit) {
            client.subscribeWith()
                .topicFilter(filter)
                .qos(MqttQos.EXACTLY_ONCE)
                .retainAsPublished(true)
                .retainHandling(Mqtt5RetainHandling.SEND)
                .send()
                .whenComplete { ack, error -> done(error == null && ack.reasonCodes.none { it.isError }) }
        }

        override fun unsubscribe(filter: String) {
            client.unsubscribeWith().topicFilter(filter).send()
        }

        override fun close() {
            flags.closed.set(true)
            client.disconnect()
        }
    }

    private class Link3(private val client: Mqtt3AsyncClient, private val flags: Flags) : MqttLink {
        override fun subscribe(filter: String, done: (granted: Boolean) -> Unit) {
            client.subscribeWith()
                .topicFilter(filter)
                .qos(MqttQos.EXACTLY_ONCE)
                .send()
                .whenComplete { ack, error -> done(error == null && ack.returnCodes.none { it.isError }) }
        }

        override fun unsubscribe(filter: String) {
            client.unsubscribeWith().topicFilter(filter).send()
        }

        override fun close() {
            flags.closed.set(true)
            client.disconnect()
        }
    }

    companion object {
        const val KEEP_ALIVE_S = 30
        private const val TIMEOUT_S = 10L

        /** What a failed CONNECT means for the session. */
        fun refusal(error: Throwable): ConnectOutcome.Refused {
            val cause = if (error is CompletionException) error.cause ?: error else error
            return when (cause) {
                is Mqtt5ConnAckException -> when (val code = cause.mqttMessage.reasonCode) {
                    Mqtt5ConnAckReasonCode.UNSUPPORTED_PROTOCOL_VERSION -> ConnectOutcome.Refused(ConnectRefusal.UNSUPPORTED_VERSION)
                    Mqtt5ConnAckReasonCode.NOT_AUTHORIZED, Mqtt5ConnAckReasonCode.BAD_USER_NAME_OR_PASSWORD -> ConnectOutcome.Refused(ConnectRefusal.BAD_LOGIN)
                    else -> ConnectOutcome.Refused(ConnectRefusal.OTHER, code.name)
                }
                is Mqtt3ConnAckException -> when (val code = cause.mqttMessage.returnCode) {
                    Mqtt3ConnAckReturnCode.UNSUPPORTED_PROTOCOL_VERSION -> ConnectOutcome.Refused(ConnectRefusal.UNSUPPORTED_VERSION)
                    Mqtt3ConnAckReturnCode.NOT_AUTHORIZED, Mqtt3ConnAckReturnCode.BAD_USER_NAME_OR_PASSWORD -> ConnectOutcome.Refused(ConnectRefusal.BAD_LOGIN)
                    else -> ConnectOutcome.Refused(ConnectRefusal.OTHER, code.name)
                }
                is ConnectionFailedException -> ConnectOutcome.Refused(ConnectRefusal.NO_BROKER)
                is ConnectionClosedException -> ConnectOutcome.Refused(ConnectRefusal.CLOSED_BEFORE_CONNACK)
                else -> ConnectOutcome.Refused(ConnectRefusal.OTHER, cause.javaClass.simpleName)
            }
        }
    }
}
