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
import ru.adsrv.frpjump.settings.SessionGrantCleaner
import ru.adsrv.frpjump.settings.SessionGrants
import ru.adsrv.frpjump.settings.SettingsStore
import ru.adsrv.frpjump.settings.assistantTools
import ru.adsrv.frpjump.ssh.SshTargets

/**
 * What the app's session grants are bound to, read live from the saved connections, their tunnels, the assistant's settings and the app lock.
 * The lock counts as engaged from the moment the app would lock on return ("Lock after" run out while out of sight), not only once the screen shows it.
 */
class AppGrantWorld(private val targets: SshTargets, private val settings: SettingsStore, private val lock: AppLock) : GrantWorld {
    override fun period(target: GrantTarget): Any? = targets.period(target.profileId, target.connectionId)
    override fun fingerprint(target: GrantTarget): Any? = targets.fingerprint(target.profileId, target.connectionId)
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
    tick: Flow<*> = flow { while (true) { emit(Unit); delay(GRANT_TICK_MS) } }): SessionGrants {
    val grants = SessionGrants(AppGrantWorld(targets, settings, lock))
    SessionGrantCleaner(grants, scope,
        changes = merge(targets.changes(), settings.assistantDisables,
            settings.settings.map { Triple(it.assistantEnabled to it.assistantTools(), it.appLock, it.appLockAfter) }.distinctUntilChanged(), tick),
        locked = lock.locked)
    return grants
}
