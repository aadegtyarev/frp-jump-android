package ru.adsrv.frpjump.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import ru.adsrv.frpjump.devices.DisplayChange
import ru.adsrv.frpjump.devices.DisplayFailure
import ru.adsrv.frpjump.devices.displayFailure
import ru.adsrv.frpjump.ui.userMessage
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.devices.DeviceDirectory
import ru.adsrv.frpjump.devices.DeviceList
import ru.adsrv.frpjump.devices.DeviceRow
import ru.adsrv.frpjump.session.Notice
import ru.adsrv.frpjump.errors.DomainError
import java.time.Instant

data class DevicesUiState(
    /** Null until the first answer or the cached list. */
    val profile: String? = null,
    val devices: List<DeviceRow>? = null,
    val display: Boolean = false,
    val refreshing: Boolean = false,
    val favorites: Set<String> = emptySet(),
    val error: DomainError? = null,
    /** Set once a refresh of an old list failed: when the server last answered. */
    val staleSince: Instant? = null,
    /** The failure is the network, not the server. */
    val unreachable: Boolean = false,
)

/**
 * The current profile's devices; switching profiles shows the other
 * profile's list at once (its cached one until its server answers). The
 * list itself is polled by the profile ([ru.adsrv.frpjump.session.Poller])
 * while it is shown.
 */
class DevicesViewModel(private val app: FrpJumpApp) : ViewModel() {
    private val directory: StateFlow<DeviceDirectory?> = app.profiles.state.map { it.current?.devices }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, app.profiles.current?.devices)
    private val refreshing = MutableStateFlow(false)

    /** The list is on screen. */
    private var shown = false

    @OptIn(ExperimentalCoroutinesApi::class)
    private val favorites = app.profiles.state.flatMapLatest { state ->
        val profile = state.index.current
        state.current?.deviceFavorites?.favorites?.map { profile to it } ?: flowOf(profile to emptySet())
    }

    val state: StateFlow<DevicesUiState> =
        combine(app.profileInformation.observe(), refreshing, favorites) { information, busy, favorite ->
            devicesUiState(information, busy).copy(favorites = if (favorite.first == information.current) favorite.second else emptySet())
        }.stateIn(viewModelScope, SharingStarted.Eagerly, DevicesUiState())

    /** A message left by the screen before (e.g. after "Forget profile"); shown once. */
    val notice: StateFlow<Notice?> = app.session.notice

    /** The pending notice if it is meant for this list, the current profile's; once. */
    fun takeNotice(): String? = app.profiles.state.value.index.current?.let(app.session::takeNotice)

    init {
        // A switch hands "wanted" over to the profile now shown.
        viewModelScope.launch {
            var previous: DeviceDirectory? = null
            directory.collect { now ->
                // A pull of the profile left behind no longer spins on this one's list.
                if (previous != null && previous != now) refreshing.value = false
                previous?.want(this@DevicesViewModel, false)
                previous = now
                if (shown && now != null) want(now)
            }
        }
    }

    /** The list is on screen (or not): the poller fetches it only then. */
    fun shown(visible: Boolean) {
        shown = visible
        val now = directory.value ?: return
        if (visible) want(now) else now.want(this, false)
    }

    private fun want(devices: DeviceDirectory) {
        devices.want(this, true)
        viewModelScope.launch { devices.refresh() }
    }

    /** Gone with its screen: the list is no longer wanted for it. */
    /** Saves display data through the selected profile's existing device directory. */
    fun saveDisplay(profile: String, device: String, change: DisplayChange, done: (DisplayFailure?) -> Unit) {
        val runtime = app.profiles.runtime(profile) ?: return done(DisplayFailure.Other(ru.adsrv.frpjump.locale.Texts.get(ru.adsrv.frpjump.R.string.profile_gone)))
        viewModelScope.launch {
            val failure = try {
                withContext(Dispatchers.IO) { runtime.devices.setDisplay(device, change) }
                null
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                displayFailure(e, change) { it.userMessage() }
            }
            done(failure)
        }
    }

    fun toggleFavorite(profile: String, device: String, done: (String?) -> Unit) {
        val runtime = app.profiles.runtime(profile) ?: return done(ru.adsrv.frpjump.locale.Texts.get(ru.adsrv.frpjump.R.string.profile_gone))
        viewModelScope.launch {
            val failure = try { withContext(Dispatchers.IO) { runtime.deviceFavorites.toggle(device) }; null }
            catch (e: Exception) { if (e is CancellationException) throw e; e.userMessage() }
            done(failure)
        }
    }

    override fun onCleared() {
        directory.value?.want(this, false)
    }

    /** Pull to refresh: waits for a fetch already in flight rather than skipping. */
    fun pull() {
        val devices = directory.value ?: return
        refreshing.value = true
        viewModelScope.launch {
            devices.pull()
            refreshing.value = false
        }
    }
}

/** Current-profile presentation; the shared source has already applied Undo. */
internal fun devicesUiState(information: ru.adsrv.frpjump.profiles.ProfileInformationState, busy: Boolean): DevicesUiState {
    val part = information.profiles.firstOrNull { it.id == information.current }
    val list = part?.devices ?: DeviceList()
    return DevicesUiState(
        profile = part?.id,
        devices = list.devices,
        display = list.display,
        refreshing = busy,
        error = list.error,
        // A list restored at start is not a failure yet: no banner until a fetch fails.
        staleSince = list.updatedAt.takeIf { list.stale && list.error != null },
        unreachable = list.unreachable,
    )
}
