package ru.adsrv.frpjump.ui.ssh

import ru.adsrv.frpjump.ssh.SshAuth
import ru.adsrv.frpjump.ssh.SshAuthMethod
import ru.adsrv.frpjump.ssh.SshLogin

/**
 * A session tab's label: "user · device" (":port" when not 22), and
 * " · 2", " · 3" for the session's [number] among those to the same user,
 * device and port (see [ru.adsrv.frpjump.ssh.nextSessionNumber]), so that
 * two shells there can be told apart.
 */
fun tabLabel(user: String, device: String, port: Int, number: Int): String {
    val base = "$user · $device" + if (port != 22) ":$port" else ""
    return if (number > 1) "$base · $number" else base
}

/**
 * Whether "+" can open another session with [auth] as it is, without the
 * login dialog: not after the device [refused] it; then a key, or a
 * password the session holds in memory, or a remembered one that is still
 * kept ([hasRememberedPassword], asked only then).
 */
fun reusesLogin(auth: SshAuth, refused: Boolean, hasRememberedPassword: () -> Boolean): Boolean = when {
    refused -> false
    auth is SshAuth.Password -> auth.typed != null || hasRememberedPassword()
    else -> true
}

/** The login dialog's start for another session like one as [user] with [auth]. */
fun loginLike(user: String, auth: SshAuth): SshLogin =
    SshLogin(user, if (auth is SshAuth.Password) SshAuthMethod.PASSWORD else SshAuthMethod.KEY)

/**
 * The tab selected after [closedId] closes, out of [ids] (the order before):
 * the same one unless it was the closed one, then its left neighbour, else
 * its right one; null when none is left.
 */
fun selectionAfterClose(ids: List<Int>, selectedId: Int, closedId: Int): Int? {
    if (selectedId != closedId && selectedId in ids) return selectedId
    val at = ids.indexOf(closedId)
    val left = ids.subList(0, at.coerceAtLeast(0)).lastOrNull()
    return left ?: ids.drop(at + 1).firstOrNull { it != closedId }
}
