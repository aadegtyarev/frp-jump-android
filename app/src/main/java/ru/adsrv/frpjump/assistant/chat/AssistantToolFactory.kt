package ru.adsrv.frpjump.assistant.chat

import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.*
import ru.adsrv.frpjump.profiles.ProfileInformation
import ru.adsrv.frpjump.settings.AssistantTools

/**
 * One Send's fixed tool list, in catalog order: web search, read page, inspect image, profile information, terminal tabs, terminal read (one switch), terminal input (own switch, in effect only with the tabs on), SSH command; then the
 * service tool `request_tool` while at least one tool is off ([declined] are the wire names the owner declined in this conversation).
 */
internal object AssistantToolFactory {
    fun create(enabled: AssistantTools, runtime: AssistantChatRuntime, active: AgentProvider, profileInformation: ProfileInformation?,
        sshCommands: AssistantSshCommands?, publish: (ChatSource) -> Unit,
        declined: Set<String> = emptySet(), terminalTabs: AssistantTerminalTabs? = null, tunnels: AssistantTunnels? = null,
        toolTimeoutMs: Long = ru.adsrv.frpjump.agent.AgentLimits().toolTimeoutMs,
        waits: WaitAdder? = null, limits: ru.adsrv.frpjump.settings.AssistantLimits = ru.adsrv.frpjump.settings.AssistantLimits()): List<AgentTool> {
        val web = AssistantWebTools(enabled, runtime.publicWeb, runtime.webSearch, active as? ImageInspector, publish)
        // terminal_input is offered only while the tabs are on: the model asks for terminal_tabs first.
        val off = AssistantToolId.switches.filter { !enabled[it] && enabled.offerable(it) }
        return web.tools + (if (enabled.profileInformation && profileInformation != null) listOf(
            AssistantProfileInformationTool(profileInformation)) else emptyList()) +
            (if (enabled.tunnels && tunnels != null) listOf(StartTunnelTool(tunnels, toolTimeoutMs), StopTunnelTool(tunnels)) else emptyList()) +
            (if (enabled.terminalTabs && terminalTabs != null) listOf(AssistantTerminalTabsTool(terminalTabs), AssistantTerminalReadTool(terminalTabs), AssistantTerminalRenameTool(terminalTabs)) else emptyList()) +
            (if (enabled[AssistantToolId.TERMINAL_INPUT] && terminalTabs != null) listOf(AssistantTerminalInputTool(terminalTabs, limits.afterInputSeconds)) else emptyList()) +
            (if (enabled[AssistantToolId.WAIT_FOR] && waits != null) listOf(WaitForTool(waits, limits, waitKinds(enabled, terminalTabs != null, tunnels != null))) else emptyList()) +
            (if (enabled.sshCommand && sshCommands != null) listOf(AssistantSshCommandTool(sshCommands)) else emptyList()) +
            (if (off.isNotEmpty() && enabled.offer) listOf(AssistantRequestTool(off, declined)) else emptyList())
    }

    /** The events a wait may be for now: time always; tab events while the terminal tabs are on, tunnel events while the profile information is on. */
    internal fun waitKinds(enabled: AssistantTools, tabs: Boolean, tunnels: Boolean): List<WaitKind> =
        WaitKind.entries.filter { kind -> if (kind.isTab) tabs && enabled.terminalTabs else if (kind.isTunnel) tunnels && enabled.profileInformation else true }
}
