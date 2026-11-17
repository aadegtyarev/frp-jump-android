package ru.adsrv.frpjump.assistant

import ru.adsrv.frpjump.settings.GrantTarget
import ru.adsrv.frpjump.settings.GrantWorld
import ru.adsrv.frpjump.settings.SettingsStore
import ru.adsrv.frpjump.ssh.SshTargets

/** What the app's session grants are bound to, read live from the saved connections, their tunnels and the assistant's settings. */
class AppGrantWorld(private val targets: SshTargets, private val settings: SettingsStore) : GrantWorld {
    override fun period(target: GrantTarget): Long? = targets.period(target.profileId, target.connectionId)
    override fun fingerprint(target: GrantTarget): Any? = targets.fingerprint(target.profileId, target.connectionId)
    override fun enabled(): Boolean = settings.assistantAvailability.enabled
    override fun disableVersion(): Long = settings.assistantAvailability.disableVersion
    override fun inEffect(tool: AssistantToolId): Boolean = settings.assistantAvailability.tools[tool]
}
