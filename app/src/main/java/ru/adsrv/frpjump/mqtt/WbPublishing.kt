package ru.adsrv.frpjump.mqtt

import ru.adsrv.frpjump.mqtt.plugins.wirenboard.ONE_FIELD_META
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.WbControl
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.WbWidget
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.parseControlMeta
import ru.adsrv.frpjump.mqtt.plugins.wirenboard.widgetOf

/** Opaque identity of one accepted MQTT client. Never reuse it for a reconnect or another session. */
class WbWriteOwner internal constructor()

/** Final use is on the session actor, so incoming meta cannot interleave with the actual send. */
internal fun wbWritable(tree: TopicTree, control: WbControl): Boolean {
    val levels = control.path.levels
    if (levels.size != 5 || levels[0] != "" || levels[1] != "devices" || levels[3] != "controls") return false
    val meta = control.path.child("meta")
    fun text(path: MqttPath) = tree.history(path).firstOrNull()?.let { decodeUtf8(it.payload) }
    val current = parseControlMeta(text(meta), ONE_FIELD_META.mapNotNull { field -> text(meta.child(field))?.let { field to it } }.toMap())
        ?: return false
    return current == control.meta && !current.hidden && widgetOf(current) != WbWidget.READ_ONLY && widgetOf(current) != WbWidget.ALARM
}
