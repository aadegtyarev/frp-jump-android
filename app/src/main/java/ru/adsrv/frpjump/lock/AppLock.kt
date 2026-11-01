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

/**
 * The app's screen lock, one per process: [LockPolicy] on the activity's
 * start and stop, and the BiometricPrompt. Main thread.
 */
class AppLock(private val clock: () -> Long = SystemClock::elapsedRealtime) {
    private val policy = LockPolicy()
    private val _locked = MutableStateFlow(false)

    /** True while the content must be hidden behind the lock screen. */
    val locked: StateFlow<Boolean> = _locked

    fun onStart(enabled: Boolean) {
        _locked.value = policy.onStart(clock(), enabled)
    }

    fun onStop() = policy.onStop(clock())

    /** The setting went off, or there is no screen lock left to ask for. */
    fun unlock() {
        policy.unlocked()
        _locked.value = false
    }

    /**
     * Asks for the phone's biometrics or screen lock. Success unlocks the
     * app as well (also when it was asked to turn the setting on).
     */
    fun authenticate(activity: FragmentActivity, title: String, subtitle: String, onResult: (AuthResult) -> Unit) {
        policy.authStarted()
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                policy.authFinished(success = true, now = clock())
                _locked.value = false
                onResult(AuthResult.Success)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                policy.authFinished(success = false, now = clock())
                onResult(
                    when (errorCode) {
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_CANCELED,
                        -> AuthResult.Cancelled
                        else -> AuthResult.Failed(errString.toString())
                    },
                )
            }
            // onAuthenticationFailed (a finger not recognised): the prompt stays and says so itself.
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        try {
            BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info)
        } catch (e: Exception) {
            // E.g. the activity is being saved: nothing was shown.
            policy.authFinished(success = false, now = clock())
            onResult(AuthResult.Failed(e.message ?: e.javaClass.simpleName))
        }
    }

    companion object {
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

        /** The phone has a PIN, pattern or password: the prompt always has something to ask for. */
        fun hasScreenLock(context: Context): Boolean =
            context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
    }
}
