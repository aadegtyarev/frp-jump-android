package ru.adsrv.frpjump.ui.plugins

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.locale.Texts
import ru.adsrv.frpjump.mqtt.plugins.PluginStore
import ru.adsrv.frpjump.notices.NoticeKind
import ru.adsrv.frpjump.ui.userMessage

/**
 * A change to the plugin settings, written off the main thread in the
 * app's scope: closing a screen or a sheet right after the tap must not
 * lose it. The store keeps it in memory before writing, so the switch
 * moves at once; a failed write says so (docs/contract.md scenario 12,
 * "Failures") and the new state stays for the rest of the run.
 */
fun savePluginChange(app: FrpJumpApp, edit: PluginStore.() -> Unit) {
    app.appScope.launch {
        val failure = withContext(Dispatchers.IO) { runCatching { app.mqttPlugins.edit() }.exceptionOrNull() }
        if (failure != null) app.notices.show(Texts.get(R.string.settings_save_failed, failure.userMessage()), NoticeKind.WARN, long = true)
    }
}
