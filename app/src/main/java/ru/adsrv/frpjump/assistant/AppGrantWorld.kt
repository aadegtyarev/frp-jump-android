package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import ru.adsrv.frpjump.lock.AppLock
import ru.adsrv.frpjump.settings.GrantTarget
import ru.adsrv.frpjump.settings.GrantWorld
import ru.adsrv.frpjump.settings.PersistentFp
import ru.adsrv.frpjump.settings.ConnectionGrantCleaner
import ru.adsrv.frpjump.settings.ConnectionGrants
import ru.adsrv.frpjump.settings.SessionGrantCleaner
import ru.adsrv.frpjump.settings.SessionGrants
import ru.adsrv.frpjump.settings.SettingsStore
import ru.adsrv.frpjump.settings.assistantTools
import ru.adsrv.frpjump.ssh.SshTargets

/**
 * What the app's session grants are bound to, read live from the saved connections, their tunnels, the assistant's settings and the app lock.
 * The lock counts as engaged from the moment the app would lock on return ("Lock after" run out while out of sight), not only once the screen shows it.
 */
class AppGrantWorld(private val targets: SshTargets, private val settings: SettingsStore, private val lock: AppLock,
    private val mqttPrincipal: (GrantTarget) -> Pair<Any, String?>? = { null }) : GrantWorld {
    private fun isMqtt(target: GrantTarget) = targets.labels(target.profileId, target.connectionId)?.second?.kind == ru.adsrv.frpjump.ports.PortKind.MQTT
    override fun period(target: GrantTarget): Any? {
        val tunnel = targets.period(target.profileId, target.connectionId) ?: return null
        return if (isMqtt(target)) listOf(tunnel, mqttPrincipal(target)?.first ?: return null) else tunnel
    }
    override fun fingerprint(target: GrantTarget): Any? = targets.fingerprint(target.profileId, target.connectionId)
    override fun persistentFingerprint(target: GrantTarget): PersistentFp {
        val base = targets.persistentFingerprint(target.profileId, target.connectionId)
        if (!isMqtt(target) || base !is PersistentFp.Known) return base
        val principal = mqttPrincipal(target) ?: return base
        val configured = try { targets.rememberedMqttUser(target.profileId, target.connectionId) } catch (_: Exception) { return PersistentFp.Unknown }
        if (principal.second != configured || mqttPrincipal(target)?.first !== principal.first) return PersistentFp.Unknown
        return if (targets.persistentFingerprint(target.profileId, target.connectionId) == base) base else PersistentFp.Unknown
    }
    override fun hidden(target: GrantTarget): Boolean = targets.hidden(target.profileId, target.connectionId)
    override fun enabled(): Boolean = settings.assistantAvailability.enabled
    override fun disableVersion(): Long = settings.assistantAvailability.disableVersion
    override fun inEffect(tool: AssistantToolId): Boolean = settings.assistantAvailability.tools[tool]
    override fun toolOffs(tool: AssistantToolId): Long = settings.assistantAvailability.toolOffs[tool] ?: 0L
    override fun locked(): Boolean = settings.settings.value.let { lock.engaged(it.appLock, it.appLockAfter.ms) }
}

/** How often the cleaner looks again although nothing changed: the lock's interval runs out with no event. */
const val GRANT_TICK_MS = 5_000L

/**
 * THE one way the app's session grants are built (the application and the tests both use it): the grants over [AppGrantWorld] and the cleaner that
 * needs no conversation. Every change of what a grant is bound to sweeps it (connections, profiles, tunnel periods, Undo, logins, host keys, the
 * assistant's off switch, the tools' switches, the lock setting), [tick] covers the lock interval running out, and the app lock ending clears all.
 */
fun appSessionGrants(targets: SshTargets, settings: SettingsStore, lock: AppLock, scope: CoroutineScope,
    tick: Flow<*> = flow { while (true) { emit(Unit); delay(GRANT_TICK_MS) } },
    mqttPrincipal: (GrantTarget) -> Pair<Any, String?>? = { null }, mqttChanges: Flow<*> = kotlinx.coroutines.flow.emptyFlow<Any?>()): SessionGrants {
    val grants = SessionGrants(AppGrantWorld(targets, settings, lock, mqttPrincipal))
    SessionGrantCleaner(grants, scope,
        changes = merge(targets.changes(), settings.assistantDisables,
            settings.settings.map { Triple(it.assistantEnabled to it.assistantTools(), it.appLock, it.appLockAfter) }.distinctUntilChanged(), tick, mqttChanges),
        locked = lock.locked)
    return grants
}

/**
 * THE one way the app's persistent "For this connection" permissions are built: [ConnectionGrants] over the same [AppGrantWorld] and a cleaner that needs no
 * conversation. Every change of what a record is bound to (connections, profiles, logins, host keys, enrollments) and the tick sweep it, off Main; a read that
 * cannot tell (Unknown) keeps it. The lock does not delete a record (it only stops its use).
 */
fun appConnectionGrants(targets: SshTargets, settings: SettingsStore, lock: AppLock, scope: CoroutineScope,
    tick: Flow<*> = flow { while (true) { emit(Unit); delay(GRANT_TICK_MS) } },
    mqttPrincipal: (GrantTarget) -> Pair<Any, String?>? = { null }, mqttChanges: Flow<*> = kotlinx.coroutines.flow.emptyFlow<Any?>()): ConnectionGrants {
    val grants = ConnectionGrants(settings, AppGrantWorld(targets, settings, lock, mqttPrincipal))
    ConnectionGrantCleaner(grants, scope, merge(targets.changes(), tick, mqttChanges))
    return grants
}
