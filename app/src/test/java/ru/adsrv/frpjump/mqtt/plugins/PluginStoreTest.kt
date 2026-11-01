package ru.adsrv.frpjump.mqtt.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.mqtt.connectionKey
import ru.adsrv.frpjump.ssh.MemoryStorage
import ru.adsrv.frpjump.store.TextStorage
import java.io.IOException

class PluginStoreTest {
    private val v2 = MemoryStorage()
    private val old = MemoryStorage()
    private val corrupt = MemoryStorage()
    private val oldCorrupt = MemoryStorage()
    private var ids = 0

    private fun store(file: TextStorage = v2, legacy: TextStorage = old) = PluginStore(file, legacy, corrupt, oldCorrupt, newId = { "new-${++ids}" })

    /** A file that cannot be read (an I/O error); counts writes. */
    private class Failing : TextStorage {
        var writes = 0

        override fun read(): String? = throw IOException("EIO")

        override fun write(text: String) {
            writes++
        }

        override fun delete() = Unit
    }

    /** An old per-connection file, connections in this order. */
    private fun oldFile(vararg connections: Pair<Pair<String, String>, String>): String =
        connections.joinToString(",", "{", "}") { (pc, body) -> JsonPrimitive(connectionKey(pc.first, pc.second)).toString() + ":" + body }

    private val app get() = store().get().app.plugins

    @Test
    fun `a fresh install has both plugins on, nothing set up, and writes the v2 file`() {
        val s = store()
        val c = s.get("p", "c")
        assertTrue(c.wirenBoardOn)
        assertTrue(c.statusCommand.on)
        assertEquals(emptySet<String>(), c.statusCommand.presets)
        assertEquals(WirenBoardSettings(), c.wirenBoard)
        val json = Json.parseToJsonElement(v2.text!!).jsonObject
        assertEquals(2, json["version"]!!.jsonPrimitive.int)
        assertNotNull(json["app"]!!.jsonObject["plugins"]!!.jsonObject["wirenboard"])
        assertNotNull(json["app"]!!.jsonObject["plugins"]!!.jsonObject["status-command"])
        assertFalse(s.unreadable())
    }

    @Test
    fun `migration keeps on and off per connection exactly`() {
        old.text = oldFile(
            ("p" to "home") to """{"wirenBoard":{"on":false}}""",
            ("p" to "wb") to """{"wirenBoard":{"on":true,"tabShown":true}}""",
            ("q" to "x") to """{"statusCommand":{"on":false}}""",
        )
        val s = store()
        assertTrue(s.get().app.plugins.wirenBoard.on)
        assertTrue(s.get().app.plugins.statusCommand.on)
        assertFalse(s.get("p", "home").wirenBoardOn)
        assertTrue(s.get("p", "home").statusCommand.on)
        assertTrue(s.get("p", "wb").wirenBoardOn)
        assertTrue(s.get("p", "wb").wbTabShown)
        assertTrue(s.get("q", "x").wirenBoardOn)
        assertFalse(s.get("q", "x").statusCommand.on)
        // A connection with everything on has no own setting.
        assertNull(s.get().own(pluginKey("p", "wb"), PluginIds.WIREN_BOARD))
        assertEquals(Exceptions(on = 0, off = 1), s.get().exceptionsOf(PluginIds.WIREN_BOARD))
        assertEquals(Exceptions(on = 0, off = 1), s.get().exceptionsOf(PluginIds.STATUS_COMMAND))
    }

    @Test
    fun `migration merges presets and rules only from connections with the plugin on`() {
        val ownRule = """{"status":"home/{x...}/state","command":"home/{x...}/cmd","id":"r1"}"""
        old.text = oldFile(
            ("p" to "off") to """{"statusCommand":{"on":false,"presets":["tasmota"],"rules":[{"status":"dead/{x}","id":"r9"}]}}""",
            ("p" to "a") to """{"statusCommand":{"presets":["zigbee2mqtt"],"rules":[$ownRule]}}""",
            ("p" to "b") to """{"statusCommand":{"presets":["wirenboard","zigbee2mqtt"]}}""",
        )
        val sc = app.statusCommand
        assertEquals(setOf(PluginIds.PRESET_ZIGBEE2MQTT, PluginIds.PRESET_WIREN_BOARD), sc.presets)
        assertEquals(listOf(RuleSpec("home/{x...}/state", "home/{x...}/cmd", id = "r1")), sc.rules)
        // The rule works on every connection with the plugin on.
        assertEquals(sc.rules, store().get("p", "b").statusCommand.rules)
    }

