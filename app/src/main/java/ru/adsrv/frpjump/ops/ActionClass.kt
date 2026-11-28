package ru.adsrv.frpjump.ops

/**
 * What KIND of action a tool or an operation is, for the owner's question and for "For session" (stage E1). The class never decides
 * by itself whether a call asks: that is still `effect.changes && confirms`, the policy and a valid grant. A tool or an operation cannot
 * be built without one.
 *
 * [grantable]: whether a session grant may be held for the class at all. Whether a TOOL of the class is covered is the tool's own
 * [ru.adsrv.frpjump.assistant.AssistantToolId.grantable]; sharing a class never extends a grant to a tool that is not grantable.
 * [wire] is a stable name, not persisted in E1.
 */
enum class ActionClass(val wire: String, val grantable: Boolean) {
    /** Reads from the public internet. */
    PUBLIC_READ("public_read", false),
    /** Reads through the owner's saved connections. */
    CONNECTION_READ("connection_read", false),
    /** Changes something on a device through a saved connection or its sessions. */
    CONNECTION_CHANGE("connection_change", true),
    /** Publishes to the owner's MQTT explorer only. */
    MQTT_PUBLISH("mqtt_publish", true),
    /** Reads the app's own state (skills). */
    APP_READ("app_read", false),
    /** Changes the app's own state (skills, tunnels, tab names, waits). */
    APP_CHANGE("app_change", false),
}
