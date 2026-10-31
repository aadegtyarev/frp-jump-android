package ru.adsrv.frpjump.mqtt

import org.junit.Assert.assertEquals
import org.junit.Test

class PublishStartTest {
    private fun value(payload: ByteArray, retain: Boolean = true) =
        TopicValue.of(payload, retain, qos = 0, at = 1, version = MqttVersion.V5, previous = null)

    @Test
    fun `a topic's card starts on its current text value`() {
        val path = MqttPath.parse("/devices/x/controls/K1")
        assertEquals(PublishStart("/devices/x/controls/K1", "1", focusPayload = true), publishFromCard(path, value("1".toByteArray())))
        // Not text, too large, empty or cleared: the value field starts empty, still focused.
        assertEquals("", publishFromCard(path, value(byteArrayOf(0xff.toByte(), 0xfe.toByte()))).payload)
        assertEquals("", publishFromCard(path, value(ByteArray(PREFILL_MAX_BYTES + 1) { 'a'.code.toByte() })).payload)
        assertEquals("a".repeat(PREFILL_MAX_BYTES), publishFromCard(path, value(ByteArray(PREFILL_MAX_BYTES) { 'a'.code.toByte() })).payload)
        assertEquals(PublishStart(path.format(), "", focusPayload = true), publishFromCard(path, value(ByteArray(0))))
    }

    @Test
    fun `a branch without a value starts on the topic`() {
        assertEquals(PublishStart("/devices/x", "", focusPayload = false), publishFromCard(MqttPath.parse("/devices/x"), null))
    }

    @Test
    fun `publish here starts with the level and a slash, ready for the new name`() {
        assertEquals(PublishStart("/devices/x/", "", focusPayload = false), publishAtLevel(MqttPath.parse("/devices/x")))
        // The branch of the topics starting with `/`.
        assertEquals("/", publishAtLevel(MqttPath(listOf(""))).topic)
        assertEquals("a/", publishAtLevel(MqttPath.parse("a")).topic)
        assertEquals("", publishAtLevel(MqttPath.ROOT).topic)
    }
}