    @Test
    fun `migration keeps rules in file order, drops equal ones, keeps different commands and renews taken ids`() {
        old.text = oldFile(
            ("p" to "a") to """{"statusCommand":{"rules":[
                {"status":"s/{x}","command":"s/{x}/one","id":"r1","name":"first"},
                {"status":"t/{x}","except":["t/no"],"id":"r2"}]}}""",
            ("p" to "b") to """{"statusCommand":{"rules":[
                {"status":"s/{x}","command":"s/{x}/two","id":"r1"},
                {"status":"t/{x}","except":["t/no"],"id":"r7","name":"dup"},
                {"status":"u/{x}","id":"r2","name":"kept"}]}}""",
        )
        val rules = app.statusCommand.rules
        assertEquals(
            listOf(
                RuleSpec("s/{x}", "s/{x}/one", name = "first", id = "r1"),
                RuleSpec("t/{x}", except = listOf("t/no"), id = "r2"),
                RuleSpec("s/{x}", "s/{x}/two", id = "new-1"),
                RuleSpec("u/{x}", name = "kept", id = "new-2"),
            ),
            rules,
        )
        // The first rule for a status template wins.
        val target = commandFor("s/a", rulesInForce(app.statusCommand, emptyList()))
        assertEquals("s/a/one", (target as CommandTarget.Elsewhere).topic)
    }

    @Test
    fun `the Wiren Board preset counts as done if any connection had seen the tab`() {
        old.text = oldFile(
            ("p" to "a") to """{}""",
            ("p" to "b") to """{"statusCommand":{"on":false,"wirenBoardTabSeen":true}}""",
        )
        assertTrue(app.statusCommand.wirenBoardPresetAutoDone)
        old.text = oldFile(("p" to "a") to """{}""")
        v2.text = null
        assertFalse(app.statusCommand.wirenBoardPresetAutoDone)
    }

    @Test
    fun `migration runs once, writes v2 at once and leaves the old file as it was`() {
        old.text = oldFile(("p" to "home") to """{"wirenBoard":{"on":false}}""")
        val before = old.text
        val s = store()
        s.get()
        assertNotNull(v2.text)
        assertEquals(before, old.text)
        // A change after the upgrade goes to v2 only; a later start does not migrate again.
        s.switch("p", "home", PluginIds.WIREN_BOARD, on = true)
        assertEquals(before, old.text)
        assertTrue(store().get("p", "home").wirenBoardOn)
        assertTrue(store().get().connections.isEmpty())
    }

    @Test
    fun `an old file that does not parse migrates to the defaults and is copied aside once`() {
        old.text = "not json"
        assertEquals(PluginsFile(), store().get())
        assertNotNull(v2.text)
        assertEquals("not json", old.text)
        assertEquals("not json", oldCorrupt.text)
        v2.text = null
        old.text = "[]"
        store().get()
        assertEquals("not json", oldCorrupt.text)
    }

    @Test
    fun `an old entry that does not read is skipped, the others migrate`() {
        old.text = oldFile(
            ("p" to "bad") to """{"wirenBoard":{"on":"maybe"}}""",
            ("p" to "home") to """{"wirenBoard":{"on":false},"statusCommand":{"rules":[{"status":"a/{x}","id":"r1"}]}}""",
        )
        val s = store()
        assertFalse(s.get("p", "home").wirenBoardOn)
        assertEquals(listOf("r1"), s.get().app.plugins.statusCommand.rules.map { it.id })
        assertNull(oldCorrupt.text)
    }

    @Test
    fun `a v2 file that cannot be read gives the defaults, writes nothing and copies nothing`() {
        val failing = Failing()
        old.text = oldFile(("p" to "home") to """{"wirenBoard":{"on":false}}""")
        val s = store(file = failing)
        assertEquals(PluginsFile(), s.get())
        assertTrue(s.unreadable())
        // Not migrated over it either.
        assertTrue(s.get("p", "home").wirenBoardOn)
        s.updateByApp { it.wbTabAppeared(pluginKey("p", "home")) }
        assertEquals(0, failing.writes)
        assertNull(corrupt.text)
    }

    @Test
    fun `an old file that cannot be read gives the defaults and does not write v2, so the next start migrates`() {
        val s = store(legacy = Failing())
        assertEquals(PluginsFile(), s.get())
        assertTrue(s.unreadable())
        assertNull(v2.text)
        assertNull(oldCorrupt.text)
        old.text = oldFile(("p" to "home") to """{"wirenBoard":{"on":false}}""")
        assertFalse(store().get("p", "home").wirenBoardOn)
    }

    @Test
    fun `an unreadable v2 file reads as the defaults, is copied aside once and kept until the owner changes something`() {
        old.text = oldFile(("p" to "home") to """{"wirenBoard":{"on":false}}""")
        v2.text = "{broken"
        val s = store()
        assertEquals(PluginsFile(), s.get())
        assertTrue(s.unreadable())
        assertEquals("{broken", corrupt.text)
        // Not migrated: the v2 file exists.
        assertTrue(s.get("p", "home").wirenBoardOn)
        // What the app does by itself is not written over it.
        s.updateByApp { it.wbTabAppeared(pluginKey("p", "home")) }
        assertEquals("{broken", v2.text)
        assertTrue(s.get("p", "home").wbTabShown)
        // The first copy is the one kept.
        v2.text = "{second"
        store().get()
        assertEquals("{broken", corrupt.text)
        // The owner's change is written, and the warning goes.
        s.switch("p", "home", PluginIds.WIREN_BOARD, on = false)
        assertFalse(s.unreadable())
        assertFalse(store().get("p", "home").wirenBoardOn)
    }

