package ru.adsrv.frpjump.mqtt.plugins.wirenboard

import ru.adsrv.frpjump.mqtt.LevelPattern
import ru.adsrv.frpjump.mqtt.MqttPath
import ru.adsrv.frpjump.mqtt.NaturalOrder
import ru.adsrv.frpjump.mqtt.TopicValue
import ru.adsrv.frpjump.mqtt.decodeUtf8
import java.math.BigDecimal

/*
 * The Wiren Board convention read from the session's tree: devices under
 * `/devices/<d>`, controls under `/devices/<d>/controls/<c>`, each with
 * its meta (docs/mqtt-contract.md, "Wiren Board"). The explorer knows
 * none of it; the plugin reads the tree's topics like any reader.
 */

private const val DEVICES = "devices"
private const val CONTROLS = "controls"
private const val META = "meta"
private const val ERROR = "error"
private const val NAME = "name"

/** `/devices` and the filters the plugin holds while its tab shows: device and control meta, errors included. */
val WB_META_FILTERS = listOf("/devices/+/meta/#", "/devices/+/controls/+/meta/#")

private fun exact(vararg levels: String) = levels.map { LevelPattern.Exact(it) }

/** The device meta topics: `/devices/+/meta` and below. */
val WB_DEVICE_META_PATTERN: List<LevelPattern> = exact("", DEVICES) + LevelPattern.OneLevel + LevelPattern.Exact(META) + LevelPattern.Rest

/** The control meta topics: `/devices/+/controls/+/meta` and below. */
val WB_CONTROL_META_PATTERN: List<LevelPattern> =
    exact("", DEVICES) + LevelPattern.OneLevel + LevelPattern.Exact(CONTROLS) + LevelPattern.OneLevel + LevelPattern.Exact(META) + LevelPattern.Rest

/** A device's own topics: `/devices/<d>` and below. */
fun wbDevicePattern(id: String): List<LevelPattern> = exact("", DEVICES, id) + LevelPattern.Rest

/** The branch of device [id], opened live while it is shown. */
fun wbDevicePath(id: String): MqttPath = MqttPath(listOf("", DEVICES, id))

/** The status topic of control [control] of device [device]. */
fun wbControlPath(device: String, control: String): MqttPath = MqttPath(listOf("", DEVICES, device, CONTROLS, control))

/**
 * The topics' texts as the plugin reads them: a non-UTF-8 value is left
 * out; an empty one (an empty message, or a cleared retained value) is an
 * empty text, which every meta and error topic reads as absent and a value
 * topic as an empty text for `text` controls and "No value" for the rest.
 */
fun wbTexts(values: List<Pair<MqttPath, TopicValue>>): Map<MqttPath, String> = buildMap {
    values.forEach { (path, value) ->
        decodeUtf8(value.payload)?.let { put(path, it) }
    }
}

/** What one device's topics hold, gathered before reading. */
private class DeviceTopics {
    var json: String? = null
    var name: String? = null
    var error: String? = null
    val controls = HashMap<String, ControlTopics>()
}

private class ControlTopics {
    var value: String? = null
    var json: String? = null
    val fields = HashMap<String, String>()
    var error: String? = null
}

private fun gather(texts: Map<MqttPath, String>): Map<String, DeviceTopics> {
    val devices = HashMap<String, DeviceTopics>()
    texts.forEach { (path, text) ->
        val l = path.levels
        if (l.size < 4 || l[0] != "" || l[1] != DEVICES) return@forEach
        val device = devices.getOrPut(l[2]) { DeviceTopics() }
        when {
            l[3] == META && l.size == 4 -> device.json = text
            l[3] == META && l.size == 5 && l[4] == NAME -> device.name = text
            l[3] == META && l.size == 5 && l[4] == ERROR -> device.error = text
            l[3] == CONTROLS && l.size >= 5 -> {
                val control = device.controls.getOrPut(l[4]) { ControlTopics() }
                when {
                    l.size == 5 -> control.value = text
                    l[5] != META -> Unit
                    l.size == 6 -> control.json = text
                    l.size == 7 && l[6] == ERROR -> control.error = text
                    l.size == 7 -> control.fields[l[6]] = text
                }
            }
        }
    }
    return devices
}

