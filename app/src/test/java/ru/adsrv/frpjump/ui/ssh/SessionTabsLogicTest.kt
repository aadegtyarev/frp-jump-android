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
    private val root = TabTarget("root", "dev-a", 22)

    @Test
    fun `one session per target has no number`() {
        assertEquals(
            listOf("root · dev-a", "admin · dev-a", "root · dev-b"),
            tabLabels(listOf(root, TabTarget("admin", "dev-a", 22), TabTarget("root", "dev-b", 22))),
        )
    }

    @Test
    fun `later sessions to the same user, device and port are numbered`() {
        val other = TabTarget("admin", "dev-a", 22)
        assertEquals(
            listOf("root · dev-a", "admin · dev-a", "root · dev-a · 2", "root · dev-a · 3"),
            tabLabels(listOf(root, other, root, root)),
        )
    }

    @Test
    fun `another port shows it and counts on its own`() {
        val p2222 = TabTarget("root", "dev-a", 2222)
        assertEquals(
            listOf("root · dev-a", "root · dev-a:2222", "root · dev-a:2222 · 2"),
            tabLabels(listOf(root, p2222, p2222)),
        )
    }

    @Test
    fun `key and a password in memory are reused without asking`() {
        var asked = false
        assertTrue(reusesLogin(SshAuth.Key) { asked = true; false })
        assertTrue(reusesLogin(SshAuth.Password("typed", remember = false)) { asked = true; false })
        assertFalse(asked)
    }

    @Test
    fun `a remembered password is reused only while it is kept`() {
        assertTrue(reusesLogin(SshAuth.Password(null, remember = true)) { true })
        assertFalse(reusesLogin(SshAuth.Password(null, remember = true)) { false })
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
