package ru.adsrv.frpjump.assistant

import java.io.File

/** The service tool's spec with every tool off (the longest one): what a request with no tool on carries beyond the prompt. */
internal val requestToolSpec get() = AssistantRequestTool(AssistantToolId.switches, emptySet()).spec

/** The shipped prompt assets, read from `src/main/assets` (JVM tests run in the module directory), without Android. */
internal object TestPrompts {
    /** Every tool switch on. */
    val allTools = ru.adsrv.frpjump.settings.AssistantTools(search = true, readPage = true, inspectImage = true, profileInformation = true, sshCommand = true,
        terminalTabs = true, terminalInput = true, tunnels = true, waits = true, sessions = true, skills = true, mqttRead = true, mqttPublish = true, httpGet = true, httpPost = true, tempFiles = true)
    val real: AssistantPrompts by lazy { AssistantPrompts.load { File("src/main/assets/$it").inputStream() } }
    val lazy: Lazy<AssistantPrompts> = lazy { real }
    /** Dummy strings for tests that never inspect prompt text. */
    val placeholder: Lazy<AssistantPrompts> = lazy { AssistantPrompts("assistant system prompt", "assistant summary prompt") }
}

/** A catalog fixture with no MQTT explorers and no broker traffic. */
internal fun noMqttReader(): AssistantMqttRead {
    val storage = ru.adsrv.frpjump.ssh.MemoryStorage()
    val env = ru.adsrv.frpjump.mqtt.MqttEnvironment(
        ru.adsrv.frpjump.mqtt.MqttLogins(storage, ru.adsrv.frpjump.profiles.FakeKeystore().box("synthetic-mqtt")),
        ru.adsrv.frpjump.mqtt.PinStore(storage), ru.adsrv.frpjump.mqtt.MqttConnector { _, _, _ -> error("must not connect") },
        ru.adsrv.frpjump.mqtt.FakeScheduler())
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
    val sessions = ru.adsrv.frpjump.mqtt.MqttSessions(env, kotlinx.coroutines.flow.emptyFlow(), scope, kotlinx.coroutines.Dispatchers.Unconfined)
    return AssistantMqttRead(TunnelFixture().tunnels, sessions, kotlinx.coroutines.flow.MutableStateFlow(emptySet())) { null }
}

/** Synthetic connected explorer used only for catalog and confirmation fixtures. */
internal fun mqttPublisherFixture() = AssistantMqttPublishTest.Rig().service
