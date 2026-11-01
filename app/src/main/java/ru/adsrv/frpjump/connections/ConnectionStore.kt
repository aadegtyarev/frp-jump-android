package ru.adsrv.frpjump.connections

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.store.TextStorage

/**
 * One profile's connections, in the order the owner added them
 * ([ru.adsrv.frpjump.profiles.ProfileFiles.CONNECTIONS]). Reads at
 * construction and writes on every change: call off the main thread.
 */
class ConnectionStore(
    private val file: TextStorage,
    /** Where a file that does not parse is copied before anything is saved over it. */
    private val corruptCopy: TextStorage,
) {
    private val _connections = MutableStateFlow(load())
    val connections: StateFlow<List<Connection>> = _connections

    /** Adds [connection], or replaces the one with its id in place. */
    @Synchronized
    fun save(saved: Connection) {
        val connection = saved.withStandardScheme()
        val now = _connections.value
        write(if (now.any { it.id == connection.id }) now.map { if (it.id == connection.id) connection else it } else now + connection)
    }

    @Synchronized
    fun remove(id: String) {
        val now = _connections.value
        if (now.any { it.id == id }) write(now.filter { it.id != id })
    }

    private fun write(value: List<Connection>) {
        file.write(encodeConnections(value))
        _connections.value = value
    }

    /** The stored list; a file that does not parse is kept as [corruptCopy] (the first one) and reads as empty. */
    private fun load(): List<Connection> {
        val text = file.read() ?: return emptyList()
        // A web connection on 80 or 443 takes that port's scheme, whatever an older version stored.
        return runCatching { readConnections(text).map { it.withStandardScheme() } }.getOrElse {
            Log.w(TAG, "connections.json does not parse; kept as a copy")
            if (corruptCopy.read() == null) corruptCopy.write(text)
            emptyList()
        }
    }

    companion object {
        private const val TAG = "ConnectionStore"

        fun encodeConnections(value: List<Connection>): String = WireJson.encodeToString(value)
    }
}

private fun Connection.withStandardScheme(): Connection =
    if (kind == PortKind.WEB) copy(https = schemeIsHttps(port, https)) else this
