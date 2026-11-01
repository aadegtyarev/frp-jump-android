package ru.adsrv.frpjump.files

import org.junit.Assert.assertEquals
import org.junit.Test

class PathsTest {
    @Test
    fun `the path bar has a part for root and each folder down to the one shown`() {
        assertEquals(
            listOf(PathSegment("/", "/"), PathSegment("mnt", "/mnt"), PathSegment("data", "/mnt/data"), PathSegment("etc", "/mnt/data/etc")),
            pathSegments("/mnt/data/etc"),
        )
        assertEquals(listOf(PathSegment("/", "/")), pathSegments("/"))
        assertEquals(listOf(PathSegment("/", "/"), PathSegment("root", "/root")), pathSegments("/root/"))
    }

    @Test
    fun `up from a folder is its parent, and root stays root`() {
        assertEquals("/mnt", parentOf("/mnt/data"))
        assertEquals("/", parentOf("/mnt"))
        assertEquals("/", parentOf("/"))
        assertEquals("/mnt/data/etc", childOf("/mnt/data", "etc"))
        assertEquals("/etc", childOf("/", "etc"))
    }

    @Test
    fun `typed paths are absolute, relative to the folder shown, or to home with a tilde`() {
        assertEquals("/var/log", resolvePath("/root", "/var//log/"))
        assertEquals("/root/www", resolvePath("/root", "www"))
        assertEquals("/", resolvePath("/root", "../.."))
        assertEquals("/home/pi/sub", resolvePath("/etc", "~/sub", home = "/home/pi"))
        assertEquals("/home/pi", resolvePath("/etc", "~", home = "/home/pi"))
        assertEquals("/etc/~", resolvePath("/etc", "~", home = null))
        assertEquals("/a/c", normalizePath("/a/./b/../c"))
    }
}
