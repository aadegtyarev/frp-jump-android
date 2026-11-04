package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.agent.AgentModule
import ru.adsrv.frpjump.settings.SettingsStore

/** App-owned binding of the committed opt-in preference to the execution kernel. */
fun bindAssistantLifecycle(settings: SettingsStore, scope: CoroutineScope): AgentModule {
    val module = AgentModule(
        initiallyEnabled = true,
        disableVersion = { settings.assistantAvailability.disableVersion },
        allowedNow = { settings.assistantAvailability.enabled },
    )
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
        // Re-read authority, not an emitted bool: notifications can be stale or conflated.
        settings.assistantDisables.collect { module.refreshAvailability() }
    }
    return module
}
