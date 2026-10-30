package ru.adsrv.frpjump.ui.enroll

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.api.ApiException
import ru.adsrv.frpjump.enroll.EnrollLineParser
import ru.adsrv.frpjump.enroll.EnrollLineResult
import ru.adsrv.frpjump.store.Credentials
import ru.adsrv.frpjump.ui.userMessage

data class EnrollUiState(
    val text: String = "",
    val needsName: Boolean = false,
    val name: String = "phone",
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
)

class EnrollViewModel(private val app: FrpJumpApp) : ViewModel() {
    private val _state = MutableStateFlow(EnrollUiState(notice = app.session.takeNotice()))
    val state: StateFlow<EnrollUiState> = _state

    fun onText(text: String) = _state.update { it.copy(text = text, error = null) }

    fun onName(name: String) = _state.update { it.copy(name = name, error = null) }

    fun submit(onEnrolled: () -> Unit) {
        val current = _state.value
        if (current.busy) return
        val line = when (val parsed = EnrollLineParser.parse(current.text)) {
            is EnrollLineResult.Error -> return _state.update { it.copy(error = parsed.message) }
            is EnrollLineResult.Ok -> parsed.line
        }
        val name = if (current.needsName) current.name.trim() else line.requestedName
        _state.update { it.copy(busy = true, error = null) }
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
            try {
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
                        error = "Enrolled as $enrolled on the server, but could not store the credentials " +
                            "(${e.javaClass.simpleName}). Delete it from a laptop: " +
                            "frp-jump-client devices delete $enrolled",
                    )
                }
                return@launch
            }
            _state.update { it.copy(busy = false) }
            onEnrolled()
        }
    }
}
