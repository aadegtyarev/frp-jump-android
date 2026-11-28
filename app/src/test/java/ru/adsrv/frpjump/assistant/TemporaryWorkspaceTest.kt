package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.SideEffectAdmission
import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.settings.AssistantAvailability
import ru.adsrv.frpjump.settings.AssistantTools

class TemporaryWorkspaceTest {
    private val availability = { AssistantAvailability(true, tools = AssistantTools(tempFiles = true)) }
    private val snapshot = { AssistantProviderSnapshot(AssistantProviderSettings(model = "synthetic"), "provider") }
    private fun workspace() = TemporaryWorkspace(availability, snapshot)
    private fun create(id: String, size: Int, name: String = "item") = TempAction.Create(id, name, ByteArray(size) { (it % 251).toByte() })
    @Test fun `file count and byte caps refuse without deleting or truncating`() {
        val count = workspace()
        repeat(16) { assertTrue(count.applyLocked(create("id$it", 0)) is TempResult.Created) }
        assertSame(TempResult.Full, count.applyLocked(create("extra", 0)))
        assertEquals(16, (count.applyLocked(TempAction.List) as TempResult.Listed).files.size)
        val total = workspace()
        repeat(4) { assertTrue(total.applyLocked(create("id$it", TemporaryWorkspace.MAX_FILE_BYTES)) is TempResult.Created) }
        assertSame(TempResult.Full, total.applyLocked(create("extra", 1)))
        val item = (total.applyLocked(TempAction.List) as TempResult.Listed).files.first()
        assertEquals(TemporaryWorkspace.MAX_FILE_BYTES, item.bytes)
        val preview = total.applyLocked(TempAction.Read(item.id)) as TempResult.Preview
        assertEquals(TemporaryWorkspace.MAX_FILE_BYTES, preview.size)
        assertEquals(TemporaryWorkspace.PREVIEW_BYTES, preview.bytes.size)
        assertArrayEquals(ByteArray(preview.bytes.size) { (it % 251).toByte() }, preview.bytes)
        assertSame(TempResult.Full, workspace().applyLocked(create("huge", TemporaryWorkspace.MAX_FILE_BYTES + 1)))
    }
    @Test fun `context revision and assistant disable invalidate even if reenabled`() {
        var state = availability(); var provider = snapshot()
        val store = TemporaryWorkspace({ state }, { provider }); store.stampLocked()
        store.applyLocked(create("id", 2))
        provider = provider.copy(revision = "other"); store.stampLocked()
        assertSame(TempResult.Missing, store.applyLocked(TempAction.Read("id")))
        store.applyLocked(create("new", 2))
        state = state.copy(disableVersion = 1, enabled = true); store.stampLocked()
        assertSame(TempResult.Missing, store.applyLocked(TempAction.Read("new")))
    }
    @Test fun `explicit create list and preview preserve UTF8 and page all IDs`() = runBlocking {
        val store = workspace(); val access = TemporaryAccess { action, job -> if (job?.isActive == true) store.applyLocked(action) else null }
        val admission = SideEffectAdmission { true }
        val create = TemporaryCreateTool(); val list = TemporaryListTool(1000); val read = TemporaryReadTool(1000)
        fun obj(name: String, text: String) = buildJsonObject { put("name", name); put("text", text) }
        withContext(admission + access) {
            val made = create.execute(obj("x.txt", "Привет ✓")); assertEquals(ToolOutcome.SUCCESS, made.outcome)
            val id = Json.parseToJsonElement(made.text).jsonObject.getValue("file").jsonObject.getValue("id").jsonPrimitive.content
            val preview = Json.parseToJsonElement(read.execute(buildJsonObject { put("id", id) }).text).jsonObject
            assertEquals("Привет ✓", preview.getValue("text").jsonPrimitive.content)
            assertEquals("false", preview.getValue("truncated").jsonPrimitive.content)
            assertEquals(ToolOutcome.FAILED, create.execute(obj("../x", "ok")).outcome)
            assertEquals(ToolOutcome.FAILED, create.execute(obj("x", "bad\u0000text")).outcome)
            assertEquals(ToolOutcome.FAILED, create.execute(obj("x", "\uD800")).outcome)
            assertEquals(ToolOutcome.FAILED, create.execute(obj("x", "я".repeat(8193))).outcome)
            repeat(15) { assertEquals(ToolOutcome.SUCCESS, create.execute(obj("\"".repeat(128), "v")).outcome) }
            var offset: Int? = 0; val ids = mutableListOf<String>()
            while (offset != null) {
                val page = Json.parseToJsonElement(list.execute(buildJsonObject { put("offset", offset!!) }).text).jsonObject
                ids += page.getValue("files").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
                offset = page.getValue("next_offset").jsonPrimitive.intOrNull
            }
            assertEquals(16, ids.size); assertEquals(16, ids.toSet().size); assertTrue(id in ids)
        }
    }
}
