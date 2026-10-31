package ru.adsrv.frpjump.restore

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.tunnel.TunnelService

/**
 * After a reboot with tunnels up, starts the tunnels service; the app's
 * start (this process) has every profile listed in the [RestoreRecord]
 * poll and bring its tunnels back. Android allows a foreground service
 * start from `BOOT_COMPLETED` (12+); Android 15 bars only some types from
 * it, not `specialUse`.
 *
 * Only `BOOT_COMPLETED`, after the owner unlocked the phone: the
 * credentials are in credential-encrypted storage under Keystore keys that
 * need the unlock, so there is nothing to start before it
 * (`LOCKED_BOOT_COMPLETED`).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as FrpJumpApp
        val result = goAsync()
        app.appScope.launch {
            try {
                // Storage: off the main thread. Read once, shared with the app's start.
                val record = withContext(Dispatchers.IO) { app.restoreRecord.load() }
                if (tunnelsToRestoreOnBoot(record) > 0) TunnelService.start(app)
            } finally {
                result.finish()
            }
        }
    }
}
