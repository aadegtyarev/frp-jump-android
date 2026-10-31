package ru.adsrv.frpjump.ui.enroll

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import ru.adsrv.frpjump.locale.Texts
import ru.adsrv.frpjump.enroll.EnrollLineProblem
import ru.adsrv.frpjump.ui.userMessage
import java.util.concurrent.atomic.AtomicBoolean

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
class EnrollViewModel(
    private val app: FrpJumpApp,
    /** The app's first screen (no profile to go back to): it shows notices posted for whoever lands next. */
    takesNotices: Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(
        EnrollUiState(
            color = nextProfileColor(app.profiles.state.value.index.profiles),
        ),
    )
    val state: StateFlow<EnrollUiState> = _state

    init {
        // From the flow, not only at creation: a notice posted while this is shown (e.g. from the
        // profile list over it) shows here rather than later somewhere else.
        if (takesNotices) {
            viewModelScope.launch {
                app.session.notice.collect { if (it != null) app.session.takeAnyNotice()?.let { text -> _state.update { s -> s.copy(notice = text) } } }
            }
        }
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

    /**
     * A code the scanner read: taken as the line, as if pasted, when it is an
     * enroll line. Returns false (the scanner says so and goes on) otherwise.
     */
    fun onScanned(text: String): Boolean {
        if (_state.value.busy || EnrollLineParser.parse(text) !is EnrollLineResult.Ok) return false
        onText(text)
        return true
    }

    fun onName(name: String) = _state.update { it.copy(name = name, error = null) }

    fun onProfileName(name: String) =
        _state.update { it.copy(profileName = name, profileNameEdited = name.isNotEmpty(), profileNameError = null) }

    fun onColor(color: String) = _state.update { it.copy(color = color) }

    fun submit(onEnrolled: () -> Unit) {
        val current = _state.value
        if (current.busy) return
        val line = when (val parsed = EnrollLineParser.parse(current.text)) {
            is EnrollLineResult.Error -> return _state.update { it.copy(error = parsed.problem.text()) }
            is EnrollLineResult.Ok -> parsed.line
        }
        val profile = profileName(current.profileName)
            ?: return _state.update { it.copy(profileNameError = Texts.get(R.string.profile_name_missing)) }
        val name = if (current.needsName) current.name.trim() else line.requestedName
        _state.update { it.copy(busy = true, error = null, profileNameError = null) }
        // The server spends the token as it answers: the call and the storing run in the app
        // scope, so leaving the screen cannot drop the credentials halfway.
        val work = app.appScope.async { enroll(line.controlUrl, line.token, name, profile, current.color) }
        val taken = AtomicBoolean(false)
        pending = Pending(work, taken)
        viewModelScope.launch {
            val outcome = work.await()
            // Shown here, or (the screen went away meanwhile) as a notice: never both, never neither.
            if (!taken.compareAndSet(false, true)) return@launch
            pending = null
            when (outcome) {
                is EnrollOutcome.Stored -> {
                    _state.update { it.copy(busy = false) }
                    onEnrolled()
                }
                is EnrollOutcome.Refused -> _state.update {
                    it.copy(busy = false, error = outcome.message, needsName = it.needsName || outcome.wantsName)
                }
                // The server has the device: say how to clean up.
                is EnrollOutcome.NotStored -> _state.update {
                    it.copy(
                        busy = false,
                        error = Texts.get(R.string.enroll_store_failed, outcome.device, outcome.failure, outcome.device),
                    )
                }
            }
        }
    }

    /** An enrollment under way and whether its outcome has been shown or posted. */
    private class Pending(val work: Deferred<EnrollOutcome>, val taken: AtomicBoolean)

    private var pending: Pending? = null

    /**
     * Left before the answer: the token may be spent by now, so the outcome
     * goes to the screen the app shows next as a notice (the new profile, or
     * how to clean up on the server). A refusal spent nothing and is dropped.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCleared() {
        val left = pending ?: return
        left.work.invokeOnCompletion { cause ->
            if (cause == null && left.taken.compareAndSet(false, true)) postOutcome(left.work.getCompleted())
        }
    }

    private fun postOutcome(outcome: EnrollOutcome) {
        val text = when (outcome) {
            is EnrollOutcome.Stored -> Texts.get(R.string.profile_added, outcome.name)
            is EnrollOutcome.NotStored -> Texts.get(R.string.enroll_store_failed, outcome.device, outcome.failure, outcome.device)
            is EnrollOutcome.Refused -> return
        }
        app.session.postNotice(text, forProfile = app.profiles.state.value.index.current)
    }

    private sealed interface EnrollOutcome {
        /** Stored as the profile [name], now the one shown. */
        data class Stored(val name: String) : EnrollOutcome

        /** The server said no (or could not be reached): nothing was spent. */
        data class Refused(val message: String, val wantsName: Boolean) : EnrollOutcome

        /** Enrolled on the server as [device], but the phone could not keep the credentials. */
        data class NotStored(val device: String, val failure: String) : EnrollOutcome
    }

    private suspend fun enroll(url: String, token: String, requestedName: String?, profile: String, color: String): EnrollOutcome {
        val r = try {
            app.api.enroll(url, token, requestedName)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A token without a fixed name: the server wants one, so ask for it.
            val wantsName = e is ApiException && e.status == 400 && "pass requested_name" in e.detail
            return EnrollOutcome.Refused(e.userMessage(), wantsName)
        }
        val credentials = Credentials(
            controlUrl = url,
            deviceId = r.deviceId,
            deviceName = r.deviceName,
            apiToken = r.apiToken,
            certPem = r.certPem,
            keyPem = r.keyPem,
            caCertPem = r.caCertPem,
            serverAddr = r.serverAddr,
            serverPort = r.serverPort,
        )
        return store(credentials, profile, color)?.let { EnrollOutcome.NotStored(r.deviceName, it) } ?: EnrollOutcome.Stored(profile)
    }

    /**
     * Stores a fresh enrollment as a profile named [name] in [color]. A name
     * not saved only leaves the host as the name. Returns null when stored,
     * else the failure's class name.
     */
    private suspend fun store(credentials: Credentials, name: String, color: String): String? {
        val runtime = try {
            app.profiles.enroll(credentials)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "credentials not stored: ${e.javaClass.simpleName}")
            return e.javaClass.simpleName
        }
        try {
            app.profiles.update(runtime.id, name, color)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "profile name not saved: ${e.javaClass.simpleName}")
        }
        return null
    }

    private companion object {
        const val TAG = "EnrollViewModel"
    }
}

/** Why the pasted line is not an enroll line, in the app's language. */
private fun EnrollLineProblem.text(): String = when (this) {
    EnrollLineProblem.EMPTY -> Texts.get(R.string.enroll_line_empty)
    EnrollLineProblem.NAME_WITHOUT_VALUE -> Texts.get(R.string.enroll_line_name_empty)
    EnrollLineProblem.NOTHING_AFTER_COMMAND -> Texts.get(R.string.enroll_line_no_url)
    EnrollLineProblem.TOO_SHORT -> Texts.get(R.string.enroll_line_too_short)
    EnrollLineProblem.TOKEN_LENGTH -> Texts.plural(R.plurals.enroll_line_token_length, EnrollLineParser.TOKEN_LENGTH, EnrollLineParser.TOKEN_LENGTH)
    EnrollLineProblem.HTTP -> Texts.get(R.string.enroll_line_http)
    EnrollLineProblem.BAD_URL -> Texts.get(R.string.enroll_line_bad_url)
    EnrollLineProblem.BAD_TOKEN -> Texts.plural(R.plurals.enroll_line_bad_token, EnrollLineParser.TOKEN_LENGTH, EnrollLineParser.TOKEN_LENGTH)
}
