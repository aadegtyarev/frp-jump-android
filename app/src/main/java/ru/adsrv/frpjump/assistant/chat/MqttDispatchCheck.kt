package ru.adsrv.frpjump.assistant.chat

import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.settings.*

/** Validates a remembered basis off the scheduler and returns its in-memory dispatch check. */
internal fun mqttDispatchCheck(support: ConfirmationSupport?, offCount: Long?, basis: NoQuestion?, target: GrantTarget): (() -> Boolean)? {
    if (support == null || offCount == null) return null
    val tool = AssistantToolId.MQTT_PUBLISH
    val key = GrantKey(tool.actionClass, target)
    val persistent = if (basis == NoQuestion.CONNECTION) support.connection?.dispatchStamp(tool, key) else null
    if (basis == NoQuestion.CONNECTION && persistent == null) return null
    return {
        support.grants.inEffect(tool) && support.grants.toolOffCount(tool) == offCount && when (basis) {
            null -> true
            NoQuestion.OFF -> support.policy().resolve() == ConfirmationMode.OFF
            NoQuestion.SESSION -> support.grants.covers(tool, key)
            NoQuestion.CONNECTION -> persistent != null && support.connection?.dispatchValid(persistent) == true
        }
    }
}
