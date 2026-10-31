package ru.adsrv.frpjump.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StaleFrpcTest {
    private val binary = "/data/app/x/lib/arm64/libfrpc.so"
    private val config = "/data/user/0/ru.adsrv.frpjump/no_backup/frpc/p1/frpc.toml"
    private val frpc = "$binary\u0000-c\u0000$config\u0000"
    private val procs = mutableMapOf<Int, String>()

    private fun stale(pidText: String?) = staleFrpcPid(pidText, binary, config) { procs[it] }

    @Test
    fun `a frpc left on this profile's config is reaped`() {
        procs[4242] = frpc
        assertEquals(4242, stale("4242\n"))
    }

    @Test
    fun `a pid now used by another process is left alone`() {
        procs[4242] = "/system/bin/sh\u0000-c\u0000$config\u0000"
        assertNull(stale("4242"))
        procs[4242] = "$binary\u0000-c\u0000/data/user/0/ru.adsrv.frpjump/no_backup/frpc/p2/frpc.toml\u0000"
        assertNull("another profile's frpc", stale("4242"))
    }

    @Test
    fun `no pidfile, a gone process or garbage reap nothing`() {
        assertNull(stale(null))
        assertNull("process gone", stale("4242"))
        assertNull(stale("not a pid"))
        assertNull(stale("-1"))
    }

    @Test
    fun `the frpc just started is found by its command line`() {
        procs[10] = "/system/bin/init\u0000"
        procs[20] = frpc
        assertEquals(20, frpcPidAmong(listOf(1, 10, 20), binary, config) { procs[it] })
        assertNull(frpcPidAmong(listOf(1, 10), binary, config) { procs[it] })
    }
}
