package ru.adsrv.frpjump.settings

import ru.adsrv.frpjump.assistant.AssistantToolId
import java.util.concurrent.ConcurrentHashMap

/** A world for tests of [SessionGrants]: every target shares [period] and [fingerprint] unless overridden; [off] lists the tools out of effect (use [switchOff] to count a switch-off too). */
class FakeGrantWorld : GrantWorld {
    @Volatile var enabled = true
    @Volatile var disableVersion = 0L
    @Volatile var locked = false
    val offs = ConcurrentHashMap<AssistantToolId, Long>()
    @Volatile var period: Any? = 1L
    @Volatile var fingerprint: Any? = "f1"
    val periods = ConcurrentHashMap<GrantTarget, Any>()
    val fingerprints = ConcurrentHashMap<GrantTarget, Any>()
    val off: MutableSet<AssistantToolId> = ConcurrentHashMap.newKeySet()

    @Volatile var persistentFp: PersistentFp = PersistentFp.Known("v1:pf1")
    val persistentFps = ConcurrentHashMap<GrantTarget, PersistentFp>()
    val hiddenTargets: MutableSet<GrantTarget> = ConcurrentHashMap.newKeySet()
    override fun persistentFingerprint(target: GrantTarget): PersistentFp = persistentFps[target] ?: persistentFp
    override fun hidden(target: GrantTarget) = target in hiddenTargets

    override fun period(target: GrantTarget): Any? = periods[target] ?: period
    override fun fingerprint(target: GrantTarget): Any? = fingerprints[target] ?: fingerprint
    override fun enabled() = enabled
    override fun disableVersion() = disableVersion
    override fun inEffect(tool: AssistantToolId) = tool !in off
    override fun toolOffs(tool: AssistantToolId) = offs[tool] ?: 0L
    override fun locked() = locked

    /** The owner switches the tool off: it is out of effect and the off count grows. */
    fun switchOff(tool: AssistantToolId) { off += tool; offs.merge(tool, 1L, Long::plus) }
}
