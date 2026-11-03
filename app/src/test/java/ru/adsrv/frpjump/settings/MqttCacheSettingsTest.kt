package ru.adsrv.frpjump.settings

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.ssh.MemoryStorage
import ru.adsrv.frpjump.store.TextStorage

class MqttCacheSettingsTest {
    @Test fun `old preferences get finite cache defaults independently from ingress`() {
        val settings = SettingsStore(MemoryStorage("""{"direct":false,"mqtt_ingress":{"queue_messages":7}}""")).settings.value
        assertFalse(settings.direct)
        assertEquals(7, settings.mqttIngress.queueMessages)
        assertEquals(MqttCacheLimits(), settings.mqttCache)
        assertTrue(validMqttCacheLimits(settings.mqttCache))
    }

    @Test fun `bad cache field types and enum do not reset unrelated preferences`() {
        for (group in listOf("false", "null", "[]", """{"storage_bytes":"bad","topics":-1,"history":0,"overflow":"unknown"}""")) {
            val settings = SettingsStore(MemoryStorage("""{"direct":false,"terminal_fullscreen":true,"mqtt_cache":$group}""")).settings.value
            assertFalse(settings.direct)
            assertTrue(settings.terminalFullscreen)
            assertEquals(MqttCacheLimits(), settings.mqttCache)
        }
    }

    @Test fun `each valid cache choice survives a different invalid field`() {
        val settings = SettingsStore(MemoryStorage("""{"mqtt_cache":{"storage_bytes":1,"topics":17,"history":3,"overflow":"stop_session"}}""")).settings.value
        assertEquals(MqttCacheLimits.DEFAULT_STORAGE_BYTES, settings.mqttCache.storageBytes)
        assertEquals(17, settings.mqttCache.topics)
        assertEquals(3, settings.mqttCache.history)
        assertEquals(MqttOverflow.STOP_SESSION, settings.mqttCache.overflow)
    }

    @Test fun `invalid update does not write or publish and valid limits survive reopen`() {
        val memory = MemoryStorage()
        val store = SettingsStore(memory)
        val before = store.settings.value
        for (bad in listOf(MqttCacheLimits(storageBytes = 0), MqttCacheLimits(topics = 0), MqttCacheLimits(history = 201))) {
            assertThrows(IllegalArgumentException::class.java) { store.update { it.copy(mqttCache = bad) } }
            assertSame(before, store.settings.value)
            assertNull(memory.read())
        }
        val selected = MqttCacheLimits(8192, 9, 2, MqttOverflow.STOP_SESSION)
        store.update { it.copy(mqttCache = selected, direct = false) }
        assertEquals(selected, SettingsStore(memory).settings.value.mqttCache)
        assertFalse(SettingsStore(memory).settings.value.direct)
    }

    @Test fun `write failure preserves the visible cache configuration`() {
        val store = SettingsStore(object : TextStorage {
            override fun read(): String? = null
            override fun write(text: String) { throw IOException("Synthetic cache settings failure") }
            override fun delete() = Unit
        })
        val before = store.settings.value
        assertThrows(IOException::class.java) { store.update { it.copy(mqttCache = MqttCacheLimits(history = 1)) } }
        assertSame(before, store.settings.value)
    }
}
