package ru.adsrv.frpjump.mqtt.plugins.wirenboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.mqtt.MqttPath
import ru.adsrv.frpjump.mqtt.MqttVersion
import ru.adsrv.frpjump.mqtt.TopicTree

class WbDevicesTest {
    private fun texts(vararg topics: Pair<String, String>): Map<MqttPath, String> = topics.associate { (t, v) -> MqttPath.parse(t) to v }

    /** A tree holding [topics] as retained values, read as the tab reads it. */
    private fun tree(vararg topics: Pair<String, String>) = TopicTree().apply {
        topics.forEach { (t, v) -> receive(MqttPath.parse(t), v.toByteArray(), retain = true, qos = 0, MqttVersion.V5) }
    }

    @Test
    fun `devices are listed when a control has meta, by title, with their ids`() {
        val t = texts(
            "/devices/wb-mr6c_2/meta" to """{"driver":"wb-modbus","title":{"en":"MR6C 2","ru":"Реле 2"}}""",
            "/devices/wb-mr6c_2/controls/K1/meta" to """{"type":"switch","order":1}""",
            "/devices/wb-msw-v3_21/meta/name" to "Sensor 21",
            "/devices/wb-msw-v3_21/controls/Temperature/meta/type" to "temperature",
            // No control with meta: not listed.
            "/devices/other/meta" to """{"title":"Other"}""",
            "/devices/bare/controls/x" to "1",
        )
        val ru = wbDevices(t, "ru")
        assertEquals(listOf("Sensor 21", "Реле 2"), ru.map { it.title })
        assertEquals(listOf("wb-msw-v3_21", "wb-mr6c_2"), ru.map { it.id })
        assertEquals("MR6C 2", wbDevices(t, "en").first { it.id == "wb-mr6c_2" }.title)
    }

    @Test
    fun `a device without any title shows its id, and titles sort naturally`() {
        val t = texts(
            "/devices/d10/controls/a/meta" to """{"type":"value"}""",
            "/devices/d2/controls/a/meta" to """{"type":"value"}""",
        )
        assertEquals(listOf("d2", "d10"), wbDevices(t, "ru").map { it.title })
    }

    @Test
    fun `errors of the device or a control mark the row`() {
        val t = texts(
            "/devices/a/controls/c/meta" to """{"type":"value"}""",
            "/devices/a/controls/c/meta/error" to "r",
            "/devices/b/controls/c/meta" to """{"type":"value"}""",
            "/devices/b/meta/error" to "r",
            "/devices/c/controls/c/meta" to """{"type":"value"}""",
        )
        val byId = wbDevices(t, "en").associateBy { it.id }
        assertTrue(byId.getValue("a").hasErrors)
        assertFalse(byId.getValue("a").deviceError)
        assertTrue(byId.getValue("b").hasErrors)
        assertTrue(byId.getValue("b").deviceError)
        assertFalse(byId.getValue("c").hasErrors)
    }

    @Test
    fun `cleared topics are absent, so emptying the error clears the marks`() {
        val tree = tree("/devices/a/controls/c/meta" to """{"type":"value"}""", "/devices/a/controls/c/meta/error" to "r")
        assertTrue(wbDevices(wbTexts(tree.values(WB_CONTROL_META_PATTERN)), "en").single().hasErrors)
        tree.receive(MqttPath.parse("/devices/a/controls/c/meta/error"), ByteArray(0), retain = true, qos = 0, MqttVersion.V5)
        assertFalse(wbDevices(wbTexts(tree.values(WB_CONTROL_META_PATTERN)), "en").single().hasErrors)
        // Cleared meta: the control and so the device are gone.
        tree.receive(MqttPath.parse("/devices/a/controls/c/meta"), ByteArray(0), retain = true, qos = 0, MqttVersion.V5)
        assertEquals(emptyList<WbDevice>(), wbDevices(wbTexts(tree.values(WB_CONTROL_META_PATTERN)), "en"))
    }

    @Test
    fun `the tab shows once a control's meta is seen`() {
        assertFalse(wbSeen(texts("/devices/a/meta" to """{"title":"A"}""", "/devices/a/controls/c" to "1")))
        assertFalse(wbSeen(texts("/devices/a/controls/c/meta" to "{}")))
        assertTrue(wbSeen(texts("/devices/a/controls/c/meta/type" to "switch")))
        // A broker without the convention.
        val plain = tree("home/light/state" to "on", "devices/a/controls/c/meta" to """{"type":"switch"}""")
        assertFalse(wbSeen(wbTexts(plain.values(WB_CONTROL_META_PATTERN))))
    }

