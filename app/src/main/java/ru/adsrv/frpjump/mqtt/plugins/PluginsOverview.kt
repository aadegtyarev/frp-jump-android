package ru.adsrv.frpjump.mqtt.plugins

/*
 * What Settings › «Плагины», each plugin's screen and a connection's
 * «Плагины» sheet show of the stored settings (docs/contract.md
 * scenario 12): pure, so the screens only draw it.
 */

/** Settings › «Плагины» row: «Для MQTT · включено: [on] из [total]», and «· есть исключения» when [anyExceptions]. */
data class PluginsSummary(val on: Int, val total: Int, val anyExceptions: Boolean)

/** One plugin's row on the plugins screen: its app-level switch and its exceptions. */
data class PluginItem(val id: String, val on: Boolean, val exceptions: Exceptions)

/** Where a plugin's state on one connection comes from. */
enum class PluginSource {
    /** «Как в настройках»: the connection follows the app level. */
    APP,

    /** «Только для этого подключения»: the connection's own setting. */
    OWN,
}

/** A connection's own setting for one plugin, as stored: [key] is its [pluginKey]. */
data class OwnEntry(val key: String, val profile: String, val connectionId: String, val on: Boolean)

/** The shipped plugins in their row order. */
fun pluginItems(file: PluginsFile): List<PluginItem> = PluginIds.ALL.map { PluginItem(it, file.app.plugins.isOn(it), file.exceptionsOf(it)) }

/** The Settings row's hint: app-level switches on, and whether any connection has its own setting for any plugin. */
fun pluginsSummary(file: PluginsFile): PluginsSummary {
    val items = pluginItems(file)
    return PluginsSummary(on = items.count { it.on }, total = items.size, anyExceptions = items.any { it.exceptions.any })
}

/** The connections with their own setting for plugin [id], in the file's order (an own setting equal to the app level too). */
fun ownEntries(file: PluginsFile, id: String): List<OwnEntry> = file.connections.mapNotNull { (key, entry) ->
    entry.plugins[id]?.let { OwnEntry(key, profileOfPluginKey(key), key.substringAfter('/'), it.on) }
}

/** Where plugin [id]'s state on connection [key] comes from. */
fun sourceOf(file: PluginsFile, key: String, id: String): PluginSource = if (file.own(key, id) == null) PluginSource.APP else PluginSource.OWN
