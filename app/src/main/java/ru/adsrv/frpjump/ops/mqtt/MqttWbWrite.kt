package ru.adsrv.frpjump.ops.mqtt

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import ru.adsrv.frpjump.mqtt.WbWriteOwner
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.WbControl
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.WbWidget
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.WbWrite
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.WbWriteBook
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.awaitsEcho
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.currentValue
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.widgetOf
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.writeBlocked
import ru.adsrv.frpjump.ops.Effect
import ru.adsrv.frpjump.ops.MqttRefusal
import ru.adsrv.frpjump.ops.OpResult
import ru.adsrv.frpjump.ops.Operation

/** [payload] to [control]'s `/on`, for the accepted client [owner] that showed the control. */
data class MqttWbWriteInput(val target: MqttTarget, val owner: WbWriteOwner?, val control: WbControl, val payload: String)

/**
 * A Wiren Board control write (QoS 1, not retained). Before anything is sent it refuses, in this order: no open session
 * (`NotConnected`), a client that does not own the intent or a control that is not writable (`NotOwner`, `NotWritable`), and a
 * control whose last write has no outcome yet (`Blocked`, so a double tap sends once). Then it records the write in [book], sends,
 * and records the broker's answer on exactly that write, from the session's answer, even if the caller stopped waiting
 * (an answer after the owner is gone, or for a write a newer one replaced, changes nothing). The session checks owner and
 * writability once more in the same action as the send. [book] belongs to one ViewModel, as it always did: it is not the session's,
 * so answers do not outlive the screen. [onSending] runs right after the write is recorded, before the send (the screen reads the
 * control more often while a write waits). UI only.
 */
class MqttWbWrite(
    private val host: MqttWriteHost,
    private val book: MutableStateFlow<WbWriteBook>,
    private val onSending: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) : Operation<MqttWbWriteInput, MqttDelivery> {
    override val name = "wb_write"
    override val effect = Effect.CHANGE_REMOTE

    override suspend fun invoke(input: MqttWbWriteInput): OpResult<MqttDelivery> {
        val s = host.session(input.target) ?: return OpResult.Refused(MqttRefusal.NotConnected)
        val (_, owner, control, payload) = input
        if (!s.ownsWbWrites(owner)) return OpResult.Refused(MqttRefusal.NotOwner)
        if (!s.wbWritable(control)) return OpResult.Refused(MqttRefusal.NotWritable)
        val now = clock()
        val history = s.history(control.path)
        if (writeBlocked(book.value.writes[control.path]?.takeIf { it.owner === owner }, control.meta, history, now)) return OpResult.Refused(MqttRefusal.Blocked)
        val widget = widgetOf(control.meta)
        val shown = if (widget == WbWidget.WO_SWITCH) book.value.sent[control.path] else currentValue(control.meta, history)
        val write = WbWrite(control.path, payload, awaitsEcho(control.meta, widget, shown, payload), now, owner = owner)
        book.update { it.copy(writes = it.writes + (control.path to write)) }
        onSending()
        return awaitAnswer { done ->
            s.publishWb(owner, control, payload) { answer ->
                if (s.ownsWbWrites(owner)) {
                    val at = clock()
                    // Pure CAS transform: exact-write identity owns both answer and wo-switch feedback.
                    book.update { b -> if (s.ownsWbWrites(owner)) b.answered(write, answer, at, widget == WbWidget.WO_SWITCH) else b }
                }
                done(answer)
            }
        }.toOpResult()
    }
}
