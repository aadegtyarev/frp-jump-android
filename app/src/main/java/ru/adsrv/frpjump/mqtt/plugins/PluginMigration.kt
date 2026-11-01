package ru.adsrv.frpjump.mqtt.plugins

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.mqtt.profileOfKey

/*
 * The per-connection `mqtt_plugins.json` of MQTT stage 6 and its migration
 * to `mqtt_plugins_v2.json` (docs/contract.md scenario 12, "Migration").
 */

/** One connection's plugins in `mqtt_plugins.json`. */
@Serializable
internal data class LegacyConnectionPlugins(
    val statusCommand: LegacyStatusCommand = LegacyStatusCommand(),
    val wirenBoard: LegacyWirenBoard = LegacyWirenBoard(),
)

@Serializable
internal data class LegacyStatusCommand(
    val on: Boolean = true,
    val presets: Set<String> = emptySet(),
    val rules: List<RuleSpec> = emptyList(),
    val wirenBoardTabSeen: Boolean = false,
)

@Serializable
internal data class LegacyWirenBoard(val on: Boolean = true, val tabShown: Boolean = false)

/**
 * The old file's connections in the file's order, keyed as the MQTT stores
 * key them; an entry that does not read is skipped. No file: none. Null:
 * the file is not a JSON object at all.
 */
internal fun readLegacy(text: String?): Map<String, LegacyConnectionPlugins>? {
    text ?: return emptyMap()
    val obj = runCatching { WireJson.decodeFromString<JsonObject>(text) }.getOrNull() ?: return null
    return buildMap {
        obj.forEach { (key, value) ->
            runCatching { WireJson.decodeFromJsonElement(LegacyConnectionPlugins.serializer(), value) }.getOrNull()?.let { put(key, it) }
        }
    }
}

/**
 * The v2 settings from the old per-connection ones ([old] in the file's
 * order, keys as [ru.adsrv.frpjump.mqtt.connectionKey] makes them):
 * - both plugins on at app level; a connection with a plugin off gets its own "off";
 * - only connections with Status and command topics on give presets
 *   (their union) and own rules (in connection, then rule order; a rule
 *   equal to one taken is dropped, one with a taken id gets [newId]);
 * - the Wiren Board preset counts as turned on by itself if any connection
 *   had seen the WB tab, with Status and command topics on there or not
 *   (as the contract's migration says); "the tab was shown" is kept per connection.
 */
internal fun migratePlugins(old: Map<String, LegacyConnectionPlugins>, newId: () -> String): PluginsFile {
    val presets = LinkedHashSet<String>()
    val rules = ArrayList<RuleSpec>()
    val ids = HashSet<String>()
    var file = PluginsFile()
    old.forEach { (oldKey, c) ->
        val sc = c.statusCommand
        if (sc.on) {
            presets += sc.presets
            sc.rules.forEach { rule ->
                if (rules.none { it.sameAs(rule) }) {
                    var id = rule.id
                    while (id in ids) id = newId()
                    ids += id
                    rules += rule.copy(id = id)
                }
            }
        }
        val key = newKey(oldKey) ?: return@forEach
        file = file.withEntry(key) { e ->
            val own = buildMap {
                if (!c.wirenBoard.on) put(PluginIds.WIREN_BOARD, OwnSetting(false))
                if (!sc.on) put(PluginIds.STATUS_COMMAND, OwnSetting(false))
            }
            e.copy(plugins = own, wbTabShown = c.wirenBoard.tabShown)
        }
    }
    val autoDone = old.values.any { it.statusCommand.wirenBoardTabSeen }
    return file.withApp { it.copy(statusCommand = StatusCommandSettings(presets = presets, rules = rules, wirenBoardPresetAutoDone = autoDone)) }
}

/** The same rule as far as matching goes: status, command and exclusions (the name and id aside). */
private fun RuleSpec.sameAs(other: RuleSpec) = status == other.status && command == other.command && except == other.except

/** An old key (`["<profile>","<connection>"]`) as a v2 key; null when it is not one. */
private fun newKey(oldKey: String): String? {
    val profile = profileOfKey(oldKey) ?: return null
    val connection = runCatching { WireJson.decodeFromString<List<String>>(oldKey) }.getOrNull()?.getOrNull(1) ?: return null
    return pluginKey(profile, connection)
}
