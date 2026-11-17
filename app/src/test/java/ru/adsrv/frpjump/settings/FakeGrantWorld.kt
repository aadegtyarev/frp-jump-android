package ru.adsrv.frpjump.settings

import ru.adsrv.frpjump.assistant.AssistantToolId
import java.util.concurrent.ConcurrentHashMap

/** A world for tests of [SessionGrants]: every target shares [period] and [fingerprint] unless overridden; [off] lists the tools out of effect. */
class FakeGrantWorld : GrantWorld {
    @Volatile var enabled = true
    @Volatile var disableVersion = 0L
    @Volatile var period: Long? = 1L
    @Volatile var fingerprint: Any? = "f1"
    val periods = ConcurrentHashMap<GrantTarget, Long>()
    val fingerprints = ConcurrentHashMap<GrantTarget, Any>()
    val off: MutableSet<AssistantToolId> = ConcurrentHashMap.newKeySet()

    override fun period(target: GrantTarget): Long? = periods[target] ?: period
    override fun fingerprint(target: GrantTarget): Any? = fingerprints[target] ?: fingerprint
    override fun enabled() = enabled
    override fun disableVersion() = disableVersion
    override fun inEffect(tool: AssistantToolId) = tool !in off
}
