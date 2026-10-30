package ru.adsrv.frpjump.enroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollLineParserTest {
    private val token = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcd"

    private fun ok(text: String): EnrollLine {
        val result = EnrollLineParser.parse(text)
        assertTrue("expected Ok, got $result", result is EnrollLineResult.Ok)
        return (result as EnrollLineResult.Ok).line
    }

    private fun error(text: String): String {
        val result = EnrollLineParser.parse(text)
        assertTrue("expected Error, got $result", result is EnrollLineResult.Error)
        return (result as EnrollLineResult.Error).message
    }

    @Test
    fun fullCliLine() {
        val line = ok("frp-jump-client enroll https://way.example.com $token --name phone")
        assertEquals(EnrollLine("https://way.example.com", token, "phone"), line)
    }

    @Test
    fun lineWrappedBetweenWords() {
        val line = ok("  frp-jump-client enroll\nhttps://way.example.com\r\n$token\n  --name\nphone\n")
        assertEquals(EnrollLine("https://way.example.com", token, "phone"), line)
    }

    @Test
    fun lineWrappedInsideTheToken() {
        val line = ok("frp-jump-client enroll https://way.example.com ${token.take(20)}\n${token.drop(20)}")
        assertEquals(token, line.token)
    }

    @Test
    fun lineWrappedInsideTheUrl() {
        val line = ok("frp-jump-client enroll https://way.exam\nple.com/prefix $token")
        assertEquals("https://way.example.com/prefix", line.controlUrl)
        assertEquals(token, line.token)
    }

    @Test
    fun sudoPrefix() {
        val line = ok("sudo frp-jump-client enroll https://way.example.com $token")
        assertEquals(EnrollLine("https://way.example.com", token, null), line)
    }

    @Test
    fun sudoWithFullPath() {
        val line = ok("sudo /usr/local/bin/frp-jump-client enroll https://way.example.com $token")
        assertEquals(token, line.token)
    }

    @Test
    fun nameWithEquals() {
        assertEquals("phone-2", ok("frp-jump-client enroll https://way.example.com $token --name=phone-2").requestedName)
    }

    @Test
    fun nameBeforeUrl() {
        assertEquals("phone", ok("frp-jump-client enroll --name phone https://way.example.com $token").requestedName)
    }

    @Test
    fun namePlaceholderMeansNoName() {
        assertNull(ok("frp-jump-client enroll https://way.example.com $token --name <pick-a-name>").requestedName)
    }

    @Test
    fun bareUrlAndToken() {
        assertEquals(EnrollLine("https://way.example.com:8443", token, null), ok("https://way.example.com:8443/ $token"))
    }

    @Test
    fun httpRejected() {
        assertTrue(error("frp-jump-client enroll http://way.example.com $token").contains("https://"))
    }

    @Test
    fun garbageRejected() {
        error("")
        error("   \n ")
        error("hello world")
        error("frp-jump-client enroll")
        error("frp-jump-client enroll https://way.example.com")
        error("https://way.example.com $token --name")
        error("https://way.example.com $token --verbose")
        error("https://way.example.com tok\$en!")
        error("ftp://way.example.com $token")
    }

    @Test
    fun unsafeUrlsRejected() {
        error("https://user:pass@way.example.com $token")
        error("https://way.example.com/?x=1 $token")
        error("https://way.example.com/#frag $token")
        error("https://way.example.com:0 $token")
        error("https:///path $token")
    }
}
