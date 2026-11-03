package ru.adsrv.frpjump.connections

import java.util.concurrent.CancellationException
import ru.adsrv.frpjump.ports.LocalPortStore

/** Connections are authoritative; the derived reservation map may need repair before allocation. */
enum class ConnectionSaveResult { SAVED, SAVED_PORT_REPAIR_PENDING }

/** Not a two-file transaction: never report a durable connection as unsaved because cache writing failed. */
fun saveConnectionWithReservations(
    connections: ConnectionStore,
    ports: LocalPortStore,
    connection: Connection,
    validate: () -> Unit,
    reserve: (MutableMap<String, Int>) -> Unit,
): ConnectionSaveResult {
    var saved = false
    try {
        ports.update { map ->
            validate()
            connections.save(connection)
            saved = true
            reserve(map)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        if (!saved) throw failure
        return ConnectionSaveResult.SAVED_PORT_REPAIR_PENDING
    }
    return ConnectionSaveResult.SAVED
}
