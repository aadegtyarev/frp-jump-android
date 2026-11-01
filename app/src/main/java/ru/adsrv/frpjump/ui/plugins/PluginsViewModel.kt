package ru.adsrv.frpjump.ui.plugins

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.devices.DeviceIcon
import ru.adsrv.frpjump.mqtt.plugins.OwnEntry
import ru.adsrv.frpjump.mqtt.plugins.PluginItem
import ru.adsrv.frpjump.mqtt.plugins.PluginStore
import ru.adsrv.frpjump.mqtt.plugins.ownEntries
import ru.adsrv.frpjump.mqtt.plugins.pluginItems
import ru.adsrv.frpjump.mqtt.plugins.pluginKey
import ru.adsrv.frpjump.profiles.ProfilesState

/** A saved connection as an exception row names it. */
data class KnownConnection(
    val connection: Connection,
    /** The device's icon from its profile's last list; null: the generic glyph. */
    val icon: DeviceIcon?,
    val profileName: String,
    val profileColor: String,
)

/**
 * One row of «Свои настройки подключений»: the connection and its own
 * setting. [profileName] and [profileColor] are set only when there is
 * more than one profile.
 */
data class ExceptionRow(
    val key: String,
    val profile: String,
    val connectionId: String,
    val on: Boolean,
    val connection: Connection,
    val icon: DeviceIcon?,
    val profileName: String?,
    val profileColor: String?,
)

/**
 * [entries] as rows, in their order. A connection not among [known] (its
 * profile's list not read) is left out until it is.
 */
fun exceptionRows(entries: List<OwnEntry>, known: Map<String, KnownConnection>, manyProfiles: Boolean): List<ExceptionRow> =
    entries.mapNotNull { e ->
        val k = known[e.key] ?: return@mapNotNull null
        ExceptionRow(
            key = e.key,
            profile = e.profile,
            connectionId = e.connectionId,
            on = e.on,
            connection = k.connection,
            icon = k.icon,
            profileName = k.profileName.takeIf { manyProfiles },
            profileColor = k.profileColor.takeIf { manyProfiles },
        )
    }

/** What the plugins screens show. */
data class PluginsUi(
    val plugins: List<PluginItem> = emptyList(),
    /** By plugin id. */
    val exceptions: Map<String, List<ExceptionRow>> = emptyMap(),
    /** The file did not read: the defaults apply until the next change. */
    val unreadable: Boolean = false,
)

/** Every profile's saved connections by [pluginKey], and whether there is more than one profile. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun knownConnections(profiles: Flow<ProfilesState>): Flow<Pair<Map<String, KnownConnection>, Boolean>> = profiles.flatMapLatest { state ->
    val many = state.index.profiles.size > 1
    val runtimes = state.index.profiles.mapNotNull { p -> state.runtimes[p.id]?.let { p to it } }
    if (runtimes.isEmpty()) {
        flowOf(emptyMap<String, KnownConnection>() to many)
    } else {
        combine(
            runtimes.map { (p, r) ->
                combine(r.connections.connections, r.devices.state) { list, devices ->
                    val icons = devices.devices.orEmpty().associate { it.name to it.icon }
                    list.associate { c -> pluginKey(p.id, c.id) to KnownConnection(c, icons[c.device], p.name, p.color) }
                }
            },
        ) { parts -> parts.fold(emptyMap<String, KnownConnection>()) { all, part -> all + part } to many }
    }
}

/**
 * Settings › «Плагины» and each plugin's screen: the app-level switches,
 * the exceptions with their connections, and the writes. Every write is
 * made in the app's scope, so leaving the screen right after a tap does
 * not lose it; the plugin observer applies it to the sessions.
 */
class PluginsViewModel(private val app: FrpJumpApp) : ViewModel() {
    private val store: PluginStore = app.mqttPlugins

    val state: StateFlow<PluginsUi?> = combine(store.changes, knownConnections(app.profiles.state)) { _, (known, many) ->
        val file = store.get()
        PluginsUi(
            plugins = pluginItems(file),
            exceptions = pluginItems(file).associate { it.id to exceptionRows(ownEntries(file, it.id), known, many) },
            unreadable = store.unreadable(),
        )
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The app-level switch of plugin [id]; connections' own settings are left as they are. */
    fun setOn(id: String, on: Boolean) = savePluginChange(app) { store.update { f -> f.withApp { it.withOn(id, on) } } }

    /** «Как в настройках» on an exception row: the connection follows the app level again. */
    fun followApp(id: String, row: ExceptionRow) = savePluginChange(app) { store.update { it.followingApp(row.key, id) } }
}
