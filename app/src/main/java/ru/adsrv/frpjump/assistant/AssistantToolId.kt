package ru.adsrv.frpjump.assistant

import ru.adsrv.frpjump.ops.Effect

/**
 * The assistant's tools: the only place with a tool's wire name and persisted setting key.
 * Declaration order is the order of the Tools screen; the order of `tools` in provider requests must match it (pinned by tests).
 */
enum class AssistantToolId(val wire: String, val settingKey: String, val effect: Effect) {
    WEB_SEARCH("web_search", "assistant_search", Effect.READ_REMOTE),
    READ_PAGE("read_page", "assistant_read_page", Effect.READ_REMOTE),
    INSPECT_IMAGE("inspect_image", "assistant_inspect_image", Effect.READ_REMOTE),
    PROFILE_INFORMATION("profile_information", "assistant_profile_information", Effect.READ_REMOTE),
    /** Arbitrary command: may change the remote system. */
    SSH_COMMAND("ssh_command", "assistant_ssh_command", Effect.CHANGE_REMOTE);

    companion object {
        fun fromWire(name: String): AssistantToolId? = entries.firstOrNull { it.wire == name }
    }
}
