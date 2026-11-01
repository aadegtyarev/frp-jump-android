package ru.adsrv.frpjump.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionNumberTest {
    private val root = SshTarget("root", "dev-a", 22)
    private val admin = SshTarget("admin", "dev-a", 22)
    private val other = SshTarget("root", "dev-a", 2222)

    @Test
    fun `the first session to a login is 1`() {
        assertEquals(1, nextSessionNumber(listOf(admin to 1, other to 1), root))
    }

    @Test
    fun `later sessions count on from the highest open one`() {
        assertEquals(3, nextSessionNumber(listOf(root to 1, admin to 1, root to 2), root))
    }

    @Test
    fun `closing one renumbers nothing and its number is not reused while a later one lives`() {
        // 1, 2, 3 opened; 2 closed: the others keep 1 and 3, the next is 4.
        assertEquals(4, nextSessionNumber(listOf(root to 1, root to 3), root))
    }

    @Test
    fun `with all of a login closed it starts at 1 again`() {
        assertEquals(1, nextSessionNumber(listOf(admin to 2), root))
    }

    @Test
    fun `Change login to the same user keeps the tab's number`() {
        assertEquals(2, replacementNumber(listOf(root to 1), root, 2, root))
    }

    @Test
    fun `Change login to another user counts among that user's sessions`() {
        assertEquals(1, replacementNumber(listOf(root to 1), root, 2, admin))
        assertEquals(3, replacementNumber(listOf(admin to 1, admin to 2), root, 1, admin))
    }
}
