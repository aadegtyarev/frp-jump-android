package ru.adsrv.frpjump.settings

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.ssh.MemoryStorage
import ru.adsrv.frpjump.store.TextStorage

class MqttIngressSettingsTest {
    @Test fun `old files retain unrelated preferences and receive finite defaults`() {
        val settings = SettingsStore(MemoryStorage("""{"direct":false,"terminal_fullscreen":true}""")).settings.value
        assertFalse(settings.direct)
        assertTrue(settings.terminalFullscreen)
        assertEquals(MqttIngressLimits(), settings.mqttIngress)
        assertTrue(validMqttIngressLimits(settings.mqttIngress))
    }

    @Test fun `invalid new field types and unknown overflow do not reset unrelated preferences`() {
        for (group in listOf(
            """{"payload_bytes":"bad","queue_bytes":-1,"queue_messages":0,"overflow":"unknown"}""",
            "false", "null", "[]",
        )) {
            val settings = SettingsStore(MemoryStorage("""{"direct":false,"terminal_fullscreen":true,"mqtt_ingress":$group}""")).settings.value
            assertFalse("Bad new settings must not reset direct=false: $group", settings.direct)
            assertTrue(settings.terminalFullscreen)
            assertEquals(MqttIngressLimits(), settings.mqttIngress)
        }
    }

    @Test fun `inconsistent byte pair falls back while valid count and action survive`() {
        val settings = SettingsStore(MemoryStorage("""{"direct":false,"mqtt_ingress":{"payload_bytes":16777216,"queue_bytes":1024,"queue_messages":17,"overflow":"stop_session"}}""")).settings.value
        assertFalse(settings.direct)
        assertEquals(MqttIngressLimits.DEFAULT_PAYLOAD_BYTES, settings.mqttIngress.payloadBytes)
        assertEquals(MqttIngressLimits.DEFAULT_QUEUE_BYTES, settings.mqttIngress.queueBytes)
        assertEquals(17, settings.mqttIngress.queueMessages)
        assertEquals(MqttOverflow.STOP_SESSION, settings.mqttIngress.overflow)
        assertTrue(validMqttIngressLimits(settings.mqttIngress))
    }

    @Test fun `invalid update cannot publish or write and valid choices survive reopen`() {
        val storage = MemoryStorage()
        val store = SettingsStore(storage)
        val before = store.settings.value
        assertThrows(IllegalArgumentException::class.java) {
            store.update { it.copy(mqttIngress = MqttIngressLimits(queueMessages = 0)) }
        }
        assertSame(before, store.settings.value)
        assertNull(storage.read())
        val limits = MqttIngressLimits(2048, 8192, 7, MqttOverflow.STOP_SESSION)
        store.update { it.copy(mqttIngress = limits, direct = false) }
        assertEquals(limits, SettingsStore(storage).settings.value.mqttIngress)
        assertFalse(SettingsStore(storage).settings.value.direct)
    }

    @Test fun `failed write keeps the old visible configuration`() {
        val storage = object : TextStorage {
            override fun read(): String? = null
            override fun write(text: String) { throw IOException("Synthetic settings write fault") }
            override fun delete() = Unit
        }
        val store = SettingsStore(storage)
        val before = store.settings.value
        assertThrows(IOException::class.java) { store.update { it.copy(mqttIngress = MqttIngressLimits(queueMessages = 7)) } }
        assertSame(before, store.settings.value)
    }
}
