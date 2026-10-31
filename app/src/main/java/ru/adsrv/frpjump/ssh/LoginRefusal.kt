package ru.adsrv.frpjump.ssh

import ru.adsrv.frpjump.R

/** Why a login was refused, as the owner is told it; a remembered password is never dropped for it. */
enum class LoginRefusal {
    /** The device did not take the phone's key. */
    KEY,

    /** The password just typed. */
    PASSWORD_TYPED,

    /** The remembered password: kept, "Change login" replaces it. */
    PASSWORD_SAVED,

    /** A password is remembered but cannot be unsealed on this phone (the Keystore key went). */
    PASSWORD_UNREADABLE,

    /** No password typed and none remembered (forgotten meanwhile). */
    PASSWORD_MISSING,
}

/**
 * The refusal of a login with a key ([key]) or a password: [typed] when it
 * was typed for this login; else whether one is remembered ([remembered])
 * and could be read ([readable]).
 */
fun loginRefusal(key: Boolean, typed: Boolean, remembered: Boolean, readable: Boolean): LoginRefusal = when {
    key -> LoginRefusal.KEY
    typed -> LoginRefusal.PASSWORD_TYPED
    !remembered -> LoginRefusal.PASSWORD_MISSING
    !readable -> LoginRefusal.PASSWORD_UNREADABLE
    else -> LoginRefusal.PASSWORD_SAVED
}

/** The text of a password refusal, taking the user's name; [LoginRefusal.KEY] takes the key's place in Settings too. */
fun loginRefusalText(refusal: LoginRefusal): Int = when (refusal) {
    LoginRefusal.KEY -> R.string.ssh_key_refused
    LoginRefusal.PASSWORD_TYPED -> R.string.ssh_password_refused
    LoginRefusal.PASSWORD_SAVED -> R.string.ssh_saved_password_refused
    LoginRefusal.PASSWORD_UNREADABLE -> R.string.ssh_saved_password_unreadable
    LoginRefusal.PASSWORD_MISSING -> R.string.ssh_saved_password_missing
}

/**
 * Whether the login dialog may offer the remembered password of [user]
 * ([kept]: one is kept): not to the user it was just refused for
 * ([refusedUser], [refusal]) when that was the saved password or it could
 * not be read; an empty field would only try it again. Another user's
 * saved password still counts.
 */
fun offersSavedPassword(refusal: LoginRefusal?, refusedUser: String?, user: String, kept: () -> Boolean): Boolean {
    val stale = refusal == LoginRefusal.PASSWORD_SAVED || refusal == LoginRefusal.PASSWORD_UNREADABLE
    return if (stale && user.trim() == refusedUser) false else kept()
}

/**
 * Whether "Terminal" or "Files" logs in at once with the [login]
 * remembered for a device and port, without the dialog: by key unless the
 * key was just refused for that user; by password when one is saved for
 * that user ([savedPassword]) and it was not just refused or unreadable
 * ([lastRefusal] for [refusedUser], see [offersSavedPassword]).
 */
fun connectsDirectly(login: SshLogin, savedPassword: Boolean, lastRefusal: LoginRefusal?, refusedUser: String?): Boolean =
    when (login.method) {
        SshAuthMethod.KEY -> !(lastRefusal == LoginRefusal.KEY && refusedUser == login.user)
        SshAuthMethod.PASSWORD -> offersSavedPassword(lastRefusal, refusedUser, login.user) { savedPassword }
    }
