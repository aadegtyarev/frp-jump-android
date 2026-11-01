package ru.adsrv.frpjump.ui.profiles

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.locale.Texts
import ru.adsrv.frpjump.api.ApiException
import ru.adsrv.frpjump.ui.userMessage

private const val TAG = "ForgetProfile"

/**
 * "Forget profile" [id]: tell its server to delete this phone, then wipe
 * the profile locally whatever the server said: offline the owner deletes
 * the device from a laptop instead. 401/404 mean the server already has no
 * such phone: nothing to tell. The screen the owner lands on gets a notice
 * ([ru.adsrv.frpjump.session.AppSession.notice]): that the server was not
 * told and what to run instead, or else that the profile is gone. Returns
 * null when done, or why the local wipe (Keystore, storage) failed: then
 * it can be tried again. Run it in the app scope, so leaving the screen
 * cannot stop it halfway.
 */
suspend fun forgetProfile(app: FrpJumpApp, id: String): String? {
    val runtime = app.profiles.runtime(id) ?: return null
    val name = app.profiles.state.value.index.find(id)?.name.orEmpty()
    return try {
        val credentials = withContext(Dispatchers.IO) { runtime.store.load() }
        var notice = Texts.get(R.string.profile_forgotten, name)
        if (credentials != null) {
            val laptop = { e: Exception ->
                Texts.get(R.string.forget_server_not_told, e.userMessage(), credentials.deviceName, credentials.deviceName)
            }
            try {
                app.api.deleteDevice(credentials, credentials.deviceName)
            } catch (e: ApiException) {
                if (e.status != 401 && e.status != 404) notice = laptop(e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice = laptop(e)
            }
        }
        app.forgetProfile(id, keepData = false)
        // For the screen the app moves to: the next profile's devices, or enrollment.
        app.session.postNotice(notice, forProfile = app.profiles.state.value.index.current)
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "forget failed: ${e.javaClass.simpleName}")
        Texts.get(R.string.forget_failed, e.userMessage())
    }
}
