package ru.adsrv.frpjump.profiles

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.ports.profilePortKey
import ru.adsrv.frpjump.restore.pendingRestore
import ru.adsrv.frpjump.store.Credentials
import ru.adsrv.frpjump.store.StoredEnrollment
import ru.adsrv.frpjump.tunnel.TunnelsState
import ru.adsrv.frpjump.tunnel.Waiting
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay

data class ProfilesState(
    /** False until [Profiles.load] has read (and, from v1, upgraded) the profiles. */
    val loaded: Boolean = false,
    /** Why loading failed; [Profiles.load] may be called again. */
    val failure: String? = null,
    /** After a failure, [Profiles.loadRetrying] tries again. */
    val retrying: Boolean = false,
    val index: ProfileIndex = ProfileIndex(),
    val runtimes: Map<String, ProfileRuntime> = emptyMap(),
    /**
     * How many times each profile's enrollment was replaced in place (filled
     * after its server removed it, or wiped for "Enroll again"): what was read
     * from its credentials before is stale once this changes. Missing: 0.
     */
    val enrollments: Map<String, Int> = emptyMap(),
) {
    /** The profile the app shows; null with no profiles. */
    val current: ProfileRuntime? get() = index.current?.let(runtimes::get)
}

/**
 * The app's profiles and one [ProfileRuntime] for each: every profile polls
 * and runs its own tunnels, whichever one the app shows ([ProfilesState.current]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Profiles(
    private val store: ProfileStore,
    /** Tunnels by profile as last applied ([ru.adsrv.frpjump.restore.RestoreRecord.profiles]); null until read. */
    private val restoreRecord: StateFlow<Map<String, Int>?> = MutableStateFlow(emptyMap()),
    private val open: (profile: Profile, store: ProfileStore, currentId: StateFlow<String?>) -> ProfileRuntime,
) {
    private val _state = MutableStateFlow(ProfilesState())
    val state: StateFlow<ProfilesState> = _state
    private val currentId = MutableStateFlow<String?>(null)

    /** Serialises adding and forgetting profiles. */
    private val changing = Mutex()

    val current: ProfileRuntime? get() = _state.value.current

    fun runtime(id: String): ProfileRuntime? = _state.value.runtimes[id]

    /** Tunnels up in all profiles together. */
    val tunnelCount: Flow<Int> = allTunnels { states -> states.values.sumOf { it.tunnels.size } }

    /** Tunnels up, by profile id; a profile without any is left out. */
    val tunnelsByProfile: Flow<Map<String, Int>> = allTunnels { states ->
        states.mapValues { it.value.tunnels.size }.filterValues { it > 0 }
    }

    /** [tunnelsByProfile] as it is now. */
    fun tunnelsByProfileNow(): Map<String, Int> =
        _state.value.runtimes.mapValues { it.value.tunnels.state.value.tunnels.size }.filterValues { it > 0 }

    /**
     * Tunnels still to bring back after a cold start (see [pendingRestore]);
     * null while not known yet. 0 in normal running.
     */
    val restorePending: Flow<Int?> =
        combine(restoreRecord, _state, tunnelsByProfile) { record, state, up ->
            pendingRestore(
                record,
                listed = when {
                    state.loaded -> state.runtimes.keys
                    state.failure != null && !state.retrying -> emptySet()
                    else -> null
                },
                up,
            )
        }.distinctUntilChanged()

    /**
     * Why tunnels still to come back are not back yet, when a poll said so:
     * the Keystore locked first, then no network. Null otherwise.
     */
    val restoreWaiting: Flow<Waiting?> =
        combine(restoreRecord, _state.map { it.runtimes }.distinctUntilChanged().flatMapLatest { runtimes ->
            if (runtimes.isEmpty()) {
                flowOf(emptyMap())
            } else {
                val ids = runtimes.keys.toList()
                combine(ids.map { runtimes.getValue(it).tunnels.state }) { states -> ids.zip(states).toMap() }
            }
        }) { record, states ->
            val waiting = states.filter { (id, s) -> (record?.get(id) ?: 0) > 0 && s.tunnels.isEmpty() }.values.mapNotNull { it.waiting }
            waiting.minOrNull()
        }.distinctUntilChanged()

    /** Every tunnel up, as [profilePortKey]s. */
    val upTunnels: Flow<Set<String>> = allTunnels { states ->
        states.flatMap { (id, s) -> s.tunnels.map { profilePortKey(id, it.device, it.port) } }.toSet()
    }

    private fun <T> allTunnels(summary: (Map<String, TunnelsState>) -> T): Flow<T> =
        _state.map { it.runtimes }.distinctUntilChanged().flatMapLatest { runtimes ->
            if (runtimes.isEmpty()) {
                flowOf(summary(emptyMap()))
            } else {
                val ids = runtimes.keys.toList()
                combine(ids.map { runtimes.getValue(it).tunnels.state }) { states -> summary(ids.zip(states).toMap()) }
            }
        }.distinctUntilChanged()

    /**
     * Reads the profiles (upgrading v1 data first) and starts a runtime for
     * each. Storage: runs on the IO dispatcher. False when it failed.
     */
    suspend fun load(): Boolean = changing.withLock {
        if (_state.value.loaded) return@withLock true
        withContext(Dispatchers.IO) {
            try {
                val index = store.load()
                publish(index, index.profiles.associate { it.id to open(it, store, currentId) })
                true
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "profiles not loaded: ${e.javaClass.simpleName}")
                _state.value = _state.value.copy(failure = e.javaClass.simpleName)
                false
            }
        }
    }

    /**
     * [load], tried again after each of [delaysMs] while it fails (a cold
     * start bringing tunnels back has no screen to retry from). The restore
     * waits meanwhile, and goes on once the profiles are read.
     */
    suspend fun loadRetrying(delaysMs: List<Long>): Boolean {
        for (wait in delaysMs) {
            if (load()) return true
            _state.value = _state.value.copy(retrying = true)
            delay(wait)
        }
        return load().also { if (!it) _state.value = _state.value.copy(retrying = false) }
    }

    /**
     * Stores a fresh enrollment: into the current profile when that one has
     * no credentials (its server removed it, or v1 data without an
     * enrollment), else as a new profile, which becomes the current one.
     */
    suspend fun enroll(credentials: Credentials): ProfileRuntime = changing.withLock {
        withContext(Dispatchers.IO) {
            val now = _state.value
            val current = now.current
            val profile = now.index.currentProfile
            if (current != null && profile != null && current.store.state() is StoredEnrollment.NotEnrolled) {
                store.fill(profile, credentials)
                publish(store.index(), now.runtimes, changedEnrollment = profile.id)
                current
            } else {
                val added = store.add(credentials)
                val runtime = open(added, store, currentId)
                publish(store.index(), now.runtimes + (added.id to runtime))
                runtime
            }
        }
    }

    /**
     * "Forget profile" on this phone: stops its tunnels and, under the same
     * lock as its polls, wipes its credentials and key. With [keepData] the
     * profile stays, empty, for enrolling again (ports, logins, host keys
     * kept); otherwise its directory goes and it leaves the list, the next
     * profile becoming the current one. [beforeUnlisting] runs, on the IO
     * dispatcher, before the profile leaves the list ([ProfileStore.remove]).
     */
    suspend fun forget(id: String, keepData: Boolean, beforeUnlisting: (last: Boolean) -> Unit = {}) = changing.withLock {
        val runtime = _state.value.runtimes[id] ?: return@withLock
        runtime.tunnels.shutdown {
            withContext(Dispatchers.IO) { if (keepData) runtime.store.wipe() else store.remove(id, beforeUnlisting) }
        }
        // After the wipe: a poll can no longer fetch and cache the list again.
        runtime.devices.forget()
        if (keepData) {
            runtime.session.clearRemoved()
            publish(store.index(), _state.value.runtimes, changedEnrollment = id)
        } else {
            runtime.close()
            publish(store.index(), _state.value.runtimes - id)
        }
    }

    /** Shows [id] from now on; its cached list at once, then its polls every 2 s. Storage: on the IO dispatcher. */
    suspend fun setCurrent(id: String) = changing.withLock {
        if (_state.value.index.current == id || _state.value.index.find(id) == null) return@withLock
        val index = withContext(Dispatchers.IO) {
            store.setCurrent(id)
            store.index()
        }
        publish(index, _state.value.runtimes)
    }

    /** Renames and recolours [id]; false when it is no longer listed (forgotten meanwhile). Storage: on the IO dispatcher. */
    suspend fun update(id: String, name: String, color: String): Boolean = changing.withLock {
        val index = withContext(Dispatchers.IO) { store.update(id, name, color)?.let { store.index() } } ?: return@withLock false
        publish(index, _state.value.runtimes)
        true
    }

    private val _disconnectAllFailed = MutableStateFlow(false)

    /**
     * The last "Disconnect all" could not tell some server: those tunnels
     * may come back. Cleared when the next one starts.
     */
    val disconnectAllFailed: StateFlow<Boolean> = _disconnectAllFailed

    /** Suspends until [load] has finished, well or not. */
    suspend fun awaitLoaded() {
        _state.first { it.loaded || it.failure != null }
    }

    /**
     * "Disconnect all": every profile's tunnels, at once. False (and
     * [disconnectAllFailed]) when a server could not be told. With the
     * profiles unreadable there is nothing to tell them with: nothing runs,
     * and nothing is brought back until they are read.
     */
    suspend fun disconnectAll(): Boolean {
        _disconnectAllFailed.value = false
        val runtimes = _state.value.runtimes.values.toList()
        val told = coroutineScope { runtimes.map { async { it.tunnels.disconnectAll() } }.awaitAll() }.all { it }
        _disconnectAllFailed.value = !told
        return told
    }

    /** [changedEnrollment]: a profile whose credentials were just filled in or wiped in place. */
    private fun publish(index: ProfileIndex, runtimes: Map<String, ProfileRuntime>, changedEnrollment: String? = null) {
        currentId.value = index.current
        val enrollments = _state.value.enrollments.filterKeys { it in runtimes }.toMutableMap()
        changedEnrollment?.let { enrollments[it] = (enrollments[it] ?: 0) + 1 }
        _state.value = ProfilesState(loaded = true, index = index, runtimes = runtimes, enrollments = enrollments)
    }

    private companion object {
        const val TAG = "Profiles"
    }
}
