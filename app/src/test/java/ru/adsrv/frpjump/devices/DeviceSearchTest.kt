package ru.adsrv.frpjump.devices

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class DeviceSearchTest {
    private fun device(name: String, stale: Boolean = false, label: String? = null) =
        DeviceRow(name, online = true, lastSeen = null, disabled = false, stale = stale, label = label)

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
    fun `a part of the label matches too, whatever its case`() {
        val garage = device("wb01", label = "Щиток, гараж")
        val labelled = list + garage
        assertEquals(listOf(garage), filterDevices(labelled, "ГАРАЖ"))
        assertEquals(listOf(garage), filterDevices(labelled, " щиток, "))
    }

    @Test
    fun `decomposed and composed letters match each other`() {
        // «й» typed as и + combining breve, and the other way round.
        val composed = device("wb01", label = "Двор, сарай")
        assertEquals(listOf(composed), filterDevices(listOf(composed), "сарай"))
        val decomposed = device("wb02", label = "Двор, сарай")
        assertEquals(listOf(decomposed), filterDevices(listOf(decomposed), "САРАЙ"))
    }

    @Test
    fun `case folds the same whatever the phone's language`() {
        val before = Locale.getDefault()
        try {
            // Turkish lower-cases "I" to a dotless "ı"; the search must not.
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val box = device("wb03", label = "IOT box")
            assertEquals(listOf(box), filterDevices(listOf(box), "iot"))
        } finally {
            Locale.setDefault(before)
        }
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
