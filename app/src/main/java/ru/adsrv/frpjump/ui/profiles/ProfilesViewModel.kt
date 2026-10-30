package ru.adsrv.frpjump.ui.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.profiles.defaultProfileName

/** A "Forget profile" in progress or failed, for the row it was started from. */
data class ForgetState(val id: String, val error: String? = null) {
    val busy: Boolean get() = error == null
}

/** The profiles for the switcher, the profile list and the profile group of Settings. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfilesViewModel(private val app: FrpJumpApp) : ViewModel() {
    private val servers = MutableStateFlow<Map<String, ProfileServer?>>(emptyMap())

    private val removed = app.profiles.state.map { it.runtimes }.distinctUntilChanged().flatMapLatest { runtimes ->
        if (runtimes.isEmpty()) {
            flowOf(emptySet())
        } else {
            combine(runtimes.values.map { r -> r.session.removed.map { if (it) r.id else null } }) { ids ->
                ids.filterNotNull().toSet()
            }
        }
    }

    val items: StateFlow<List<ProfileItem>> =
        combine(app.profiles.state, app.profiles.tunnelsByProfile, removed, servers) { state, tunnels, gone, known ->
            profileItems(state.index, tunnels, gone, known)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _forget = MutableStateFlow<ForgetState?>(null)
    val forget: StateFlow<ForgetState?> = _forget

    init {
        // Each profile's server and phone name: the Keystore, off the main thread. Read once
        // per profile; again only for one without an enrollment (it may have been filled in).
        viewModelScope.launch {
            app.profiles.state.collectLatest { state ->
                val known = servers.value.filterKeys { it in state.runtimes }
                val load = serversToLoad(state.runtimes.keys, known)
                val read = withContext(Dispatchers.IO) {
                    load.associateWith { id ->
                        runCatching { state.runtimes.getValue(id).store.load() }.getOrNull()?.let {
                            ProfileServer(defaultProfileName(it.controlUrl), it.deviceName)
                        }
                    }
                }
                servers.value = known + read
            }
        }
    }

    /** Shows [id]; [then] once it is the current profile. */
    fun switchTo(id: String, then: () -> Unit) {
        viewModelScope.launch {
            app.profiles.setCurrent(id)
            then()
        }
    }

    /**
     * See [forgetProfile]; [onForgotten] after it is done. One at a time:
     * false (nothing started) while another one runs. A failure is kept for
     * the row it was started from, or with [failureAsNotice] posted for the
     * device list, which outlives the sheet it was started from.
     */
    fun forget(id: String, failureAsNotice: Boolean = false, onForgotten: () -> Unit): Boolean {
        if (_forget.value?.busy == true) return false
        _forget.value = ForgetState(id)
        val work = app.appScope.async { forgetProfile(app, id) }
        viewModelScope.launch {
            val failure = work.await()
            if (failure != null && failureAsNotice) {
                app.session.postNotice(failure, forProfile = app.profiles.state.value.index.current)
                _forget.value = null
            } else {
                _forget.value = failure?.let { ForgetState(id, it) }
            }
            if (failure == null) onForgotten()
        }
        return true
    }
}
