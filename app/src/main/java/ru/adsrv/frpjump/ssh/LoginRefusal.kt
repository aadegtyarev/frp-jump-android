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