    @Test
    fun `a device's controls come by order, then by id, hidden ones left out`() {
        val t = texts(
            "/devices/d/meta" to """{"title":"D"}""",
            "/devices/d/controls/K10/meta" to """{"type":"switch"}""",
            "/devices/d/controls/K2/meta" to """{"type":"switch"}""",
            "/devices/d/controls/Third/meta" to """{"type":"value","order":3}""",
            "/devices/d/controls/First/meta" to """{"type":"value","order":1}""",
            "/devices/d/controls/Also1/meta/order" to "1",
            "/devices/d/controls/Also1/meta/type" to "value",
            "/devices/d/controls/Hidden/meta" to """{"type":"value","order":0,"hidden":true}""",
            "/devices/d/controls/First" to "5",
        )
        val view = wbDevice("d", t, "en")!!
        assertEquals(listOf("Also1", "First", "Third", "K2", "K10"), view.controls.map { it.id })
        assertEquals("5", view.controls[1].value)
        assertNull(view.controls[0].value)
        assertEquals(MqttPath.parse("/devices/d/controls/First"), view.controls[1].path)
    }

    @Test
    fun `a control's title is its meta's in the phone's language, else its id, and its errors are read`() {
        val t = texts(
            "/devices/d/controls/K1/meta" to """{"type":"switch","title":{"en":"Lamp","ru":"Лампа"}}""",
            "/devices/d/controls/K2/meta" to """{"type":"switch"}""",
            "/devices/d/controls/K2/meta/error" to "rp",
            "/devices/d/meta/error" to "r",
        )
        val view = wbDevice("d", t, "ru")!!
        assertEquals(listOf("Лампа", "K2"), view.controls.map { it.title })
        assertEquals(listOf(WbError.Read, WbError.PolledLate), view.controls[1].errors)
        assertTrue(view.deviceError)
        assertEquals("d", view.title)
    }

    @Test
    fun `the JSON meta and the one-field topics show a device alike`() {
        val json = wbDevice("d", texts("/devices/d/controls/T/meta" to """{"type":"temperature","order":1,"readonly":true}""", "/devices/d/controls/T" to "21"), "en")!!
        val legacy = wbDevice(
            "d",
            texts(
                "/devices/d/controls/T/meta/type" to "temperature",
                "/devices/d/controls/T/meta/order" to "1",
                "/devices/d/controls/T/meta/readonly" to "1",
                "/devices/d/controls/T" to "21",
            ),
            "en",
        )!!
        assertEquals(json, legacy)
    }

    @Test
    fun `a device is read from the tree through its own pattern`() {
        val tree = tree(
            "/devices/d/meta" to """{"title":"D"}""",
            "/devices/d/controls/K1/meta" to """{"type":"switch"}""",
            "/devices/d/controls/K1" to "1",
            "/devices/e/controls/K1/meta" to """{"type":"switch"}""",
        )
        val view = wbDevice("d", wbTexts(tree.values(wbDevicePattern("d"))), "en")!!
        assertEquals("D", view.title)
        assertEquals("1", view.controls.single().value)
        assertNull(wbDevice("nope", wbTexts(tree.values(wbDevicePattern("nope"))), "en"))
        assertEquals(MqttPath.parse("/devices/d"), wbDevicePath("d"))
    }

    @Test
    fun `a hidden control's error does not mark the device`() {
        val t = texts(
            "/devices/a/controls/shown/meta" to """{"type":"value"}""",
            "/devices/a/controls/secret/meta" to """{"type":"value","hidden":true}""",
            "/devices/a/controls/secret/meta/error" to "r",
        )
        assertFalse(wbDevices(t, "en").single().hasErrors)
    }

    @Test
    fun `an empty value is an empty text for a text control and no value otherwise`() {
        val tree = tree(
            "/devices/d/controls/msg/meta" to """{"type":"text"}""",
            "/devices/d/controls/msg" to "hello",
            "/devices/d/controls/v/meta" to """{"type":"value"}""",
            "/devices/d/controls/v" to "5",
        )
        // Cleared retained values (an empty retained message under MQTT 5).
        tree.receive(MqttPath.parse("/devices/d/controls/msg"), ByteArray(0), retain = true, qos = 0, MqttVersion.V5)
        tree.receive(MqttPath.parse("/devices/d/controls/v"), ByteArray(0), retain = true, qos = 0, MqttVersion.V5)
        val view = wbDevice("d", wbTexts(tree.values(wbDevicePattern("d"))), "en")!!.controls.associateBy { it.id }
        assertEquals("", view.getValue("msg").value)
        assertNull(view.getValue("v").value)
    }
}
