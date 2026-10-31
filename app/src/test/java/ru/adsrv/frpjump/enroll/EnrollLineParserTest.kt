package ru.adsrv.frpjump.enroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollLineParserTest {
    /** `secrets.token_urlsafe(32)` is 43 characters; this one even starts with `--`. */
    private val token = "--CdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde"

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
    fun wrappedAfterTheLastDotOfTheHost() {
        assertEquals(EnrollLine("https://way.example.com", token, "phone"), ok("frp-jump-client enroll https://way.example.\ncom $token --name phone"))
    }

    @Test
    fun wrappedInsideAHostLabel() {
        assertEquals("https://way.example.com", ok("frp-jump-client enroll https://wa\ny.example.com $token").controlUrl)
    }

    @Test
    fun wrappedInsideTheTokenRightAfterTheUrl() {
        // rich glues the URL and the token's first piece when the space falls at the wrap.
        val line = ok("frp-jump-client enroll https://way.example.com ${token.take(5)}\n${token.drop(5)}")
        assertEquals(EnrollLine("https://way.example.com", token, null), line)
    }

    @Test
    fun wrappedInsideTheName() {
        assertEquals("kitchen-phone", ok("frp-jump-client enroll https://way.example.com $token --name kitchen-\nphone").requestedName)
        assertEquals("kitchen-phone", ok("frp-jump-client enroll https://way.example.com $token --name=kitch\nen-phone").requestedName)
    }

    @Test
    fun wrappedInsideTheCommand() {
        val line = ok("sudo frp-jump-cli\nent enr\noll https://way.example.com $token")
        assertEquals(EnrollLine("https://way.example.com", token, null), line)
    }

    @Test
    fun tokenStartingWithDashesIsNotAnOption() {
        assertEquals(token, ok("https://way.example.com $token").token)
    }

    @Test
    fun errorsNeverQuoteTheToken() {
        val bad = token.dropLast(1) + "!"
        assertTrue(!error("https://way.example.com $bad").contains(bad.take(10)))
        assertTrue(!error("http://way.example.com $token").contains(token.take(10)))
    }

    @Test
    fun namePlaceholderMeansNoName() {
        assertNull(ok("frp-jump-client enroll https://way.example.com $token --name <pick-a-name>").requestedName)
    }

    @Test
    fun namePlaceholderInAnyShapeMeansNoName() {
        // As rich may wrap it, and spelled with `=`.
        for (tail in listOf("--name <pick-a-\nname>", "--name\n<pick-a-name>", "--name=<pick-a-name>", "--name <pick-a-name>\n")) {
            val line = ok("frp-jump-client enroll https://way.example.com $token $tail")
            assertEquals(EnrollLine("https://way.example.com", token, null), line)
        }
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
        error("https://way.example.com ${token.drop(1)}")
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
