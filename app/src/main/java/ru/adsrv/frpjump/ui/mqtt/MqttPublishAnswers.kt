package ru.adsrv.frpjump.ui.mqtt

import ru.adsrv.frpjump.mqtt.PublishAnswer
import ru.adsrv.frpjump.mqtt.PublishFailure
import ru.adsrv.frpjump.ops.MqttWriteFailure
import ru.adsrv.frpjump.ops.MqttWriteRefusal
import ru.adsrv.frpjump.ops.OpResult
import ru.adsrv.frpjump.ops.mqtt.MqttDelivery

/**
 * The publish sheet's answer for a result of `MqttPublish` only: the exact inverse of `PublishAnswer.toOpResult`, keeping the kind, the
 * broker's code and reason string and every `detail`, so the sheet words every case as before (`PublishSheet.kt`). The Wiren Board
 * devices tab does not use this: it reads its answers from its write book. A reason `MqttPublish` never returns (the WB-only
 * `NotOwner` and `Blocked`) is a programming error here.
 */
fun OpResult<MqttDelivery>.toPublishAnswer(): PublishAnswer = when (this) {
    is OpResult.Done -> when (value) {
        MqttDelivery.ACCEPTED -> PublishAnswer.Accepted
        MqttDelivery.UNCONFIRMED -> PublishAnswer.Unconfirmed
    }
    is OpResult.Refused -> when (val r = reason as? MqttWriteRefusal ?: error("not an MQTT write refusal: $reason")) {
        is MqttWriteRefusal.NotConnected -> PublishAnswer.Failed(PublishFailure.NOT_CONNECTED, r.detail)
        is MqttWriteRefusal.NotWritable -> PublishAnswer.Failed(PublishFailure.CONTROL_NOT_WRITABLE, r.detail)
        MqttWriteRefusal.NotOwner, MqttWriteRefusal.Blocked -> error("a Wiren Board write refusal is not a publish result: $r")
    }
    is OpResult.Failed -> when (val r = reason as? MqttWriteFailure ?: error("not an MQTT write failure: $reason")) {
        is MqttWriteFailure.BrokerRefused -> PublishAnswer.Refused(r.code, r.reasonString)
        is MqttWriteFailure.ConnectionLost -> PublishAnswer.Failed(PublishFailure.LOST, r.detail)
        is MqttWriteFailure.NoAnswer -> PublishAnswer.Failed(PublishFailure.TIMEOUT, r.detail)
        is MqttWriteFailure.Other -> PublishAnswer.Failed(PublishFailure.OTHER, r.detail)
    }
}
