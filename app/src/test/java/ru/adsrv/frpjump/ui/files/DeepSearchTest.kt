package ru.adsrv.frpjump.ui.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.adsrv.frpjump.files.Found

class DeepSearchTest {
    private val a = Found("/r/x/a", "x/a")
    private val b = Found("/r/x/b", "x/b")

    @Test
    fun aLateBatchOfAnOldRunIsDropped() {
        // Stopped, then "Search again" with the same text: the old run's reader may still deliver.
        val again = DeepSearch(id = 2, query = "a", root = "/r", hidden = false)
        assertNull(again.withHits(1, listOf(a)))
        assertNull(again.copy(running = false).withHits(2, listOf(a)))
    }

    @Test
    fun hitsAreAddedOnce() {
        val search = DeepSearch(id = 1, query = "a", root = "/r", hidden = false, found = listOf(a))
        assertEquals(listOf(a, b), search.withHits(1, listOf(a, b, b))!!.found)
    }
}
