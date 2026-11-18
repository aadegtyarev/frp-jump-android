package ru.adsrv.frpjump.settings

import ru.adsrv.frpjump.assistant.AssistantToolId

/** A world over the real [SettingsStore] (the assistant's switches and off count, as in the app); the tunnel period and the connection's fingerprint are the test's. */
class SettingsGrantWorld(private val settings: SettingsStore) : GrantWorld {
    @Volatile var period: Any? = 1L
    @Volatile var fingerprint: Any? = "stamp-1"
    @Volatile var persistentFp: PersistentFp = PersistentFp.Known("v1:pf1")
    @Volatile var hiddenNow = false
    override fun persistentFingerprint(target: GrantTarget): PersistentFp = persistentFp
    override fun hidden(target: GrantTarget) = hiddenNow
    override fun period(target: GrantTarget): Any? = period
    override fun fingerprint(target: GrantTarget): Any? = fingerprint
    override fun enabled() = settings.assistantAvailability.enabled
    override fun disableVersion() = settings.assistantAvailability.disableVersion
    override fun inEffect(tool: AssistantToolId) = settings.assistantAvailability.tools[tool]
    override fun toolOffs(tool: AssistantToolId) = settings.assistantAvailability.toolOffs[tool] ?: 0L
    @Volatile var locked = false
    override fun locked() = locked
}
