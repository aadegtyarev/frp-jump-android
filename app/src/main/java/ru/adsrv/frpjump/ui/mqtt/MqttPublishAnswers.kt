package ru.adsrv.frpjump.ui.mqtt

import ru.adsrv.frpjump.mqtt.PublishAnswer
import ru.adsrv.frpjump.mqtt.PublishFailure
import ru.adsrv.frpjump.ops.MqttFailure
import ru.adsrv.frpjump.ops.MqttRefusal
import ru.adsrv.frpjump.ops.OpResult
import ru.adsrv.frpjump.ops.mqtt.MqttDelivery

/**
 * The publish sheet's answer for a result of `MqttPublish` or `MqttWbWrite`: the exact inverse of `PublishAnswer.toOpResult`, so the
 * sheet words every case as before (`PublishSheet.kt`). A reason this layer does not produce for a publish is worded as an
 * unspecified failure rather than dropped.
 */
fun OpResult<MqttDelivery>.toPublishAnswer(): PublishAnswer = when (this) {
    is OpResult.Done -> if (value == MqttDelivery.ACCEPTED) PublishAnswer.Accepted else PublishAnswer.Unconfirmed
    is OpResult.Refused -> when (reason) {
        MqttRefusal.NotConnected -> PublishAnswer.Failed(PublishFailure.NOT_CONNECTED)
        MqttRefusal.NotWritable -> PublishAnswer.Failed(PublishFailure.CONTROL_NOT_WRITABLE)
        // Only a Wiren Board write returns these, and its screen reads its answer from the book, not from this.
        MqttRefusal.NotOwner, MqttRefusal.Blocked -> PublishAnswer.Failed(PublishFailure.NOT_CONNECTED)
        else -> PublishAnswer.Failed(PublishFailure.OTHER)
    }
    is OpResult.Failed -> when (val r = reason) {
        is MqttFailure.BrokerRefused -> PublishAnswer.Refused(r.code, r.reasonString)
        MqttFailure.ConnectionLost -> PublishAnswer.Failed(PublishFailure.LOST)
        MqttFailure.NoAnswer -> PublishAnswer.Failed(PublishFailure.TIMEOUT)
        is MqttFailure.Other -> PublishAnswer.Failed(PublishFailure.OTHER, r.detail)
        else -> PublishAnswer.Failed(PublishFailure.OTHER)
    }
}
