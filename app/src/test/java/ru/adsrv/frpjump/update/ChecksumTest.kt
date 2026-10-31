package ru.adsrv.frpjump.update

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class ChecksumTest {
    private val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun `the copy hashes what went through`() {
        val out = ByteArrayOutputStream()
        assertEquals(abc, copyHashing(ByteArrayInputStream("abc".toByteArray()), out, maxBytes = 10))
        assertArrayEquals("abc".toByteArray(), out.toByteArray())
    }

    @Test
    fun `a large file in chunks, with progress`() {
        val data = ByteArray(200_000) { (it % 251).toByte() }
        val progress = mutableListOf<Long>()
        val hex = copyHashing(ByteArrayInputStream(data), ByteArrayOutputStream(), maxBytes = data.size.toLong()) { progress += it }
        assertEquals(java.security.MessageDigest.getInstance("SHA-256").digest(data).toHex(), hex)
        assertEquals(data.size.toLong(), progress.last())
        assertTrue(progress.size > 1)
    }

    @Test(expected = IOException::class)
    fun `more than allowed is refused`() {
        copyHashing(ByteArrayInputStream(ByteArray(11)), ByteArrayOutputStream(), maxBytes = 10)
    }

    @Test
    fun `the published checksum must match`() {
        assertTrue(checksumMatches(abc, abc))
        assertTrue(checksumMatches(abc.uppercase(), abc))
        assertFalse(checksumMatches(abc, abc.replaceFirst('b', 'c')))
        assertFalse(checksumMatches("", ""))
    }

    @Test
    fun `only GitHub's hosts`() {
        listOf("github.com", "api.github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com", "GitHub.com").forEach {
            assertTrue(it, githubHost(it))
        }
        listOf("example.com", "github.com.evil.net", "evilgithub.com", "githubusercontent.com.evil.net", "gist.github.io").forEach {
            assertFalse(it, githubHost(it))
        }
    }
}
