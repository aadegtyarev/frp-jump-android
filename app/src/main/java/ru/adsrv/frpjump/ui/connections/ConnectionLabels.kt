package ru.adsrv.frpjump.ui.connections

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.ports.PortKind

/** "Web", "SSH", "TCP" in [context]'s language: the kind as rows and the sheet say it. */
fun kindWord(context: Context, kind: PortKind): String = context.getString(
    when (kind) {
        PortKind.WEB -> R.string.kind_web
        PortKind.SSH -> R.string.kind_ssh
        PortKind.TCP -> R.string.kind_tcp
    },
)

/** "Web 8080" for a connection without a name of its own, in [context]'s language. */
fun genericConnectionName(context: Context): (PortKind, Int) -> String =
    { kind, port -> context.getString(R.string.connection_default_name, kindWord(context, kind), port) }

/** What [connection] is called, in [context]'s language (notifications: a localized context). */
fun connectionLabel(context: Context, connection: Connection): String = connection.label(genericConnectionName(context))

/** What [connection] is called, in the app's language. */
@Composable
@ReadOnlyComposable
fun connectionLabel(connection: Connection): String {
    // Read so that a new language recomposes this.
    LocalConfiguration.current
    return connectionLabel(LocalContext.current, connection)
}
