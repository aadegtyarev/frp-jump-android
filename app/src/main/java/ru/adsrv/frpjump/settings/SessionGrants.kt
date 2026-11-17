package ru.adsrv.frpjump.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.assistant.AssistantToolId

/**
 * What a session grant is bound to, read from the app at each lookup (a seam, so the rules are tested without Android).
 * Everything here is cheap and pure.
 */
interface GrantWorld {
    /**
     * An opaque token of the open period of the tunnel of [target]'s connection (equal only for the same open tunnel never hidden for Undo since);
     * null when it is not open (not planned, a Disconnect pending, hidden for Undo, or the connection is unknown). Never reused in the process.
     */
    fun period(target: GrantTarget): Any?

    /** Equal while nothing the owner can edit about the connection changed (profile name and colour excluded); null when it is gone. */
    fun fingerprint(target: GrantTarget): Any?

    /** Whether the assistant is on now. */
    fun enabled(): Boolean

    /** Counts the times the assistant was switched off. */
    fun disableVersion(): Long

    /** Whether the tool is in effect now (its switch, and for typing the terminal tabs too). */
    fun inEffect(tool: AssistantToolId): Boolean

    /** How many times the tool went from in effect to out of effect: a switch-off that a later switch-on does not undo. */
    fun toolOffs(tool: AssistantToolId): Long

    /**
     * Whether the app is locked or would lock the moment the owner returns (the lock's interval has run out while the app is out of sight);
     * false with the app lock off. Grants do not live while this holds, and it is read at every use, not only when it changes.
     */
    fun locked(): Boolean

    /** A world with nothing open: no grant can be recorded in it. */
    object None : GrantWorld {
        override fun period(target: GrantTarget): Long? = null
        override fun fingerprint(target: GrantTarget): Any? = null
        override fun enabled() = false
        override fun disableVersion() = 0L
        override fun inEffect(tool: AssistantToolId) = false
        override fun toolOffs(tool: AssistantToolId) = 0L
        override fun locked() = false
    }
}

/**
 * What a grant is born with, captured BEFORE the owner is asked, so an edit, a close or a switch-off during the wait makes the grant born stale.
 * Not a data class: its fields are never printed.
 */
class GrantStamp internal constructor(internal val period: Any, internal val fingerprint: Any, internal val disableVersion: Long,
    /** The switch-off count of each grantable tool when the stamp was taken: a tool switched off since (and on again) is not covered. */
    internal val toolOffs: Map<AssistantToolId, Long>)

/**
 * "For session" grants, in memory only, one per app (not per conversation). A grant is (class, connection) with the open period of the connection's
 * tunnel, the connection's fingerprint, the assistant's off-switch count and the grantable tools of the class that were in effect when the owner
 * answered. A call is covered only while all of that still holds. Every lookup re-checks it, so a stale grant can never be used, and [sweep]
 * (the app-level cleaner) drops stale ones as soon as they go stale, so [observe] never shows one. Never persisted, never in history, logs or receipts.
 * Thread-safe.
 */
class SessionGrants(private val world: GrantWorld = GrantWorld.None) {
    private class Entry(val stamp: GrantStamp, val tools: Set<AssistantToolId>)

    private val entries = HashMap<GrantKey, Entry>()
    private val live = MutableStateFlow<Set<GrantTarget>>(emptySet())

    /** The connections that have a grant now (after the last change); for the connection card. */
    fun observe(): StateFlow<Set<GrantTarget>> = live

    /** The stamp to bind a grant for [target] to, or null when none can be given now (no open period, the connection is gone, the assistant is off). */
    fun stamp(target: GrantTarget): GrantStamp? {
        if (!world.enabled() || world.locked()) return null
        val period = world.period(target) ?: return null
        val fingerprint = world.fingerprint(target) ?: return null
        return GrantStamp(period, fingerprint, world.disableVersion(), AssistantToolId.entries.filter { it.grantable }.associateWith { world.toolOffs(it) })
    }

    /** Whether [tool] is in effect now. */
    fun inEffect(tool: AssistantToolId): Boolean = world.inEffect(tool)

    /** The grantable tools of [tool]'s class that are in effect now: what a For session answer on a card of [tool] covers. Empty for a tool that is not grantable. */
    fun coverableBy(tool: AssistantToolId): Set<AssistantToolId> =
        if (!tool.grantable) emptySet() else AssistantToolId.entries.filter { it.grantable && it.actionClass == tool.actionClass && world.inEffect(it) }.toSet()

