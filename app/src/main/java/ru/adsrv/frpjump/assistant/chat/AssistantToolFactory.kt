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
        waits: WaitAdder? = null, limits: ru.adsrv.frpjump.settings.AssistantLimits = ru.adsrv.frpjump.settings.AssistantLimits(),
        sessions: AssistantSessions? = null, skills: SkillRegistry? = null, mqttRead: AssistantMqttRead? = null): List<AgentTool> {
        val web = AssistantWebTools(enabled, runtime.publicWeb, runtime.webSearch, active as? ImageInspector, publish)
        // terminal_input is offered only while the tabs are on: the model asks for terminal_tabs first.
        val off = AssistantToolId.switches.filter { !enabled[it] && enabled.offerable(it) }
        return web.tools + (if (enabled.profileInformation && profileInformation != null) listOf(
            AssistantProfileInformationTool(profileInformation, limits)) else emptyList()) +
            (if (enabled.mqttRead && mqttRead != null) listOf(AssistantMqttReadTool(mqttRead.operation, limits)) else emptyList()) +
            (if (enabled.tunnels && tunnels != null) listOf(StartTunnelTool(tunnels, limits.toolTimeoutSeconds * 1000L), StopTunnelTool(tunnels)) else emptyList()) +
            (if (enabled.terminalTabs && terminalTabs != null) listOf(AssistantTerminalTabsTool(terminalTabs, limits), AssistantTerminalReadTool(terminalTabs, limits), AssistantTerminalRenameTool(terminalTabs)) else emptyList()) +
            (if (enabled[AssistantToolId.TERMINAL_INPUT] && terminalTabs != null) listOf(AssistantTerminalInputTool(terminalTabs, limits)) else emptyList()) +
            (if (enabled[AssistantToolId.TERMINAL_OPEN] && sessions != null && terminalTabs != null) listOf(TerminalOpenTool(sessions, limits.sessionsAtOnce), TerminalShowTool(sessions), TerminalCloseTool(sessions)) else emptyList()) +
            skillTools(enabled, skills, limits) +
            (if (enabled[AssistantToolId.WAIT_FOR] && waits != null) listOf(WaitForTool(waits, limits, waitKinds(enabled, terminalTabs != null, tunnels != null))) else emptyList()) +
            (if (enabled.sshCommand && sshCommands != null) listOf(AssistantSshCommandTool(sshCommands, limits)) else emptyList()) +
            (if (off.isNotEmpty() && enabled.offer) listOf(AssistantRequestTool(off, declined)) else emptyList())
    }

    /** `read_skill` while at least one skill is relevant; the three writing tools with the owner's switch. */
    private fun skillTools(enabled: AssistantTools, skills: SkillRegistry?, limits: ru.adsrv.frpjump.settings.AssistantLimits): List<AgentTool> {
        if (skills == null) return emptyList()
        val read = if (skills.relevant(enabled).isNotEmpty()) listOf(ReadSkillTool(skills, enabled, listed = enabled[AssistantToolId.SKILLS_LIST])) else emptyList()
        val write = if (enabled[AssistantToolId.SKILLS_LIST]) listOf(SkillsListTool(skills, enabled, limits), SkillSaveTool(skills, limits), SkillDeleteTool(skills)) else emptyList()
        return read + write
    }

    /** The events a wait may be for now: time always; tab events while the terminal tabs are on, tunnel events while the profile information is on. */
    internal fun waitKinds(enabled: AssistantTools, tabs: Boolean, tunnels: Boolean): List<WaitKind> =
        WaitKind.entries.filter { kind -> if (kind.isTab) tabs && enabled.terminalTabs else if (kind.isTunnel) tunnels && enabled.profileInformation else true }
}
