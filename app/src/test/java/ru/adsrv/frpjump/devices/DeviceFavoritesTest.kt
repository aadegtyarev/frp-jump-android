package ru.adsrv.frpjump.devices

import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.ssh.MemoryStorage
import ru.adsrv.frpjump.store.TextStorage

class DeviceFavoritesTest {
    @Test fun persistenceIsPerProfileAndDoesNotPruneMissingDevices() {
        val work = MemoryStorage()
        val home = MemoryStorage()
        val favorites = DeviceFavorites(work)
        assertTrue(favorites.favorites.value.isEmpty())
        favorites.toggle("same")
        assertEquals(setOf("same"), DeviceFavorites(work).favorites.value)
        assertTrue(DeviceFavorites(home).favorites.value.isEmpty())
        visibleDevices(emptyList(), "", false, favorites.favorites.value)
        assertEquals(setOf("same"), DeviceFavorites(work).favorites.value)
        favorites.close()
        assertThrows(IllegalStateException::class.java) { favorites.toggle("late") }
    }

    @Test fun writeFailureDoesNotPublishPreference() {
        val storage = object : TextStorage {
            override fun read(): String? = null
            override fun write(text: String) { error("write failed") }
            override fun delete() = Unit
        }
        val favorites = DeviceFavorites(storage)
        assertThrows(IllegalStateException::class.java) { favorites.toggle("device") }
        assertTrue(favorites.favorites.value.isEmpty())
    }

    @Test fun filtersIntersectAndPreserveOrderingWithinGroups() {
        val rows = listOf(DeviceRow("first", true, null, false, label = "Кухня"), DeviceRow("second", false, null, false, label = "Кухня"), DeviceRow("third", true, null, false, label = "Кухня"))
        assertEquals(listOf("third", "first"), visibleDevices(rows, "кухня", true, setOf("third", "second")).map { it.name })
        assertEquals(listOf("second", "third", "first"), visibleDevices(rows, "кухня", false, setOf("third", "second")).map { it.name })
    }
}
