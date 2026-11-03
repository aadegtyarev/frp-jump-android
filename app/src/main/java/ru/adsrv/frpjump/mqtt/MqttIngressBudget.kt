package ru.adsrv.frpjump.mqtt

import ru.adsrv.frpjump.settings.MqttIngressLimits
import ru.adsrv.frpjump.settings.MqttOverflow
import ru.adsrv.frpjump.settings.validMqttIngressLimits

/** Short accounting gate only: no scheduler, callback, copying or IO under it. One ledger across attempts. */
internal class MqttIngressBudget(val limits: MqttIngressLimits) {
    init { require(validMqttIngressLimits(limits)) { "invalid MQTT ingress limits" } }
    private val gate = Any()
    private var current: Lease? = null
    private var messages = 0
    private var bytes = 0L

    class Lease internal constructor() {
        internal var accepting = true
        internal var stopSignalQueued = false
    }
    data class Usage(val messages: Int, val bytes: Long)
    sealed interface Admission {
        class Accepted(val reservation: Reservation) : Admission
        class Rejected(val notifyStop: Boolean) : Admission
        data object Stale : Admission
    }

    inner class Reservation internal constructor(private val lease: Lease, private val charge: Long) {
        private var released = false
        fun isCurrent(): Boolean = synchronized(gate) { !released && current === lease && lease.accepting }
        fun release() = synchronized(gate) {
            if (!released) {
                released = true
                messages--
                bytes -= charge
            }
        }
    }

    fun newLease(): Lease = synchronized(gate) { Lease().also { current = it } }
    fun invalidate() = synchronized(gate) { current = null }
    fun owns(lease: Lease): Boolean = synchronized(gate) { current === lease }
    fun usage(): Usage = synchronized(gate) { Usage(messages, bytes) }

    fun stopPostFailed(lease: Lease) = synchronized(gate) {
        if (current === lease && !lease.accepting) lease.stopSignalQueued = false
    }

    fun reserve(lease: Lease, topic: String, payloadBytes: Int): Admission = synchronized(gate) {
        if (current !== lease) return@synchronized Admission.Stale
        if (!lease.accepting) return@synchronized if (lease.stopSignalQueued) Admission.Stale else rejected(lease)
        val charge = MqttIngressLimits.MESSAGE_CHARGE.toLong() + topic.length.toLong() * 2 + payloadBytes.toLong()
        if (payloadBytes < 0 || payloadBytes > limits.payloadBytes || messages >= limits.queueMessages ||
            charge > limits.queueBytes.toLong() - bytes) {
            return@synchronized rejected(lease)
        }
        messages++
        bytes += charge
        Admission.Accepted(Reservation(lease, charge))
    }

    /** A bad/failed payload supplier cannot bypass policy or leak its reservation. */
    fun reject(lease: Lease): Admission = synchronized(gate) {
        if (current !== lease || !lease.accepting) Admission.Stale else rejected(lease)
    }

    // Called only inside the gate. Stop is signalled at most once per lease, not once per rejected publish.
    private fun rejected(lease: Lease): Admission.Rejected {
        val stop = limits.overflow == MqttOverflow.STOP_SESSION
        if (stop) {
            lease.accepting = false
            lease.stopSignalQueued = true
        }
        return Admission.Rejected(stop)
    }
}
