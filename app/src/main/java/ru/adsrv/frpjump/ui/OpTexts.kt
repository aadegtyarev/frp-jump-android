package ru.adsrv.frpjump.ui

import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.locale.Texts
import ru.adsrv.frpjump.ops.DirectRefusal
import ru.adsrv.frpjump.ops.OpResult
import ru.adsrv.frpjump.ops.TunnelFailure
import ru.adsrv.frpjump.ops.TunnelRefusal

/*
 * The owner's wording of operation results, in the app's language. Each screen-facing operation gets its own exhaustive `when`; the
 * assistant words the same results for the model elsewhere (kinds only). A typed error keeps, for the owner's screens only, a server's
 * own `detail` (shown as it is), the host and the system's exception messages; none of it goes to the model.
 */

fun TunnelRefusal.userText(): String = when (this) {
    TunnelRefusal.ProfileGone -> Texts.get(R.string.profile_gone)
}

fun TunnelFailure.userText(): String = when (this) {
    is TunnelFailure.DidNotComeUp -> error?.userText() ?: Texts.get(R.string.files_tunnel_down)
    is TunnelFailure.DeviceNotListening -> reason.userText()
}

fun DirectRefusal.userText(): String = when (this) {
    DirectRefusal.NoNetwork -> Texts.get(R.string.keep_no_network)
}

/** The notice for a result of `TunnelConnect` or `TunnelDown`; null when nothing is to be said. They only return tunnel refusals. */
fun OpResult<Unit>.tunnelActionNotice(): String? = (this as? OpResult.Refused)?.let { (it.reason as TunnelRefusal).userText() }

/** The notice for a result of `TunnelUp`; null when the tunnel is up. `TunnelUp` only returns tunnel refusals and failures. */
fun OpResult<Int>.tunnelNotice(): String? = when (this) {
    is OpResult.Done -> null
    is OpResult.Refused -> (reason as TunnelRefusal).userText()
    is OpResult.Failed -> (reason as TunnelFailure).userText()
}
