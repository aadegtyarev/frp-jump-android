package ru.adsrv.frpjump.ui.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import ru.adsrv.frpjump.ssh.NameSetter
import ru.adsrv.frpjump.ssh.TabName

class AssistantSessionTitleTest {
    @Test fun `custom title remains compact and repeated names have distinct ordinals`() {
        val name = TabName("Deploy", NameSetter.ASSISTANT)
        val first = sessionDisplayTitle(name, 1, "Terminal")
        val second = sessionDisplayTitle(name, 2, "Terminal")
        assertEquals("\u2068Deploy\u2069", first)
        assertNotEquals(first, second)
        assertEquals("\u2068Deploy\u2069 · 2", second)
        assertEquals("Terminal · 2", sessionDisplayTitle(null, 2, "Terminal"))
    }
}