    /** Whether a grant covers this call of [tool] now. A grant that went stale is dropped on the way. */
    fun covers(tool: AssistantToolId, key: GrantKey): Boolean = synchronized(entries) {
        // A tool that is not grantable, or of another class than the key's, is never covered (fail closed).
        if (!tool.grantable || tool.actionClass != key.actionClass || !key.actionClass.grantable) return false
        val entry = entries[key] ?: return false
        if (!current(key.target, entry.stamp)) { entries.remove(key); publish(); return false }
        if (tool !in entry.tools) return false
        if (!world.inEffect(tool) || !untouched(tool, entry.stamp)) {
            // Switched off (now, or at any moment since, even if it is on again): it leaves the grant for good; switching it on later does not bring it back.
            narrow(key, entry, entry.tools - tool)
            return false
        }
        true
    }

    /**
     * Records the owner's For session answer for [tool] on [key], bound to [stamp]. [offered] is what the card said it covers (what [coverableBy]
     * gave when the card was raised): the grant covers those of them that are still in effect and were not switched off since the stamp, never more
     * than the owner saw. Only for a grantable tool among them, and only while the period and the fingerprint are still the stamp's (else nothing is
     * recorded: the answer acts as Allow once). A repeated answer replaces the entry.
     */
    fun record(key: GrantKey, tool: AssistantToolId, stamp: GrantStamp, offered: Set<AssistantToolId>): Boolean = synchronized(entries) {
        if (!tool.grantable || tool.actionClass != key.actionClass || !key.actionClass.grantable) return false
        if (!current(key.target, stamp)) return false
        val tools = offered.filter { it.grantable && it.actionClass == key.actionClass && world.inEffect(it) && untouched(it, stamp) }.toSet()
        if (tool !in tools) return false
        entries[key] = Entry(stamp, tools)
        publish()
        true
    }

    /** Drops every grant that is stale now and the tools that are out of effect; an emptied grant goes. */
    fun sweep() = synchronized(entries) {
        for ((key, entry) in entries.toList()) {
            if (!current(key.target, entry.stamp)) { entries.remove(key); continue }
            val tools = entry.tools.filter { world.inEffect(it) && untouched(it, entry.stamp) }.toSet()
            if (tools != entry.tools) narrow(key, entry, tools)
        }
        publish()
    }

    /** "Forget": every grant of one connection at once. */
    fun forgetConnection(profileId: String, connectionId: String) = synchronized(entries) {
        entries.keys.removeAll { it.target == GrantTarget(profileId, connectionId) }; publish()
    }

    /** Every grant ends (history cleared, the app locked). */
    fun clear() = synchronized(entries) { entries.clear(); publish() }

    /** The keys held now (stale ones included until looked up or swept); for tests. */
    fun snapshot(): Set<GrantKey> = synchronized(entries) { entries.keys.toSet() }

    private fun narrow(key: GrantKey, entry: Entry, tools: Set<AssistantToolId>) {
        if (tools.isEmpty()) entries.remove(key) else entries[key] = Entry(entry.stamp, tools)
        publish()
    }

    /** The tool has not been switched off since the stamp was taken. */
    private fun untouched(tool: AssistantToolId, stamp: GrantStamp): Boolean = world.toolOffs(tool) == stamp.toolOffs[tool]

    private fun current(target: GrantTarget, stamp: GrantStamp): Boolean =
        world.enabled() && !world.locked() && world.disableVersion() == stamp.disableVersion && world.period(target) == stamp.period && world.fingerprint(target) == stamp.fingerprint

    private fun publish() { live.value = entries.keys.mapTo(HashSet()) { it.target } }
}

/**
 * Keeps [grants] honest with no conversation alive: every change of what a grant is bound to sweeps it, and the app lock ends all of them.
 * [changes] emits whenever the world may have changed; [locked] is the app lock's state.
 */
class SessionGrantCleaner(grants: SessionGrants, scope: CoroutineScope, changes: Flow<*>, locked: Flow<Boolean>) {
    init {
        scope.launch { changes.collect { grants.sweep() } }
        scope.launch { locked.collect { if (it) grants.clear() } }
    }
}
