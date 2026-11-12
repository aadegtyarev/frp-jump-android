package ru.adsrv.frpjump.lock

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How a prompt ended. */
sealed interface AuthResult {
    data object Success : AuthResult

    /** The owner closed it (back, a tap outside, the app left). */
    data object Cancelled : AuthResult

    /** Too many attempts, no hardware and the like; [message] is Android's own words. */
    data class Failed(val message: String) : AuthResult
}

/** What the system prompt reports; the codes are [BiometricPrompt]'s. */
interface PromptListener {
    fun succeeded()
    fun error(code: Int, message: String)
}

/**
 * Shows the system prompt for an owner (an activity). A seam, so that
 * [AppLock]'s rules can be tested without Android.
 */
interface PromptHost {
    /** The owner's fragment state was saved: androidx would show nothing and never answer. */
    fun isStateSaved(owner: Any): Boolean

    fun show(owner: Any, title: String, subtitle: String, listener: PromptListener)
}

/**
 * The app's screen lock, one per process: [LockPolicy] on the activity's
 * start and stop, and the BiometricPrompt. Main thread.
 *
 * androidx.biometric 1.1.0 drops the answer when the prompt's activity is
 * destroyed first (a recreation, for one), so each prompt has an owner and
 * an id: [onDestroy] abandons the owner's open prompt, and a late answer
 * of a prompt that is not the current one does nothing.
 */
class AppLock(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val host: PromptHost = AndroidPromptHost,
) {
    private val policy = LockPolicy()
    private val _locked = MutableStateFlow(false)

    /** True while the content must be hidden behind the lock screen. */
    val locked: StateFlow<Boolean> = _locked

    private class Prompt(val id: Int, val owner: Any)

    private var current: Prompt? = null
    private var lastId = 0

    /** [afterMs]: Settings › Lock after, read at this start. */
    fun onStart(enabled: Boolean, afterMs: Long = LockPolicy.DEFAULT_TIMEOUT_MS) {
        _locked.value = policy.onStart(clock(), enabled, afterMs)
    }

    fun onStop() = policy.onStop(clock())

    /** The app is launching a window of its own; see [LockPolicy.handoff]. */
    fun handoff() = policy.handoff(clock())

    /** The setting went off, or there is no screen lock left to ask for. */
    fun unlock() {
        policy.unlocked()
        _locked.value = false
    }

    /**
     * [owner] is being destroyed: its open prompt, if any, will never be
     * answered. It is dropped without a result and unlocks nothing.
     */
    fun onDestroy(owner: Any) {
        val prompt = current ?: return
        if (prompt.owner !== owner) return
        current = null
        policy.authAbandoned(clock())
    }

    /**
     * Asks for the phone's biometrics or screen lock. Success unlocks the
     * app as well (also when it was asked to turn the setting on). A prompt
     * whose [activity] is destroyed first gets no result.
     */
    fun authenticate(activity: FragmentActivity, title: String, subtitle: String, onResult: (AuthResult) -> Unit) =
        authenticateFor(activity, title, subtitle, onResult)

    internal fun authenticateFor(owner: Any, title: String, subtitle: String, onResult: (AuthResult) -> Unit) {
        // Nothing would be shown and nothing answered: do not start.
        if (host.isStateSaved(owner)) {
            onResult(AuthResult.Cancelled)
            return
        }
        val prompt = Prompt(++lastId, owner)
        current = prompt
        policy.authStarted()
        // The prompt that is not the current one (abandoned, replaced) has no say.
        fun end(success: Boolean, result: AuthResult) {
            if (current?.id != prompt.id) return
            current = null
            policy.authFinished(success = success, now = clock())
            if (success) _locked.value = false
            onResult(result)
        }
        val listener = object : PromptListener {
            override fun succeeded() = end(true, AuthResult.Success)

            override fun error(code: Int, message: String) = end(false, errorResult(code, message))
            // A finger not recognised: the prompt stays and says so itself.
        }
        try {
            host.show(owner, title, subtitle, listener)
        } catch (e: Exception) {
            // E.g. the activity is being saved: nothing was shown.
            end(false, AuthResult.Failed(e.message ?: e.javaClass.simpleName))
        }
    }

    private fun errorResult(code: Int, message: String): AuthResult = when (code) {
        BiometricPrompt.ERROR_USER_CANCELED,
        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
        BiometricPrompt.ERROR_CANCELED,
        -> AuthResult.Cancelled
        else -> AuthResult.Failed(message)
    }

    companion object {
        /** The phone has a PIN, pattern or password: the prompt always has something to ask for. */
        fun hasScreenLock(context: Context): Boolean =
            context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
    }
}

/** The system's BiometricPrompt on a [FragmentActivity]. */
object AndroidPromptHost : PromptHost {
    /**
     * Fingerprint (or face) with the PIN, pattern or password as the
     * fallback. Android 10 does not take a strong biometric together
     * with the screen lock, so there it is any biometric: this guards
     * the screen, no key is unlocked with it.
     */
    val AUTHENTICATORS: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        BIOMETRIC_STRONG or DEVICE_CREDENTIAL
    } else {
        BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    }

    override fun isStateSaved(owner: Any): Boolean = (owner as FragmentActivity).supportFragmentManager.isStateSaved

    override fun show(owner: Any, title: String, subtitle: String, listener: PromptListener) {
        val activity = owner as FragmentActivity
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = listener.succeeded()

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) =
                listener.error(errorCode, errString.toString())
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info)
    }
}
