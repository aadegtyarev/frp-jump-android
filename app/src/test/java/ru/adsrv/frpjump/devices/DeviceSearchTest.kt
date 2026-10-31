package ru.adsrv.frpjump.devices

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceSearchTest {
    private fun device(name: String, stale: Boolean = false) = DeviceRow(name, online = true, lastSeen = null, disabled = false, stale = stale)

    private val list = listOf(device("wb-kitchen"), device("WB-Boiler"), device("router"), device("Котельная"))

    @Test
    fun `a blank query shows everything`() {
        assertEquals(list, filterDevices(list, ""))
        assertEquals(list, filterDevices(list, "   "))
    }

    @Test
    fun `a part of the name matches, whatever its case, in the list's order`() {
        assertEquals(listOf(device("wb-kitchen"), device("WB-Boiler")), filterDevices(list, "wb"))
        assertEquals(listOf(device("WB-Boiler")), filterDevices(list, "boil"))
        assertEquals(listOf(device("Котельная")), filterDevices(list, "КОТЕЛ"))
        assertEquals(listOf(device("router")), filterDevices(list, " out "))
    }

    @Test
    fun `nothing matching gives an empty list`() {
        assertEquals(emptyList<DeviceRow>(), filterDevices(list, "printer"))
        assertEquals(emptyList<DeviceRow>(), filterDevices(emptyList(), "wb"))
    }

    @Test
    fun `an offline cached list filters the same`() {
        val cached = list.map { it.copy(stale = true) }
        assertEquals(listOf(device("router", stale = true)), filterDevices(cached, "ROUT"))
    }
}