    @Test
    fun `a higher version reads as the defaults`() {
        v2.text = """{"version":3,"app":{"plugins":{"wirenboard":{"on":false}}}}"""
        val s = store()
        assertTrue(s.get("p", "c").wirenBoardOn)
        assertTrue(s.unreadable())
        assertEquals(v2.text, corrupt.text)
    }

    @Test
    fun `missing plugins and fields have their defaults, unknown ones are ignored`() {
        v2.text = """{"version":2,"app":{"plugins":{"status-command":{"presets":["tasmota"]},"later":{"on":false}}},
            "connections":{"p/c":{"plugins":{"later":{"on":true}}}}}"""
        val s = store()
        assertTrue(s.get("p", "c").wirenBoardOn)
        assertEquals(setOf(PluginIds.PRESET_TASMOTA), s.get("p", "c").statusCommand.presets)
        assertTrue(s.get("p", "c").statusCommand.on)
        assertFalse(s.unreadable())
    }

    @Test
    fun `settings come back after a restart`() {
        val rule = RuleSpec("home/{x...}/state", "home/{x...}/cmd", listOf("home/x/state"), "home", id = "r1")
        val s = store()
        s.update { f -> f.withApp { it.copy(statusCommand = it.statusCommand.copy(presets = setOf(PluginIds.PRESET_TASMOTA), rules = listOf(rule))) } }
        s.switch("p", "c", PluginIds.STATUS_COMMAND, on = false)
        val again = store()
        assertEquals(listOf(rule), again.get().app.plugins.statusCommand.rules)
        assertEquals(setOf(PluginIds.PRESET_TASMOTA), again.get("p", "other").statusCommand.presets)
        assertFalse(again.get("p", "c").statusCommand.on)
    }

    @Test
    fun `every change is counted`() {
        val s = store()
        val before = s.changes.value
        s.switch("p", "c", PluginIds.WIREN_BOARD, on = false)
        assertEquals(before + 1, s.changes.value)
    }

    @Test
    fun `deleting a connection removes its keys from both files and keeps the rules`() {
        old.text = oldFile(
            ("p" to "c1") to """{"wirenBoard":{"on":false},"statusCommand":{"rules":[{"status":"a/{x}","id":"r1"}]}}""",
            ("p" to "c2") to """{"wirenBoard":{"on":false}}""",
        )
        val s = store()
        s.forget("p", "c1")
        assertTrue(store().get("p", "c1").wirenBoardOn)
        assertFalse(store().get("p", "c2").wirenBoardOn)
        assertEquals(listOf("r1"), store().get().app.plugins.statusCommand.rules.map { it.id })
        assertEquals(setOf(connectionKey("p", "c2")), Json.parseToJsonElement(old.text!!).jsonObject.keys)
    }

    @Test
    fun `forgetting a profile removes its connections from both files, the last profile both files`() {
        old.text = oldFile(
            ("p" to "c1") to """{"wirenBoard":{"on":false}}""",
            ("q" to "c1") to """{"wirenBoard":{"on":false},"statusCommand":{"presets":["tasmota"]}}""",
        )
        val s = store()
        s.forgetProfile("p")
        assertTrue(store().get("p", "c1").wirenBoardOn)
        assertFalse(store().get("q", "c1").wirenBoardOn)
        val kept = Json.parseToJsonElement(old.text!!).jsonObject
        assertEquals(setOf(connectionKey("q", "c1")), kept.keys)
        // The rest of the old file is as it was.
        assertEquals(Json.parseToJsonElement("""{"wirenBoard":{"on":false},"statusCommand":{"presets":["tasmota"]}}"""), kept[connectionKey("q", "c1")])
        s.wipe()
        assertNull(v2.text)
        assertNull(old.text)
        assertEquals(PluginsFile(), s.get())
    }

    @Test
    fun `a scrub leaves a missing or unreadable old file alone`() {
        val s = store()
        s.forget("p", "c")
        assertNull(old.text)
        old.text = "not json"
        s.forgetProfile("p")
        assertEquals("not json", old.text)
    }

    @Test
    fun `the file has the contract's shape`() {
        val s = store()
        s.switch("p", "c", PluginIds.WIREN_BOARD, on = false)
        s.updateByApp { it.wbTabAppeared(pluginKey("p", "c")) }
        val json = Json.parseToJsonElement(v2.text!!).jsonObject
        val conn = json["connections"]!!.jsonObject["p/c"]!!.jsonObject
        assertEquals(Json.parseToJsonElement("""{"wirenboard":{"on":false}}"""), conn["plugins"])
        assertEquals(true, conn["wbTabShown"]!!.jsonPrimitive.content.toBoolean())
        val sc = json["app"]!!.jsonObject["plugins"]!!.jsonObject["status-command"] as JsonObject
        assertEquals(setOf("on", "presets", "rules", "wirenBoardPresetAutoDone"), sc.keys)
        val wb = json["app"]!!.jsonObject["plugins"]!!.jsonObject["wirenboard"] as JsonObject
        assertEquals(setOf("on", "titleLanguage", "showHidden"), wb.keys)
    }
}
