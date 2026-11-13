package ru.adsrv.frpjump.lock

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
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
    /** The system's own confirm-credential action (`KeyguardManager.createConfirmDeviceCredentialIntent(null, null)?.action`). */
    private val systemCredentialAction: () -> String? = { null },
) {
    private val policy = LockPolicy()
    private val _locked = MutableStateFlow(false)

    /** True while the content must be hidden behind the lock screen. */
    val locked: StateFlow<Boolean> = _locked

    private class Prompt(val id: Int, val owner: Any, val onResult: (AuthResult) -> Unit)

    private var current: Prompt? = null
    private var lastId = 0

    /**
     * The app's own launch of an external window, held while locked (see
     * [holdLaunch]). [onDrop] tells its owner that it will never run (a result
     * launch is answered as cancelled, so nothing waits on it for good).
     */
    private class Held(val owner: Any, val launch: () -> Unit, val onDrop: (() -> Unit)?)

    private var held: Held? = null

    /** [afterMs]: Settings › Lock after, read at this start. */
    fun onStart(enabled: Boolean, afterMs: Long = LockPolicy.DEFAULT_TIMEOUT_MS) {
        setLocked(policy.onStart(clock(), enabled, afterMs))
    }

    fun onStop() {
        policy.onStop(clock())
        // Out of sight while locked (the last started activity stopped): the held launch is dropped, except for
        // the prompt's own stops (an owner who left with the prompt up is caught when the prompt ends, see
        // [authenticateFor]). Another instance still in sight keeps it.
        if (!policy.started && !policy.authenticating) dropHeld()
    }

    /** The one place [_locked] changes: it never goes false with a launch still held, unless the unlock runs it. */
    private fun setLocked(locked: Boolean) {
        _locked.value = locked
        if (!locked) dropHeld()
    }

    private fun dropHeld() {
        val h = held ?: return
        held = null
        try {
            h.onDrop?.invoke()
        } catch (e: RuntimeException) {
            Log.w(TAG, "held launch drop failed: ${e.javaClass.simpleName}")
        }
    }

    /** The app is launching a window of its own; see [LockPolicy.handoff]. */
    fun handoff() = policy.handoff(clock())

    /** The setting went off, or there is no screen lock left to ask for. */
    fun unlock() {
        policy.unlocked()
        // Nothing was protected (no screen lock any more, or the setting went off): a held launch is dropped.
        setLocked(false)
    }

    /**
     * The app is about to launch an external window ([launch] does it). While
     * locked it must not appear over the lock screen: [launch] is kept, the
     * latest only, and runs once after a successful unlock; true means "held",
     * the caller must not launch. [forResult]: a result is awaited (the
     * `startActivityForResult` path); [onDrop] runs if the launch never will.
     * Dropped by [onStop] (not the prompt's own), when the prompt ends with the
     * screen out of sight, [onDestroy] of [owner], and whenever the lock goes
     * off other than by the prompt's success. A launch that fails when it runs
     * gives no message.
     */
    fun holdLaunch(
        owner: Any,
        action: String?,
        forResult: Boolean,
        enabled: Boolean,
        afterMs: Long,
        onDrop: (() -> Unit)? = null,
        launch: () -> Unit,
    ): Boolean {
        if (!shouldHold(action, forResult, enabled, afterMs)) return false
        dropHeld() // The older one is replaced: it never runs either.
        held = Held(owner, launch, onDrop)
        return true
    }

    /**
     * The one decision. Hold when the lock is engaged, or when the app is out
     * of sight and the lock would engage on return (the setting on and the
     * interval elapsed: [LockPolicy.wouldLockNow]); never the prompt's own launch.
     */
    private fun shouldHold(action: String?, forResult: Boolean, enabled: Boolean, afterMs: Long): Boolean {
        val engaged = _locked.value || (!policy.started && policy.wouldLockNow(clock(), enabled, afterMs))
        return engaged && !(forResult && isPromptLaunch(action))
    }

    /**
     * The system's confirm-device-credential screen, which androidx.biometric
     * starts for the PIN fallback (BiometricFragment: Fragment.startActivityForResult
     * with KeyguardManager.createConfirmDeviceCredentialIntent) while a prompt is
     * open. It arrives at the activity as an ordinary startActivityForResult with
     * no marker of its fragment (the fragment goes through the activity result
     * registry, shared with the app's own pickers), so it is told by its platform
     * action (by action only, not by package), while our prompt is active and on
     * the `startActivityForResult` path only. The app itself never launches these actions.
     */
    fun isPromptLaunch(action: String?): Boolean = policy.authenticating && action in credentialActions

    /** The system's action if it says one, else the platform constants; asked once per process, when a prompt first needs it. */
    private val credentialActions: Set<String> by lazy {
        systemCredentialAction()?.let { setOf(it) } ?: CREDENTIAL_ACTIONS
    }

    private fun runHeld() {
        val h = held ?: return
        held = null
        try {
            h.launch()
        } catch (e: RuntimeException) {
            // E.g. no activity for it any more: no message (the owner can repeat the action), nothing logged of it.
            Log.w(TAG, "held launch failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * [owner] is being destroyed: its open prompt, if any, will never be
     * answered. It is dropped without a result and unlocks nothing.
     */
    fun onDestroy(owner: Any) {
        if (held?.owner === owner) dropHeld()
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
        val prompt = Prompt(++lastId, owner, onResult)
        val replaced = current
        current = prompt
        policy.authStarted()
        // The replaced prompt's owner may be alive and waiting (another instance): do not leave it spinning.
        replaced?.onResult?.invoke(AuthResult.Cancelled)
        // The prompt that is not the current one (abandoned, replaced) has no say.
        fun end(success: Boolean, result: AuthResult) {
            if (current?.id != prompt.id) return
            current = null
            policy.authFinished(success = success, now = clock())
            // The owner left while the prompt was up: nothing visible could open now, and it must not run at a later unlock.
            if (!policy.started) dropHeld()
            if (success) _locked.value = false
            onResult(result)
            if (success) runHeld()
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
        private const val TAG = "AppLock"

        /** `KeyguardManager.createConfirmDeviceCredentialIntent` actions (hidden constants), with and without a user. */
        val CREDENTIAL_ACTIONS = setOf(
            "android.app.action.CONFIRM_DEVICE_CREDENTIAL",
            "android.app.action.CONFIRM_DEVICE_CREDENTIAL_WITH_USER",
        )

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