/** A device's title: its JSON meta's title, else `meta/name`, else its id. */
private fun deviceTitle(id: String, topics: DeviceTopics, language: String): String =
    parseDeviceTitle(topics.json)?.pick(language) ?: topics.name?.takeIf { it.isNotBlank() } ?: id

/** One row of the devices list. */
data class WbDevice(
    val id: String,
    val title: String,
    /** `meta/error` of the device itself: "The device does not answer". */
    val deviceError: Boolean,
    /** The device's or any shown (not hidden) control's `meta/error` is set: the red "!". */
    val hasErrors: Boolean,
)

/** By title in the tree's natural order, ties by id. */
private val BY_TITLE: Comparator<WbDevice> = compareBy<WbDevice, String>(NaturalOrder) { it.title }.thenBy(NaturalOrder) { it.id }

/**
 * The devices in [texts] (device and control meta, see [wbTexts]) for a
 * reader of [language]: a device is listed when at least one of its
 * controls has meta; by title.
 */
fun wbDevices(texts: Map<MqttPath, String>, language: String): List<WbDevice> =
    gather(texts).mapNotNull { (id, topics) ->
        val withMeta = topics.controls.values.mapNotNull { c -> parseControlMeta(c.json, c.fields)?.let { c to it } }
        if (withMeta.isEmpty()) return@mapNotNull null
        val deviceError = parseErrors(topics.error).isNotEmpty()
        // A hidden control is not shown in the device, so its error would be a mark with nothing behind it.
        val controlError = withMeta.any { (c, meta) -> !meta.hidden && parseErrors(c.error).isNotEmpty() }
        WbDevice(id, deviceTitle(id, topics, language), deviceError, deviceError || controlError)
    }.sortedWith(BY_TITLE)

/** Whether [texts] (control meta) hold any control with meta: the tab shows. */
fun wbSeen(texts: Map<MqttPath, String>): Boolean = gather(texts).values.any { d -> d.controls.values.any { parseControlMeta(it.json, it.fields) != null } }

/** One control of a device as its row shows it. */
data class WbControl(
    val id: String,
    /** The status topic. */
    val path: MqttPath,
    val title: String,
    val meta: ControlMeta,
    /** The status topic's value; null when not seen ("No value"). */
    val value: String?,
    val errors: List<WbError>,
)

/** A device opened in the tab. */
data class WbDeviceView(
    val id: String,
    val title: String,
    val deviceError: Boolean,
    /** By `order`, then those without one by id; hidden ones left out. */
    val controls: List<WbControl>,
)

private val BY_ORDER: Comparator<WbControl> = Comparator { a, b ->
    val oa: BigDecimal? = a.meta.order
    val ob: BigDecimal? = b.meta.order
    when {
        oa != null && ob != null -> oa.compareTo(ob).takeIf { it != 0 } ?: NaturalOrder.compare(a.id, b.id)
        oa != null -> -1
        ob != null -> 1
        else -> NaturalOrder.compare(a.id, b.id)
    }
}

/**
 * Device [id] read from [texts] (its topics, see [wbDevicePattern] and
 * [wbTexts]) for a reader of [language]; null when it has no control with
 * meta (gone, or never a device of the convention).
 */
fun wbDevice(id: String, texts: Map<MqttPath, String>, language: String): WbDeviceView? {
    val topics = gather(texts)[id] ?: return null
    val all = topics.controls.mapNotNull { (cid, c) ->
        val meta = parseControlMeta(c.json, c.fields) ?: return@mapNotNull null
        // An empty value is a value of a text control, and none of any other.
        val value = c.value?.takeIf { it.isNotEmpty() || isTextType(meta) }
        WbControl(cid, wbControlPath(id, cid), meta.title?.pick(language) ?: cid, meta, value, parseErrors(c.error))
    }
    if (all.isEmpty()) return null
    return WbDeviceView(id, deviceTitle(id, topics, language), parseErrors(topics.error).isNotEmpty(), all.filter { !it.meta.hidden }.sortedWith(BY_ORDER))
}
