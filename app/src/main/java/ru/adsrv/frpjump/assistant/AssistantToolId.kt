package ru.adsrv.frpjump.assistant

import ru.adsrv.frpjump.ops.ActionClass
import ru.adsrv.frpjump.ops.Effect

/**
 * The assistant's tools: the only place with a tool's wire name and persisted setting key.
 * Declaration order is the order of the tool list (the Tools screen shows the [switches]); the order of `tools` in provider requests must match it (pinned by tests).
 */
enum class AssistantToolId(val wire: String, val settingKey: String, val effect: Effect, val actionClass: ActionClass, val ownsSwitch: Boolean = true,
    /** Whether a call of this tool, when its effect changes something, goes through the owner's confirmation. False only for [TERMINAL_RENAME]. */
    val confirms: Boolean = true,
    /**
     * Whether "For session" may cover a call of this tool (true for [SSH_COMMAND], [TERMINAL_INPUT] and [MQTT_PUBLISH]). A property of the tool, not of its
     * class: tools sharing a class with a grantable one are not covered by its grant.
     */
    val grantable: Boolean = false) {
    WEB_SEARCH("web_search", "assistant_search", Effect.READ_REMOTE, ActionClass.PUBLIC_READ),
    READ_PAGE("read_page", "assistant_read_page", Effect.READ_REMOTE, ActionClass.PUBLIC_READ),
    INSPECT_IMAGE("inspect_image", "assistant_inspect_image", Effect.READ_REMOTE, ActionClass.PUBLIC_READ),
    PROFILE_INFORMATION("profile_information", "assistant_profile_information", Effect.READ_REMOTE, ActionClass.CONNECTION_READ),
    HTTP_GET("http_get", "assistant_http_get", Effect.READ_REMOTE, ActionClass.CONNECTION_READ),
    MQTT_READ("mqtt_read", "assistant_mqtt_read", Effect.READ_REMOTE, ActionClass.CONNECTION_READ),
    MQTT_PUBLISH("mqtt_publish", "assistant_mqtt_publish", Effect.CHANGE_REMOTE, ActionClass.MQTT_PUBLISH, grantable = true),
    /**
     * Starts one saved tunnel as the owner's Start button does and waits until the device takes connections. The switch of the two tunnel tools
     * (`assistant_tunnels`, default off). It changes only this app and runs without a question ([confirms] false, as [TERMINAL_RENAME]).
     */
    START_TUNNEL("start_tunnel", "assistant_tunnels", Effect.CHANGE_LOCAL, ActionClass.APP_CHANGE, confirms = false),
    /**
     * Stops one saved tunnel as the owner's Stop button does (its terminal tabs close; tmux sessions on the device stay), at once and without
     * the owner's 5 s Undo: the confirmation card is the check. No switch of its own: on and off with [START_TUNNEL]. Asks as every changing tool does.
     */
    STOP_TUNNEL("stop_tunnel", "assistant_tunnels", Effect.CHANGE_LOCAL, ActionClass.APP_CHANGE, ownsSwitch = false),
    /** The switch of the two terminal tools: it lists the owner's open tabs. */
    TERMINAL_TABS("terminal_tabs", "assistant_terminal_tabs", Effect.READ_REMOTE, ActionClass.CONNECTION_READ),
    /** Reads one tab. No switch of its own: it is on and off with [TERMINAL_TABS] (same setting key), and the Tools screen and `request_tool` show one entry. */
    TERMINAL_READ("terminal_read", "assistant_terminal_tabs", Effect.READ_REMOTE, ActionClass.CONNECTION_READ, ownsSwitch = false),
    /**
     * Renames one open tab: the owner's own "Rename…", by the assistant. No switch of its own (on and off with [TERMINAL_TABS], like [TERMINAL_READ]).
     * Only the tab's label in this app changes (app-local, never the device or the connection): a local change that asks for no confirmation
     * ([confirms] false): the owner can undo it from the tab's menu, and every card, call line, receipt and the tab's chip show the name with the tab's user and device.
     */
    TERMINAL_RENAME("terminal_rename", "assistant_terminal_tabs", Effect.CHANGE_LOCAL, ActionClass.APP_CHANGE, ownsSwitch = false, confirms = false),
    /**
     * Types into one open tab. Its own switch (default off), but effective only while [TERMINAL_TABS] is on too: the tab list is how
     * the model finds a tab (see [ru.adsrv.frpjump.settings.AssistantTools.get]).
     */
    TERMINAL_INPUT("terminal_input", "assistant_terminal_input", Effect.CHANGE_REMOTE, ActionClass.CONNECTION_CHANGE, grantable = true),
    /**
     * Opens a terminal session for the assistant itself: a tmux session on a saved SSH tunnel's device, with no tab. The switch of the three session tools
     * (`assistant_sessions`, default off), in effect only while the terminal tabs and typing are on too. It logs in with the tunnel's saved login and changes
     * nothing on the device but that tmux session, and the owner sees it in the chat and in Sessions: it runs without a question ([confirms] false).
     */
    TERMINAL_OPEN("terminal_open", "assistant_sessions", Effect.CHANGE_REMOTE, ActionClass.CONNECTION_CHANGE, confirms = false),
    /** Asks the owner to act in one of the assistant's sessions (a card, a notification): it only asks, so no question ([confirms] false). No switch of its own. */
    TERMINAL_SHOW("terminal_show", "assistant_sessions", Effect.CHANGE_LOCAL, ActionClass.APP_CHANGE, ownsSwitch = false, confirms = false),
    /** Ends one session the assistant opened, with anything running in it. Asks as every changing tool does. No switch of its own. */
    TERMINAL_CLOSE("terminal_close", "assistant_sessions", Effect.CHANGE_REMOTE, ActionClass.CONNECTION_CHANGE, ownsSwitch = false),
    /**
     * Loads one skill by name: the owner's know-how (built in or his own). No switch and no confirmation: reading the owner's own text; it is offered
     * only while at least one skill is relevant (see the skills section of the system prompt).
     */
    READ_SKILL("read_skill", "assistant_skills", Effect.READ_LOCAL, ActionClass.APP_READ, ownsSwitch = false),
    /** Lists all skills, relevant or not. The switch of the three skill-writing tools (`assistant_skills`, default off). */
    SKILLS_LIST("skills_list", "assistant_skills", Effect.READ_LOCAL, ActionClass.APP_READ),
    /** Creates or replaces one owner skill. Asks as every changing tool does, the card showing the whole text. No switch of its own. */
    SKILL_SAVE("skill_save", "assistant_skills", Effect.CHANGE_LOCAL, ActionClass.APP_CHANGE, ownsSwitch = false),
    /** Deletes one owner skill. Asks as every changing tool does. No switch of its own. */
    SKILL_DELETE("skill_delete", "assistant_skills", Effect.CHANGE_LOCAL, ActionClass.APP_CHANGE, ownsSwitch = false),
    /**
     * Waits in the background for one event (a tab, a tunnel or the clock) and starts a new turn when it happens. Its own switch (default off);
     * it changes only this app (the wait is visible in the chat and the owner can cancel it), so it runs without a question ([confirms] false).
     */
    WAIT_FOR("wait_for", "assistant_waits", Effect.CHANGE_LOCAL, ActionClass.APP_CHANGE, confirms = false),
    /** Arbitrary command: may change the remote system. */
    SSH_COMMAND("ssh_command", "assistant_ssh_command", Effect.CHANGE_REMOTE, ActionClass.CONNECTION_CHANGE, grantable = true);

    init { require(!grantable || actionClass.grantable) { "$wire: a grantable tool needs a grantable class" } }

    companion object {
        fun fromWire(name: String): AssistantToolId? = entries.firstOrNull { it.wire == name }

        /** The tools that have a switch of their own (one entry per switch, in catalog order). */
        val switches: List<AssistantToolId> get() = entries.filter { it.ownsSwitch }
    }
}
