package ru.adsrv.frpjump.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkIdentityTest {
    private val home = networkIdentity(
        NetworkKind.WIFI,
        gateways = listOf("192.168.1.1", "fe80::1e2b:3cff:fe4d:5e6f%wlan0"),
        dns = listOf("192.168.1.1"),
        domains = "lan",
        subnets = listOf("192.168.1.0/24"),
    )

    @Test
    fun `mobile data is one network whatever its addresses`() {
        assertEquals("mobile", networkIdentity(NetworkKind.MOBILE, listOf("10.1.2.3"), listOf("8.8.8.8"), null, listOf("10.1.2.0/24")))
        assertEquals("mobile", networkIdentity(NetworkKind.MOBILE, listOf("10.9.9.9"), emptyList(), null, emptyList()))
    }

    @Test
    fun `the same Wi-Fi gives the same id, in any order`() {
        assertTrue(home.startsWith("wifi:"))
        assertEquals(
            home,
            networkIdentity(NetworkKind.WIFI, listOf("fe80::1e2b:3cff:fe4d:5e6f%wlan0", "192.168.1.1"), listOf("192.168.1.1"), "LAN ", listOf("192.168.1.0/24")),
        )
    }

    @Test
    fun `another router, DNS, subnet or kind is another network`() {
        assertNotEquals(home, networkIdentity(NetworkKind.WIFI, listOf("192.168.1.1", "fe80::aaaa%wlan0"), listOf("192.168.1.1"), "lan", listOf("192.168.1.0/24")))
        assertNotEquals(home, networkIdentity(NetworkKind.WIFI, listOf("192.168.1.1", "fe80::1e2b:3cff:fe4d:5e6f%wlan0"), listOf("1.1.1.1"), "lan", listOf("192.168.1.0/24")))
        assertNotEquals(home, networkIdentity(NetworkKind.WIFI, listOf("192.168.1.1", "fe80::1e2b:3cff:fe4d:5e6f%wlan0"), listOf("192.168.1.1"), "lan", listOf("192.168.0.0/23")))
        assertNotEquals(home, networkIdentity(NetworkKind.ETHERNET, listOf("192.168.1.1", "fe80::1e2b:3cff:fe4d:5e6f%wlan0"), listOf("192.168.1.1"), "lan", listOf("192.168.1.0/24")))
    }
}
