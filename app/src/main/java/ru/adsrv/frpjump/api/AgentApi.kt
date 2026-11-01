package ru.adsrv.frpjump.api

import ru.adsrv.frpjump.store.Credentials

/** The agent calls the tunnel loop makes; [FrpJumpApi] in the app, a fake in tests. */
interface AgentApi {
    suspend fun heartbeat(c: Credentials)

    suspend fun desiredState(c: Credentials): DesiredStateResponse

    suspend fun connect(c: Credentials, deviceName: String, targetPort: Int, protocol: String): ConnectResponse

    /** False when the server had no such connection: already disconnected. */
    suspend fun disconnect(c: Credentials, deviceName: String, targetPort: Int): Boolean
}
