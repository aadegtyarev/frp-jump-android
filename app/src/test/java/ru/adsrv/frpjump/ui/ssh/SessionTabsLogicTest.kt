package ru.adsrv.frpjump.ui.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.ssh.SshAuth
import ru.adsrv.frpjump.ssh.SshAuthMethod
import ru.adsrv.frpjump.ssh.SshLogin

class SessionTabsLogicTest {
    @Test
    fun `the first session to a login has no number, later ones do`() {
        assertEquals("root \u00B7 dev-a", tabLabel("root", "dev-a", 22, 1))
        assertEquals("root \u00B7 dev-a \u00B7 3", tabLabel("root", "dev-a", 22, 3))
    }

    @Test
    fun `another port shows it`() {
        assertEquals("root \u00B7 dev-a:2222 \u00B7 2", tabLabel("root", "dev-a", 2222, 2))
    }

    @Test
    fun `key and a password in memory are reused without asking`() {
        var asked = false
        assertTrue(reusesLogin(SshAuth.Key, refused = false) { asked = true; false })
        assertTrue(reusesLogin(SshAuth.Password("typed", remember = false), refused = false) { asked = true; false })
        assertFalse(asked)
    }

    @Test
    fun `a refused typed password is not reused`() {
        assertFalse(reusesLogin(SshAuth.Password("typed", remember = false), refused = true) { true })
    }

    @Test
    fun `a refused key is not reused`() {
        assertFalse(reusesLogin(SshAuth.Key, refused = true) { true })
    }

    @Test
    fun `a remembered password is reused only while it is kept`() {
        assertTrue(reusesLogin(SshAuth.Password(null, remember = true), refused = false) { true })
        assertFalse(reusesLogin(SshAuth.Password(null, remember = true), refused = false) { false })
    }

    @Test
    fun `the dialog starts with the session's user and method`() {
        assertEquals(SshLogin("admin", SshAuthMethod.PASSWORD), loginLike("admin", SshAuth.Password("x", remember = false)))
        assertEquals(SshLogin("root", SshAuthMethod.KEY), loginLike("root", SshAuth.Key))
    }

    @Test
    fun `closing another tab keeps the selection`() {
        assertEquals(2, selectionAfterClose(listOf(1, 2, 3), selectedId = 2, closedId = 3))
    }

    @Test
    fun `closing the selected tab selects its left neighbour`() {
        assertEquals(2, selectionAfterClose(listOf(1, 2, 3), selectedId = 3, closedId = 3))
    }

    @Test
    fun `closing the selected first tab selects the next one`() {
        assertEquals(2, selectionAfterClose(listOf(1, 2, 3), selectedId = 1, closedId = 1))
    }

    @Test
    fun `closing the last tab leaves none`() {
        assertNull(selectionAfterClose(listOf(1), selectedId = 1, closedId = 1))
    }
}
