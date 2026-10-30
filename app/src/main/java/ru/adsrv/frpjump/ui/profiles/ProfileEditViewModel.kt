package ru.adsrv.frpjump.ui.profiles

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.profiles.profileColor
import ru.adsrv.frpjump.profiles.profileName
import ru.adsrv.frpjump.ui.userMessage

data class ProfileEditUiState(
    val name: String = "",
    val color: String = "",
    val saving: Boolean = false,
    /** Shown under the name field. */
    val nameError: String? = null,
    /** Why saving failed; shown above the button. */
    val error: String? = null,
)

/** "Name and colour" of profile [id]. */
class ProfileEditViewModel(private val app: FrpJumpApp, private val id: String) : ViewModel() {
    private val _state = MutableStateFlow(
        app.profiles.state.value.index.find(id)
            ?.let { ProfileEditUiState(name = it.name, color = profileColor(it.color)) }
            ?: ProfileEditUiState(),
    )
    val state: StateFlow<ProfileEditUiState> = _state

    fun onName(name: String) = _state.update { it.copy(name = name, nameError = null, error = null) }

    fun onColor(color: String) = _state.update { it.copy(color = color, error = null) }

    /** [onSaved] also when the profile is gone: then a notice says so instead. */
    fun save(onSaved: () -> Unit) {
        val now = _state.value
        if (now.saving) return
        val name = profileName(now.name) ?: return _state.update { it.copy(nameError = app.getString(R.string.profile_name_missing)) }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val listed = try {
                app.profiles.update(id, name, now.color)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "profile not saved: ${e.javaClass.simpleName}")
                _state.update { it.copy(saving = false, error = e.userMessage()) }
                return@launch
            }
            _state.update { it.copy(saving = false) }
            // Forgotten while this screen was open: nothing to save; say so where the owner goes back to.
            if (!listed) app.session.postNotice(app.getString(R.string.profile_gone), forProfile = app.profiles.state.value.index.current)
            onSaved()
        }
    }

    private companion object {
        const val TAG = "ProfileEditViewModel"
    }
}
