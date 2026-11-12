package ru.adsrv.frpjump.ui

import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.locale.Texts
import ru.adsrv.frpjump.ops.OpFailure
import ru.adsrv.frpjump.ops.OpRefusal
import ru.adsrv.frpjump.ops.TunnelFailure
import ru.adsrv.frpjump.ops.TunnelRefusal

/** The owner's wording of a refused operation, in the app's language; the assistant words the same results for the model elsewhere. */
fun OpRefusal.userText(): String = when (this) {
    TunnelRefusal.ProfileGone -> Texts.get(R.string.profile_gone)
    else -> Texts.get(R.string.files_tunnel_down)
}

/** The owner's wording of a failed operation; a payload that the tunnel manager or the server wrote is shown as it is. */
fun OpFailure.userText(): String = when (this) {
    is TunnelFailure.DidNotComeUp -> detail ?: Texts.get(R.string.files_tunnel_down)
    is TunnelFailure.DeviceNotListening -> detail
    else -> Texts.get(R.string.files_tunnel_down)
}
