package ru.adsrv.frpjump.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.assistant.AssistantToolId

/** How a "For this connection" answer ended. */
enum class RecordResult {
    /** Written. */
    RECORDED,
    /** Nothing was written (the answer acts as Allow once): not grantable, stale, or a tool switched off during the wait. */
    NOT_RECORDED,
    /** The write failed: the call is allowed once and the owner is told (nothing is kept in memory instead). */
    SAVE_FAILED,
}

/**
 * The persistent "For this connection" permission (stage E3). One record per (class, connection) in the committed [SettingsStore] policy
 * (`assistant_confirmations.overrides`): the in-memory state is not a second source, it reads the committed value live, as the policy is read.
 * A record covers a call only while all of this holds, re-checked at every use: the assistant is on, the app is not locked by the interval (no turn
 * uses ANY grant then; the record is kept, not deleted), the tool is grantable, of the record's class, in the record's tools and in effect now, the
 * connection is not hidden for Undo, and its [PersistentFp] is the record's. A Mismatch or Gone ends the record (it is revoked in memory at once and deleted
 * by the next [sweep], off Main); Unknown asks and keeps it.
 *
 * Revocation: Forget, Forget profile and a Mismatch or Gone stop the USE of the record at once, in memory ([revoke]), before and whatever the write does; the
 * revocation is cleared only once the committed record is really gone, a failed delete is retried by [sweep], and a revoked record never covers meanwhile.
 * A [Stamp] carries the revocation count, so an answer that was in flight when the owner forgot cannot write the record back. Nothing here is read from or
 * written to disk on the caller's thread except where noted: callers run [stamp], [covers] and [record] off Main (the gate does).
 */
class ConnectionGrants(private val settings: SettingsStore, private val world: GrantWorld, private val now: () -> Long = System::currentTimeMillis) {
    /**
     * What a record is born with, captured BEFORE the owner is asked, so an edit, a close, a switch-off or an assistant off during the wait makes the
     * answer act as Allow once. Not a data class: its fields are never printed.
     */
    class Stamp internal constructor(internal val period: Any, internal val fp: String, internal val disableVersion: Long, internal val toolOffs: Map<AssistantToolId, Long>,
        internal val revocations: Long)

    private val revocations = AtomicLong()
    private val revokedTargets = MutableStateFlow<Set<GrantTarget>>(emptySet())
    private val revokedProfiles = MutableStateFlow<Set<String>>(emptySet())
    private fun isRevoked(target: GrantTarget) = target in revokedTargets.value || target.profileId in revokedProfiles.value

    /** The stamp to bind a record for [target] to, or null when none can be given now (assistant off, app locked, no open tunnel period, fingerprint not Known). */
    fun stamp(target: GrantTarget): Stamp? {
        if (!world.enabled() || world.locked()) return null
        val period = world.period(target) ?: return null
        val fp = (world.persistentFingerprint(target) as? PersistentFp.Known)?.fp ?: return null
        return Stamp(period, fp, world.disableVersion(), AssistantToolId.entries.filter { it.grantable }.associateWith { world.toolOffs(it) }, revocations.get())
    }

    /** Whether a record covers this call of [tool] now. A record that is Mismatch or Gone is revoked on the way (deleted by [sweep]); an Unknown one is kept and does not cover. */
    fun covers(tool: AssistantToolId, key: GrantKey): Boolean {
        // Forgotten (or ended) and not yet deleted: never used, whatever the write did or will do.
        if (isRevoked(key.target)) return false
        // A tool that is not grantable, or of another class than the key's, is never covered (fail closed).
        if (!tool.grantable || tool.actionClass != key.actionClass || !key.actionClass.grantable) return false
        // While the lock's interval has run out NO grant is used; the record stays.
        if (!world.enabled() || world.locked()) return false
        val record = settings.assistantConfirmations.recordOf(key.actionClass, key.target) ?: return false
        if (record.mode != ConfirmationMode.OFF || tool.wire !in record.tools || !world.inEffect(tool)) return false
        if (world.hidden(key.target)) return false
        return when (val fp = world.persistentFingerprint(key.target)) {
            is PersistentFp.Known -> if (fp.fp == record.fp) true else { markEnded(key.target); false }
            PersistentFp.Gone -> { markEnded(key.target); false }
            PersistentFp.Unknown -> false
        }
    }

