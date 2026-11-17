package ru.adsrv.frpjump.assistant.chat

import ru.adsrv.frpjump.assistant.AssistantSshCommands
import ru.adsrv.frpjump.settings.ConfirmationMode
import ru.adsrv.frpjump.settings.SessionGrants
import ru.adsrv.frpjump.settings.SettingsStore

/**
 * The one place that connects a chat to the app: the policy is the settings store's committed value read at each call, "off" is committed
 * through the store, and target names come from the saved connections; session grants are the app's [grants] (which refuse a tool that is
 * switched off). The chat owner builds its support here, so tests that use this function cover the production wiring.
 */
internal fun chatConfirmationSupport(settings: SettingsStore, ssh: AssistantSshCommands?, grants: SessionGrants) = ConfirmationSupport(
    policy = { settings.assistantConfirmations },
    persistOff = { try { settings.setAssistantConfirmations(ConfirmationMode.OFF); true } catch (_: Exception) { false } },
    info = { target -> ssh?.info(target) },
    grants = grants,
)
