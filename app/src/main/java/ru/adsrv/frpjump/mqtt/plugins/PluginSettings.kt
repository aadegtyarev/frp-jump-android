package ru.adsrv.frpjump.mqtt.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The MQTT plugins' settings as `mqtt_plugins_v2.json` keeps them
 * (docs/contract.md scenario 12): each plugin on or off and set up for the
 * whole app; a connection may have its own on or off, which wins.
 */

/** Where a plugin is on: the connection's own setting when it has one, else the app level. */
fun resolve(app: Boolean, own: Boolean?): Boolean = own ?: app

/** How many connections have their own setting for a plugin, by its value. */
data class Exceptions(val on: Int, val off: Int) {
    val any: Boolean get() = on + off > 0
}

/** The connections with an own setting ([own] not null), split by it; an own setting equal to the app level counts too. */
fun <K> exceptions(own: Map<K, Boolean?>): Exceptions = Exceptions(own.values.count { it == true }, own.values.count { it == false })

/** A connection's own setting after its switch is moved to [moved] with the app level at [app]: none when they agree. */
fun ownAfterSwitch(moved: Boolean, app: Boolean): Boolean? = if (moved == app) null else moved

/** The format version of [PluginsFile]. */
const val PLUGINS_VERSION = 2

/** The Wiren Board plugin's title language: the app's language. */
const val TITLE_LANGUAGE_AUTO = "auto"

/** The Wiren Board plugin's settings, for the whole app. */
@Serializable
data class WirenBoardSettings(
    val on: Boolean = true,
    /** [TITLE_LANGUAGE_AUTO], `ru` or `en`. */
    val titleLanguage: String = TITLE_LANGUAGE_AUTO,
    val showHidden: Boolean = false,
)

/** Every plugin's app-level settings; a plugin missing from the file has its defaults. */
@Serializable
data class AppPlugins(
    @SerialName(PluginIds.WIREN_BOARD) val wirenBoard: WirenBoardSettings = WirenBoardSettings(),
    @SerialName(PluginIds.STATUS_COMMAND) val statusCommand: StatusCommandSettings = StatusCommandSettings(),
) {
    /** The app-level switch of plugin [id]; a plugin this version does not know is on. */
    fun isOn(id: String): Boolean = when (id) {
        PluginIds.WIREN_BOARD -> wirenBoard.on
        PluginIds.STATUS_COMMAND -> statusCommand.on
        else -> true
    }

    fun withOn(id: String, on: Boolean): AppPlugins = when (id) {
        PluginIds.WIREN_BOARD -> copy(wirenBoard = wirenBoard.copy(on = on))
        PluginIds.STATUS_COMMAND -> copy(statusCommand = statusCommand.copy(on = on))
        else -> this
    }
}

@Serializable
data class AppLevel(val plugins: AppPlugins = AppPlugins())

/** A connection's own setting for one plugin: only on or off. */
@Serializable
data class OwnSetting(val on: Boolean)

/** What is kept per connection: own settings by plugin id, and whether the last session showed the WB tab. */
@Serializable
data class ConnectionEntry(
    val plugins: Map<String, OwnSetting> = emptyMap(),
    val wbTabShown: Boolean = false,
) {
    /** Nothing to keep: such an entry is not written. */
    val isEmpty: Boolean get() = plugins.isEmpty() && !wbTabShown
}

/** The whole file. Connections are keyed by [pluginKey]. */
@Serializable
data class PluginsFile(
    val version: Int = PLUGINS_VERSION,
    val app: AppLevel = AppLevel(),
    val connections: Map<String, ConnectionEntry> = emptyMap(),
) {
    /** Connection [key]'s own setting for plugin [id], if it has one. */
    fun own(key: String, id: String): Boolean? = connections[key]?.plugins?.get(id)?.on

    /** Whether plugin [id] is on for connection [key]. */
    fun isOn(key: String, id: String): Boolean = resolve(app.plugins.isOn(id), own(key, id))

    /** The exceptions of plugin [id] over every connection. */
    fun exceptionsOf(id: String): Exceptions = exceptions(connections.mapValues { it.value.plugins[id]?.on })

    /** [key]'s entry changed by [change]; an entry left empty is removed. */
    fun withEntry(key: String, change: (ConnectionEntry) -> ConnectionEntry): PluginsFile {
        val updated = change(connections[key] ?: ConnectionEntry())
        return copy(connections = if (updated.isEmpty) connections - key else connections + (key to updated))
    }

    fun withApp(change: (AppPlugins) -> AppPlugins): PluginsFile = copy(app = app.copy(plugins = change(app.plugins)))

    /** The switch of plugin [id] on connection [key] moved to [on]: its own setting is kept only when it differs from the app level. */
    fun switched(key: String, id: String, on: Boolean): PluginsFile = withOwn(key, id, ownAfterSwitch(on, app.plugins.isOn(id)))

    /** «Как в настройках»: connection [key] follows the app level for plugin [id]. */
    fun followingApp(key: String, id: String): PluginsFile = withOwn(key, id, null)

    private fun withOwn(key: String, id: String, own: Boolean?): PluginsFile = withEntry(key) { e ->
        e.copy(plugins = if (own == null) e.plugins - id else e.plugins + (id to OwnSetting(own)))
    }

    fun withoutConnection(key: String): PluginsFile = copy(connections = connections - key)

    fun withoutProfile(profile: String): PluginsFile = copy(connections = connections.filterKeys { profileOfPluginKey(it) != profile })

    /** What connection [key] has in force. */
    fun forConnection(key: String): ConnectionPlugins {
        val sc = app.plugins.statusCommand
        return ConnectionPlugins(
            wirenBoardOn = isOn(key, PluginIds.WIREN_BOARD),
            statusCommand = sc.copy(on = isOn(key, PluginIds.STATUS_COMMAND)),
            wbTabShown = connections[key]?.wbTabShown == true,
            wirenBoard = app.plugins.wirenBoard,
        )
    }

    /**
     * The WB tab appeared on connection [key]: the next session opens on it.
     * The first time it appears on a connection with Status and command
     * topics on there, the Wiren Board preset turns on, once for the app;
     * turned off by hand afterwards it stays off.
     */
    fun wbTabAppeared(key: String): PluginsFile {
        val shown = withEntry(key) { it.copy(wbTabShown = true) }
        val sc = app.plugins.statusCommand
        if (sc.wirenBoardPresetAutoDone || !isOn(key, PluginIds.STATUS_COMMAND)) return shown
        return shown.withApp { it.copy(statusCommand = sc.copy(presets = sc.presets + PluginIds.PRESET_WIREN_BOARD, wirenBoardPresetAutoDone = true)) }
    }

    /** A session read the whole tree of [key] without Wiren Board meta: the next one opens on Tree. */
    fun wbTabAbsent(key: String): PluginsFile = withEntry(key) { it.copy(wbTabShown = false) }
}

/** The plugins in force for one connection: each resolved, and the app-level settings. */
data class ConnectionPlugins(
    val wirenBoardOn: Boolean,
    /** The app-level settings with `on` resolved for the connection. */
    val statusCommand: StatusCommandSettings,
    /** The connection's last session showed the WB tab: the next one opens on it. */
    val wbTabShown: Boolean,
    val wirenBoard: WirenBoardSettings,
)

/** The key of a connection in [PluginsFile]: `<profile>/<connection id>` (both are UUIDs). */
fun pluginKey(profile: String, connectionId: String): String = "$profile/$connectionId"

fun profileOfPluginKey(key: String): String = key.substringBefore('/')
