package ru.adsrv.frpjump.mqtt.plugins

import kotlinx.serialization.Serializable
import ru.adsrv.frpjump.api.WireJson

/** A set of rules shipped with a plugin, turned on per connection. */
@Serializable
data class Preset(val id: String, val name: String, val rules: List<RuleSpec>)

/**
 * A plugin as the app ships it: declarative, no code (docs/mqtt-contract.md,
 * "Payload views (plugins)"). The explorer never depends on one; with every
 * plugin off it is the plain explorer.
 */
@Serializable
data class PluginDescription(val id: String, val presets: List<Preset> = emptyList())

/** Ids of the shipped plugins and presets, as stored per connection. */
object PluginIds {
    const val STATUS_COMMAND = "status-command"

    const val PRESET_WIREN_BOARD = "wirenboard"
    const val PRESET_ZIGBEE2MQTT = "zigbee2mqtt"
    const val PRESET_TASMOTA = "tasmota"
    const val PRESET_STATE_SET = "state-set"
}

/** Tasmota's relays: `POWER` and `POWER1` … `POWER8`. */
private val TASMOTA_POWERS = listOf("POWER") + (1..8).map { "POWER$it" }

/**
 * "Status and command topics": which topic a value is sent to, by rules.
 * Its presets in their shipped order, most specific first; vendor
 * knowledge is nowhere else in the plugin.
 */
val STATUS_COMMAND_PLUGIN: PluginDescription = WireJson.decodeFromString(
    """
    {
      "id": "${PluginIds.STATUS_COMMAND}",
      "presets": [
        {"id": "${PluginIds.PRESET_WIREN_BOARD}", "name": "Wiren Board", "rules": [
          {"status": "/devices/{d}/controls/{c}", "command": "/devices/{d}/controls/{c}/on", "name": "Wiren Board"}
        ]},
        {"id": "${PluginIds.PRESET_ZIGBEE2MQTT}", "name": "Zigbee2MQTT", "rules": [
          {"status": "zigbee2mqtt/{d...}", "command": "zigbee2mqtt/{d...}/set", "name": "Zigbee2MQTT",
           "except": ["zigbee2mqtt/bridge", "zigbee2mqtt/bridge/{rest...}", "zigbee2mqtt/{d...}/set",
                      "zigbee2mqtt/{d...}/get", "zigbee2mqtt/{d...}/availability"]}
        ]},
        {"id": "${PluginIds.PRESET_TASMOTA}", "name": "Tasmota", "rules": [
          ${TASMOTA_POWERS.joinToString(",\n") { """{"status": "stat/{d}/$it", "command": "cmnd/{d}/$it", "name": "Tasmota"}""" }}
        ]},
        {"id": "${PluginIds.PRESET_STATE_SET}", "name": "state / set", "rules": [
          {"status": "{x...}/state", "command": "{x...}/set", "name": "state / set", "except": ["zigbee2mqtt/bridge/state"]}
        ]}
      ]
    }
    """,
)
