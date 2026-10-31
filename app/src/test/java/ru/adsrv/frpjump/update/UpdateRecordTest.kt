package ru.adsrv.frpjump.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.ssh.MemoryStorage

class UpdateRecordTest {
    private val hour = 60 * 60 * 1000L
    private val checked = 1_800_000_000_000L

    @Test
    fun `checks at most once a day`() {
        assertTrue("never checked", checkDue(checked, null))
        assertFalse(checkDue(checked, checked))
        assertFalse(checkDue(checked + 23 * hour, checked))
        assertTrue(checkDue(checked + 24 * hour, checked))
        assertTrue(checkDue(checked + 72 * hour, checked))
    }

    @Test
    fun `a clock set back does not stop the checks`() {
        assertTrue(checkDue(checked - hour, checked))
    }

    @Test
    fun `the record survives a restart, an unreadable one is as none`() {
        val storage = MemoryStorage()
        val record = UpdateRecord(checkedAt = checked, etag = "W/\"abc\"", release = """{"tag_name":"v0.2.1"}""", announced = "0.2.1")
        UpdateRecordStore(storage).write(record)
        assertEquals(record, UpdateRecordStore(storage).read())
        assertEquals(UpdateRecord(), UpdateRecordStore(MemoryStorage("{broken")).read())
        assertEquals(UpdateRecord(), UpdateRecordStore(MemoryStorage()).read())
    }
}
