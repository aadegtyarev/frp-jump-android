package ru.adsrv.frpjump.assistant.chat

import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.*
import ru.adsrv.frpjump.profiles.ProfileInformation
import ru.adsrv.frpjump.settings.AssistantTools

/**
 * One Send's fixed tool list, in catalog order: web search, read page, inspect image, profile information, SSH command; then the
 * service tool `request_tool` while at least one tool is off ([declined] are the wire names the owner declined in this conversation).
 */
internal object AssistantToolFactory {
    fun create(enabled: AssistantTools, runtime: AssistantChatRuntime, active: AgentProvider, profileInformation: ProfileInformation?,
        sshCommands: AssistantSshCommands?, publish: (ChatSource) -> Unit,
        declined: Set<String> = emptySet()): List<AgentTool> {
        val web = AssistantWebTools(enabled, runtime.publicWeb, runtime.webSearch, active as? ImageInspector, publish)
        val off = AssistantToolId.entries.filter { !enabled[it] }
        return web.tools + (if (enabled.profileInformation && profileInformation != null) listOf(
            AssistantProfileInformationTool(profileInformation)) else emptyList()) +
            (if (enabled.sshCommand && sshCommands != null) listOf(AssistantSshCommandTool(sshCommands)) else emptyList()) +
            (if (off.isNotEmpty() && enabled.offer) listOf(AssistantRequestTool(off, declined)) else emptyList())
    }
}
