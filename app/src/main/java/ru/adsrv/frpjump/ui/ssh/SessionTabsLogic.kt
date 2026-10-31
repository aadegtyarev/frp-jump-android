package ru.adsrv.frpjump.ui.ssh

import ru.adsrv.frpjump.ssh.SshAuth
import ru.adsrv.frpjump.ssh.SshAuthMethod
import ru.adsrv.frpjump.ssh.SshLogin

/** Who a session tab logs in as and where: what its label shows. */
data class TabTarget(val user: String, val device: String, val port: Int)

/**
 * The tabs' labels, in order: "user · device" (":port" when not 22), and
 * " · 2", " · 3" on the second and later tabs to the same user, device and
 * port, so that two shells there can be told apart.
 */
fun tabLabels(targets: List<TabTarget>): List<String> {
    val seen = HashMap<TabTarget, Int>()
    return targets.map { t ->
        val n = (seen[t] ?: 0) + 1
        seen[t] = n
        val base = "${t.user} · ${t.device}" + if (t.port != 22) ":${t.port}" else ""
        if (n > 1) "$base · $n" else base
    }
}

/**
 * Whether "+" can open another session with [auth] as it is, without the
 * login dialog: a key, or a password the session holds in memory, or a
 * remembered one that is still kept ([hasRememberedPassword], asked only then).
 */
fun reusesLogin(auth: SshAuth, hasRememberedPassword: () -> Boolean): Boolean = when (auth) {
    SshAuth.Key -> true
    is SshAuth.Password -> auth.typed != null || hasRememberedPassword()
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
