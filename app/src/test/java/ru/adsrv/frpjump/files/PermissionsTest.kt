package ru.adsrv.frpjump.files

import org.junit.Assert.assertEquals
import org.junit.Test

/** The file card's permissions, as `ls -l` and `chmod` write them. */
class PermissionsTest {
    @Test
    fun `plain modes`() {
        assertEquals("rwxr-xr-x", permissionString(0b111_101_101))
        assertEquals("rw-r--r--", permissionString(0b110_100_100))
        assertEquals("---------", permissionString(0))
        assertEquals("755", octalMode(0b111_101_101))
        assertEquals("600", octalMode(0b110_000_000))
        assertEquals("000", octalMode(0))
    }

    @Test
    fun `setuid, setgid and sticky`() {
        assertEquals("rwsr-xr-x", permissionString(0x800 or 0b111_101_101))
        assertEquals("rwSr--r--", permissionString(0x800 or 0b110_100_100))
        assertEquals("rwxr-sr-x", permissionString(0x400 or 0b111_101_101))
        assertEquals("rwxrwxrwt", permissionString(0x200 or 0b111_111_111))
        assertEquals("rwxrwxrwT", permissionString(0x200 or 0b111_111_110))
        assertEquals("4755", octalMode(0x800 or 0b111_101_101))
        assertEquals("1777", octalMode(0x200 or 0b111_111_111))
    }
}
