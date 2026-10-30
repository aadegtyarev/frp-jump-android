package ru.adsrv.frpjump.connections

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.store.TextStorage

/**
 * One profile's connections, in the order the owner added them
 * ([ru.adsrv.frpjump.profiles.ProfileFiles.CONNECTIONS]). Reads at
 * construction and writes on every change: call off the main thread.
 */
class ConnectionStore(private val file: TextStorage) {
    private val _connections = MutableStateFlow(read(file))
    val connections: StateFlow<List<Connection>> = _connections

    /** Adds [connection], or replaces the one with its id in place. */
    @Synchronized
    fun save(connection: Connection) {
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

    companion object {
        /** The stored list; nothing when there is no file or it does not parse. */
        fun read(file: TextStorage): List<Connection> =
            file.read()?.let { runCatching { WireJson.decodeFromString<List<Connection>>(it) }.getOrNull() }.orEmpty()

        fun encodeConnections(value: List<Connection>): String = WireJson.encodeToString(value)
    }
}
