package ru.adsrv.frpjump.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.R

/** What a refused login says; a remembered password is never what gets blamed for a missing or unreadable one. */
class LoginRefusalTest {
    @Test
    fun `a key refusal is the key's`() {
        assertEquals(LoginRefusal.KEY, loginRefusal(key = true, typed = false, remembered = true, readable = true))
    }

    @Test
    fun `a typed password is a wrong user or password`() {
        assertEquals(LoginRefusal.PASSWORD_TYPED, loginRefusal(key = false, typed = true, remembered = true, readable = false))
    }

    @Test
    fun `the remembered one is refused, unreadable, or gone`() {
        assertEquals(LoginRefusal.PASSWORD_SAVED, loginRefusal(key = false, typed = false, remembered = true, readable = true))
        assertEquals(LoginRefusal.PASSWORD_UNREADABLE, loginRefusal(key = false, typed = false, remembered = true, readable = false))
        assertEquals(LoginRefusal.PASSWORD_MISSING, loginRefusal(key = false, typed = false, remembered = false, readable = true))
    }

    @Test
    fun `each has its own words`() {
        assertEquals(R.string.ssh_key_refused, loginRefusalText(LoginRefusal.KEY))
        assertEquals(R.string.ssh_password_refused, loginRefusalText(LoginRefusal.PASSWORD_TYPED))
        assertEquals(R.string.ssh_saved_password_refused, loginRefusalText(LoginRefusal.PASSWORD_SAVED))
        assertEquals(R.string.ssh_saved_password_unreadable, loginRefusalText(LoginRefusal.PASSWORD_UNREADABLE))
        assertEquals(R.string.ssh_saved_password_missing, loginRefusalText(LoginRefusal.PASSWORD_MISSING))
    }

    @Test
    fun `Change login does not offer the saved password just refused for that user`() {
        assertFalse(offersSavedPassword(LoginRefusal.PASSWORD_SAVED, "root", "root") { true })
        assertFalse(offersSavedPassword(LoginRefusal.PASSWORD_UNREADABLE, "root", " root ") { true })
    }

    @Test
    fun `another user, or another refusal, keeps its saved password`() {
        assertTrue(offersSavedPassword(LoginRefusal.PASSWORD_SAVED, "root", "admin") { true })
        assertTrue(offersSavedPassword(LoginRefusal.PASSWORD_TYPED, "root", "root") { true })
        assertTrue(offersSavedPassword(LoginRefusal.KEY, "root", "root") { true })
        assertTrue(offersSavedPassword(null, null, "root") { true })
        assertFalse(offersSavedPassword(null, null, "root") { false })
    }
}
