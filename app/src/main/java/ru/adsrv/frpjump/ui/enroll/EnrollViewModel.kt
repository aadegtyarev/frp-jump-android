package ru.adsrv.frpjump.ui.enroll

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.api.ApiException
import ru.adsrv.frpjump.enroll.EnrollLineParser
import ru.adsrv.frpjump.enroll.EnrollLineResult
import ru.adsrv.frpjump.profiles.defaultProfileName
import ru.adsrv.frpjump.profiles.nextProfileColor
import ru.adsrv.frpjump.profiles.profileColor
import ru.adsrv.frpjump.profiles.profileName
import ru.adsrv.frpjump.store.Credentials
import ru.adsrv.frpjump.store.StoredEnrollment
import ru.adsrv.frpjump.ui.userMessage

data class EnrollUiState(
    val text: String = "",
    /** The server of a line that parses; shown under it as a check. */
    val host: String? = null,
    val needsName: Boolean = false,
    /** The phone's device name, asked for only when the token has none. */
    val name: String = "phone",
    val profileName: String = "",
    /** The owner typed the profile name: a new line no longer replaces it with its host. */
    val profileNameEdited: Boolean = false,
    val color: String = "",
    val busy: Boolean = false,
    /** About the line or the server's answer: shown under the line. */
    val error: String? = null,
    val profileNameError: String? = null,
    val notice: String? = null,
)

/**
 * "New profile": paste the enroll line, name the profile and pick its
 * colour. The name follows the server's host until the owner types one.
 * When the shown profile has no credentials (its server removed it, or
 * "Enroll again"), enrolling fills that one in and its name and colour
 * are offered.
 */
class EnrollViewModel(private val app: FrpJumpApp) : ViewModel() {
    private val _state = MutableStateFlow(
        EnrollUiState(
            notice = app.session.takeNotice(),
            color = nextProfileColor(app.profiles.state.value.index.profiles),
        ),
    )
    val state: StateFlow<EnrollUiState> = _state

    init {
        viewModelScope.launch {
            val profile = app.profiles.state.value.index.currentProfile ?: return@launch
            // Storage and the Keystore: off the main thread.
            val empty = withContext(Dispatchers.IO) {
                app.profiles.current?.store?.state() is StoredEnrollment.NotEnrolled
            }
            if (empty && profile.name.isNotBlank()) {
                _state.update {
                    it.copy(profileName = profile.name, profileNameEdited = true, color = profileColor(profile.color))
                }
            }
        }
    }

    fun onText(text: String) = _state.update {
        val host = (EnrollLineParser.parse(text) as? EnrollLineResult.Ok)?.line?.controlUrl?.let(::defaultProfileName)
        it.copy(
            text = text,
            host = host,
            profileName = if (it.profileNameEdited) it.profileName else host.orEmpty(),
            error = null,
        )
    }

    fun onName(name: String) = _state.update { it.copy(name = name, error = null) }

    fun onProfileName(name: String) =
        _state.update { it.copy(profileName = name, profileNameEdited = name.isNotEmpty(), profileNameError = null) }

    fun onColor(color: String) = _state.update { it.copy(color = color) }

    fun submit(onEnrolled: () -> Unit) {
        val current = _state.value
        if (current.busy) return
        val line = when (val parsed = EnrollLineParser.parse(current.text)) {
            is EnrollLineResult.Error -> return _state.update { it.copy(error = parsed.message) }
            is EnrollLineResult.Ok -> parsed.line
        }
        val profile = profileName(current.profileName)
            ?: return _state.update { it.copy(profileNameError = app.getString(R.string.profile_name_missing)) }
        val name = if (current.needsName) current.name.trim() else line.requestedName
        _state.update { it.copy(busy = true, error = null, profileNameError = null) }
        viewModelScope.launch {
            val r = try {
                app.api.enroll(line.controlUrl, line.token, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A token without a fixed name: the server wants one, so ask for it.
                val wantsName = e is ApiException && e.status == 400 && "pass requested_name" in e.detail
                _state.update {
                    it.copy(busy = false, error = e.userMessage(), needsName = it.needsName || wantsName)
                }
                return@launch
            }
            val runtime = try {
                app.profiles.enroll(
                    Credentials(
                        controlUrl = line.controlUrl,
                        deviceId = r.deviceId,
                        deviceName = r.deviceName,
                        apiToken = r.apiToken,
                        certPem = r.certPem,
                        keyPem = r.keyPem,
                        caCertPem = r.caCertPem,
                        serverAddr = r.serverAddr,
                        serverPort = r.serverPort,
                    ),
                )
            } catch (e: Exception) {
                // The token is spent and the server has the device: say how to clean up.
                val enrolled = r.deviceName
                _state.update {
                    it.copy(
                        busy = false,
                        error = app.getString(R.string.enroll_store_failed, enrolled, e.javaClass.simpleName, enrolled),
                    )
                }
                return@launch
            }
            // The enrollment is stored; a name not saved only leaves the host as the name.
            try {
                app.profiles.update(runtime.id, profile, current.color)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "profile name not saved: ${e.javaClass.simpleName}")
            }
            _state.update { it.copy(busy = false) }
            onEnrolled()
        }
    }

    private companion object {
        const val TAG = "EnrollViewModel"
    }
}