    /**
     * Records the owner's "For this connection" answer for [tool] on [key], bound to [stamp]. [offered] is what the card said it covers: the record covers those
     * of them that are grantable, of the class, in effect and not switched off since the stamp, decided against the committed settings in the same write
     * (never more than the owner saw; a tool that joins the class later is not covered). Nothing is recorded (the answer acts as Allow once) when the period or
     * the fingerprint is no longer the stamp's, the app is locked, the assistant was switched off or the tool was switched off since. A repeated answer replaces
     * the record of the same (class, connection).
     */
    suspend fun record(key: GrantKey, tool: AssistantToolId, stamp: Stamp, offered: Set<AssistantToolId>): RecordResult {
        if (!tool.grantable || tool.actionClass != key.actionClass || !key.actionClass.grantable) return RecordResult.NOT_RECORDED
        if (!world.enabled() || world.locked() || world.disableVersion() != stamp.disableVersion || world.period(key.target) != stamp.period) return RecordResult.NOT_RECORDED
        // The owner forgot (anything) since the question was asked: the answer must not write a record back.
        if (revocations.get() != stamp.revocations) return RecordResult.NOT_RECORDED
        if ((world.persistentFingerprint(key.target) as? PersistentFp.Known)?.fp != stamp.fp) return RecordResult.NOT_RECORDED
        return withContext(Dispatchers.IO) {
            try {
                val written = settings.recordConnectionGrant { current, availability ->
                    if (!current.assistantEnabled || availability.disableVersion != stamp.disableVersion) return@recordConnectionGrant null
                    val tools = offered.filter { it.grantable && it.actionClass == key.actionClass && availability.tools[it] && (availability.toolOffs[it] ?: 0L) == stamp.toolOffs[it] }
                    if (tool !in tools) null
                    else ConfirmationOverride(ConfirmationScope("target", key.target.profileId, key.target.connectionId), key.actionClass.wire, ConfirmationMode.OFF, stamp.fp,
                        tools.map { it.wire }.sorted(), now())
                }
                // A fresh answer is a new decision: it replaces whatever was revoked for this connection.
                if (written) { revokedTargets.update { it - key.target }; RecordResult.RECORDED } else RecordResult.NOT_RECORDED
            } catch (e: CancellationException) { throw e } catch (_: Exception) { RecordResult.SAVE_FAILED }
        }
    }

    /**
     * Stops the USE of the records of one connection at once, in memory only (no disk, safe on Main): what the Forget tap calls before it starts the write.
     * It lasts until the committed record is really gone ([forget] or [sweep]).
     */
    fun revoke(profileId: String, connectionId: String) { revocations.incrementAndGet(); revokedTargets.update { it + GrantTarget(profileId, connectionId) } }

    /** [revoke] for every connection of a profile. */
    fun revokeProfile(profileId: String) { revocations.incrementAndGet(); revokedProfiles.update { it + profileId } }

    private fun markEnded(target: GrantTarget) { revokedTargets.update { it + target } }

    /**
     * "Forget": the records of one connection, at once for use ([revoke]) and then for good (a write, on the caller's thread: call it off Main). A failed write
     * throws, the connection stays revoked, and [sweep] retries the delete.
     */
    fun forget(profileId: String, connectionId: String) {
        revoke(profileId, connectionId)
        settings.update { it.withPolicy { p -> p.withoutConnection(GrantTarget(profileId, connectionId)) } }
        finishRevocations()
    }

    /** Every record of [profileId] (Forget profile, re-enrolling); revoked first, then written. A failed write throws and the profile stays revoked. */
    fun forgetProfile(profileId: String) {
        revokeProfile(profileId)
        settings.update { it.withPolicy { p -> p.withoutProfile(profileId) } }
        finishRevocations()
    }

    private fun shown(overrides: List<ConfirmationOverride>) = overrides.filter { r -> r.target?.let { !isRevoked(it) } == true }

    /** The connections that have a record, with the time it was given (for the connection card's line); follows the committed settings, never shows a revoked one. */
    fun observe(): Flow<Map<GrantTarget, Long>> = combine(settings.settings, revokedTargets, revokedProfiles) { current, _, _ ->
        shown(current.assistantConfirmations.overrides).mapNotNull { r -> r.target?.let { it to r.at } }.toMap()
    }.distinctUntilChanged()

    /** The connections that have a record now (a revoked one is not shown). */
    fun current(): Map<GrantTarget, Long> = shown(settings.assistantConfirmations.overrides).mapNotNull { r -> r.target?.let { it to r.at } }.toMap()

    /**
     * Deletes the records that are revoked, Mismatch or Gone now, as soon as a change is seen (also with no conversation alive): off Main, never in a call.
     * An Unknown one stays. A failed delete is retried at the next change; the revocation keeps the record unused meanwhile and is cleared only once the
     * committed record is gone.
     */
    fun sweep() {
        val records = settings.assistantConfirmations.overrides
        if (records.isNotEmpty()) {
            val dead = records.filter { record ->
                val target = record.target ?: return@filter true
                if (isRevoked(target)) return@filter true
                when (val fp = world.persistentFingerprint(target)) {
                    is PersistentFp.Known -> fp.fp != record.fp
                    PersistentFp.Gone -> true
                    PersistentFp.Unknown -> false
                }
            }
            if (dead.isNotEmpty()) remove(dead)
        }
        finishRevocations()
    }

    /** A revocation ends once no committed record is left for it. */
    private fun finishRevocations() {
        val left = settings.assistantConfirmations.overrides
        revokedTargets.update { set -> set.filterTo(HashSet()) { t -> left.any { it.target == t } } }
        revokedProfiles.update { set -> set.filterTo(HashSet()) { p -> left.any { it.scope.id == p } } }
    }

    private fun remove(dead: List<ConfirmationOverride>) {
        try {
            settings.update { s -> s.withPolicy { p -> p.copy(overrides = p.overrides.filterNot { r -> dead.any { it == r } }) } }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { /* retried at the next change; the revocation keeps it unused */ }
    }

    private fun AppSettings.withPolicy(change: (ConfirmationPolicy) -> ConfirmationPolicy) = copy(assistantConfirmations = change(assistantConfirmations))
}

/** Keeps [grants] honest with no conversation alive: every change of what a record is bound to sweeps it, off Main. */
class ConnectionGrantCleaner(grants: ConnectionGrants, scope: CoroutineScope, changes: Flow<*>) {
    init { scope.launch(Dispatchers.IO) { changes.conflate().collect { grants.sweep() } } }
}
