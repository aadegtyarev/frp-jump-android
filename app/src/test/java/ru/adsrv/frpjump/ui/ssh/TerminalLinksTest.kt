package ru.adsrv.frpjump.ui.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decision Р37: which text in the terminal is a link, where it ends, what may be done with it, and where a tap goes. */
class TerminalLinksTest {
    private fun links(text: String) = findLinks(text).map { text.substring(it.start, it.end) }
    private fun one(text: String): String = links(text).single()

    // ---- where a link ends -------------------------------------------------------------------------------------------------------

    @Test fun `a plain link in a line`() {
        assertEquals("https://example.com/a/b", one("Hit:1 https://example.com/a/b stable InRelease"))
        assertEquals("http://example.com", one("http://example.com"))
    }

    @Test fun `trailing punctuation and quotes are cut`() {
        for (tail in listOf(".", ",", ";", ":", "!", "?", "'", "\"", "...", "?!", ".'", ",\"", "\u2026", "\u3002", "\uFF0C", "\u201D", "\u2019")) {
            assertEquals("tail [$tail]", "https://example.com/a", one("see https://example.com/a$tail next"))
            assertEquals("tail [$tail] at the end", "https://example.com/a", one("https://example.com/a$tail"))
        }
    }

    @Test fun `punctuation inside a link stays`() {
        assertEquals("https://a.com/x.y,z;w:v!u?q=1&r='s'#f", one("https://a.com/x.y,z;w:v!u?q=1&r='s'#f."))
        assertEquals("https://a.com:8080/x", one("https://a.com:8080/x:"))
    }

    @Test fun `a closing bracket with no opening one is cut`() {
        assertEquals("https://a.com/x", one("(see https://a.com/x)"))
        assertEquals("https://a.com/x", one("[https://a.com/x]"))
        assertEquals("https://a.com/x", one("{https://a.com/x}"))
        assertEquals("https://a.com/x", one("(https://a.com/x)."))
        assertEquals("https://a.com/x", one("(https://a.com/x),"))
        assertEquals("https://a.com/x", one("(\"https://a.com/x\")"))
        assertEquals("https://a.com/x", one("https://a.com/x)))"))
        assertEquals("https://a.com/x", one("https://a.com/x]."))
        assertEquals("https://a.com/x", one("\uFF08https://a.com/x\uFF09"))
    }

    @Test fun `paired brackets inside a link stay`() {
        assertEquals("https://en.wikipedia.org/wiki/Foo_(bar)", one("https://en.wikipedia.org/wiki/Foo_(bar)"))
        assertEquals("https://en.wikipedia.org/wiki/Foo_(bar)", one("(https://en.wikipedia.org/wiki/Foo_(bar))"))
        assertEquals("https://en.wikipedia.org/wiki/Foo_(bar)", one("https://en.wikipedia.org/wiki/Foo_(bar)."))
        assertEquals("https://a.com/[x]", one("https://a.com/[x]"))
        assertEquals("https://a.com/{id}", one("https://a.com/{id},"))
        assertEquals("https://a.com/(a)(b)", one("https://a.com/(a)(b)"))
        assertEquals("https://a.com/a(b", one("https://a.com/a(b"))
        // One pair and one extra: the extra one goes.
        assertEquals("https://a.com/f(x)", one("https://a.com/f(x))"))
    }

    @Test fun `a link in quotes or angle brackets ends at them`() {
        assertEquals("https://a.com/x", one("\"https://a.com/x\""))
        assertEquals("https://a.com/x", one("<https://a.com/x>"))
        assertEquals("https://a.com/x", one("href=\"https://a.com/x\" class=a"))
        assertEquals("https://a.com/x", one("'https://a.com/x'."))
        assertEquals("https://a.com/x", one("`https://a.com/x`"))
        assertEquals(listOf("https://a.com", "https://b.com"), links("\"https://a.com\",\"https://b.com\""))
        assertEquals("https://a.com/x", one("{\"url\":\"https://a.com/x\",\"n\":1}"))
    }

    @Test fun `a link ends at a space of any kind`() {
        assertEquals("https://a.com/x", one("https://a.com/x\u00A0more"))
        assertEquals("https://a.com/x", one("https://a.com/x\u3000more"))
        assertEquals("https://a.com/x", one("https://a.com/x\tmore"))
        assertEquals("https://a.com/x", one("https://a.com/x\u001B[0m"))
    }

    @Test fun `several links in one line`() {
        assertEquals(listOf("https://a.com/x", "http://b.org/y"), links("go https://a.com/x, then http://b.org/y."))
    }

    @Test fun `a scheme needs the double slash and a Latin name`() {
        assertEquals(emptyList<String>(), links("javascript:alert(1)"))
        assertEquals(emptyList<String>(), links("intent:#Intent;scheme=http;end"))
        assertEquals(emptyList<String>(), links("mailto:a@b.c tel:+123 content:foo data:text/html,x"))
        assertEquals(emptyList<String>(), links("https:/a.com http//a.com https:a.com"))
        assertEquals(emptyList<String>(), links("https://"))
        assertEquals(emptyList<String>(), links("see https:// and more"))
        assertEquals(emptyList<String>(), links("https://."))
        assertEquals("ssh://git@host/repo.git", one("clone ssh://git@host/repo.git"))
        assertEquals("git+https://a.com/r", one("git+https://a.com/r"))
    }

    @Test fun `letters before the scheme belong to it`() {
        // "xhttps" is a scheme of its own, not https: it never opens.
        assertEquals("xhttps://a.com", one("xhttps://a.com"))
        assertNull(linkOpenTarget("xhttps://a.com"))
        // Punctuation before it is not.
        assertEquals("https://a.com", one("url=https://a.com"))
        assertEquals("https://a.com", one("(https://a.com"))
    }

    @Test fun `a very long run is not a link and costs little`() {
        val ok = "https://a.com/" + "a".repeat(MAX_LINK_CHARS - 14)
        assertEquals(ok, one(ok))
        val tooLong = "https://a.com/" + "a".repeat(MAX_LINK_CHARS)
        assertEquals(emptyList<String>(), links(tooLong))
        val huge = "x https://a.com/" + "b".repeat(2_000_000) + " https://c.com/z"
        assertEquals(listOf("https://c.com/z"), links(huge))
        val many = "https://a.com/x ".repeat(20_000)
        assertEquals(20_000, links(many).size)
    }

    @Test fun `trimming never cuts into the scheme`() {
        assertEquals(8, trimLinkTail("https://", 0, 8, 8))
        assertEquals(9, trimLinkTail("https://a.", 0, 10, 8))
    }

    // ---- what may be done with a link --------------------------------------------------------------------------------------------

    private fun verdict(text: String) = describeLink(text).verdict

    @Test fun `http and https open`() {
        for (text in listOf("https://example.com/a", "http://example.com", "HTTPS://EXAMPLE.COM/A", "HtTp://example.com", "https://example.com:8443/x?y=1#z",
            "https://user:pw@example.com/", "http://192.168.1.20:5173/", "http://8.8.8.8/", "http://[2001:db8::1]:8080/x", "https://example.com./", "http://a_b.example.com/")) {
            assertEquals(text, LinkVerdict.OPEN, verdict(text))
            assertEquals(text, describeLink(text).address, linkOpenTarget(text))
            assertNull(text, describeLink(text).original)
        }
    }

    @Test fun `only http and https ever have an open target`() {
        for (scheme in listOf("ftp", "ftps", "sftp", "ssh", "file", "content", "intent", "javascript", "data", "about", "blob", "chrome", "market", "android-app",
            "tel", "sms", "mailto", "view-source", "ws", "wss", "git", "git+https", "xhttps", "https+x", "http-x", "myapp", "vscode", "x", "https.", "ht", "httpss")) {
            val text = "$scheme://example.com/x"
            assertEquals(text, LinkVerdict.OTHER_SCHEME, verdict(text))
            assertNull(text, linkOpenTarget(text))
            assertEquals(text, describeLink(text).address)
        }
        assertNull(linkOpenTarget("intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end"))
        assertNull(linkOpenTarget("javascript://%0Aalert(document.cookie)"))
        assertNull(linkOpenTarget("file:///etc/passwd"))
        assertNull(linkOpenTarget("content://com.android.contacts/contacts"))
        assertNull(linkOpenTarget("not a link at all"))
        assertNull(linkOpenTarget("https:example.com"))
        assertNull(linkOpenTarget(""))
        assertNull(linkOpenTarget("://example.com"))
    }

    @Test fun `the address of this machine is not opened`() {
        for (text in listOf(
            "http://localhost", "http://localhost:5173/", "http://LOCALHOST/", "http://localhost./", "http://app.localhost:3000/", "https://localhost/x",
            "http://127.0.0.1/", "http://127.0.0.1:8080/x", "http://127.1.2.3/", "http://127.255.255.255/", "http://127.1/", "http://127.0.1/",
            "http://0x7f.0.0.1/", "http://0X7F000001/", "http://2130706433/", "http://017700000001/", "http://0177.0.0.1/", "http://127.0.0.1./",
            "http://0.0.0.0/", "http://0.0.0.0:80/", "http://0/", "http://0x0/",
            "http://[::1]/", "http://[::1]:8080/x", "http://[0:0:0:0:0:0:0:1]/", "http://[0000::0001]/", "http://[::]/", "http://[::ffff:127.0.0.1]/",
            "http://[::ffff:7f00:1]/", "http://[::ffff:0.0.0.0]/",
            "http://%6Cocalhost/", "http://%6c%6f%63%61%6c%68%6f%73%74/", "http://\uFF4C\uFF4F\uFF43\uFF41\uFF4C\uFF48\uFF4F\uFF53\uFF54/",
            "http://user@127.0.0.1/", "http://127.0.0.1\\@example.com/", "http://127.0.0.1?x=1", "http://127.0.0.1#x",
        )) {
            assertEquals(text, LinkVerdict.REMOTE, verdict(text))
            assertNull(text, linkOpenTarget(text))
        }
    }

    @Test fun `near misses of the remote machine open`() {
        for (text in listOf("http://127.0.0.1.example.com/", "http://localhost.example.com/", "http://notlocalhost/", "http://128.0.0.1/", "http://126.255.255.255/",
            "http://1.0.0.0/", "http://[::2]/", "http://[2::1]/", "http://[::ffff:128.0.0.1]/", "http://example.com\\@127.0.0.1/", "http://localhost-x.com/")) {
            assertEquals(text, LinkVerdict.OPEN, verdict(text))
        }
    }

    @Test fun `an address that cannot be read does not open`() {
        for (text in listOf("http://", "http:///x", "http://:80/", "http://a:b/", "http://a:80:80/", "http://[::1/x", "http://[zzz]/", "http://[::1]x/", "http://[1:2:3:4:5:6:7:8:9]/",
            "http://256.1.1.1/", "http://1.2.3.4.5/", "http://1.2.3.4.5.6/", "http://08/", "http://a.1/", "http://%zz/", "http://%4/", "http://@/", "http://user@/",
            "http://[::1%25eth0]/", "http://4294967296/", "http://[1::2::3]/")) {
            assertEquals(text, LinkVerdict.INVALID, verdict(text))
            assertNull(text, linkOpenTarget(text))
        }
    }

    @Test fun `a domain with non-Latin letters opens as punycode and the original is kept`() {
        // Cyrillic "аррӏе" (a, p, p, palochka, e) passes for apple.
        val text = "https://\u0430\u0440\u0440\u04CF\u0435.com/login"
        val info = describeLink(text)
        assertEquals(LinkVerdict.OPEN, info.verdict)
        assertEquals("https://xn--80ak6aa92e.com/login", info.address)
        assertEquals(text, info.original)
        assertEquals("https://xn--80ak6aa92e.com/login", linkOpenTarget(text))

        val de = describeLink("http://user:pw@m\u00FCnchen.de:8080/stra\u00DFe?q=1#f")
        assertEquals("http://user:pw@xn--mnchen-3ya.de:8080/stra\u00DFe?q=1#f", de.address)
        assertEquals(LinkVerdict.OPEN, de.verdict)

        // A host in the path or query is not touched; only the host is.
        val path = describeLink("https://example.com/\u043F\u0443\u0442\u044C")
        assertEquals("https://example.com/\u043F\u0443\u0442\u044C", path.address)
        assertNull(path.original)

        // A Latin host keeps its text and its xn-- as given.
        assertNull(describeLink("https://xn--80ak6aa92e.com/").original)
        // An all-Cyrillic domain and a mixed one.
        assertTrue(describeLink("https://\u043F\u0440\u0438\u043C\u0435\u0440.\u0440\u0444/").address.startsWith("https://xn--e1afmkfd.xn--p1ai/"))
        assertTrue(describeLink("https://a\u0430.com/").address.startsWith("https://xn--"))
    }

    @Test fun `a non-Latin host that is this machine does not open`() {
        val info = describeLink("http://\uFF4C\uFF4F\uFF43\uFF41\uFF4C\uFF48\uFF4F\uFF53\uFF54:3000/")
        assertEquals(LinkVerdict.REMOTE, info.verdict)
        assertEquals("http://localhost:3000/", info.address)
    }

    @Test fun `a host that cannot be made into punycode does not open`() {
        // A label of more than 63 characters is no domain name.
        assertEquals(LinkVerdict.INVALID, verdict("http://" + "\u00E9".repeat(70) + ".com/"))
        assertNull(linkOpenTarget("http://" + "\u00E9".repeat(70) + ".com/"))
    }

    @Test fun `hidden and bidi characters have no open`() {
        for (text in listOf(
            "https://example.com/\u202Efdp.exe", "https://example.com/\u2066x", "https://exa\u200Bmple.com/", "https://exam\u00ADple.com/", "https://example.com/a\u200Fb",
            "https://example.com/\uFEFF", "https://example.com/\u2060", "https://example.com/\u180E", "https://example.com/\uFE0F", "https://example.com/\u034F",
            "https://example.com/\uDB40\uDC41", "https://example.com/a\u0301\u0302\u0303\u0304", "https://example.com/\u2800", "https://example.com/\uE000",
            "http://localhost/\u202E", "ftp://example.com/\u202E", "javascript://x/\u200B",
        )) {
            val info = describeLink(text)
            assertEquals(text, LinkVerdict.HIDDEN, info.verdict)
            assertEquals(text, text, info.address)
            assertNull(text, linkOpenTarget(text))
        }
        // Ordinary non-ASCII text is not hidden.
        assertEquals(LinkVerdict.OPEN, verdict("https://example.com/\u043F\u0443\u0442\u044C/\u4E2D\u6587?q=\u00E9"))
        assertEquals(LinkVerdict.OPEN, verdict("https://example.com/a\u0301"))
    }

    @Test fun `what is opened is the address that was shown`() {
        for (text in listOf("https://example.com/a?b=c#d", "https://\u0430\u0440\u0440\u04CF\u0435.com/login", "HTTPS://Example.COM/x", "http://m\u00FCnchen.de/")) {
            val info = describeLink(text)
            assertEquals(text, info.address, linkOpenTarget(text))
            // The address, taken for a link again, is the same one.
            assertEquals(info.address, describeLink(info.address).address)
            assertEquals(LinkVerdict.OPEN, describeLink(info.address).verdict)
        }
    }

    // ---- reading the screen --------------------------------------------------------------------------------------------------------

    /** Rows from the top; a row ending in `\` wraps into the next (the `\` is not a cell); `<>` is one wide character. */
    private fun grid(columns: Int, vararg rows: String) = object : TapGrid {
        private val cells = rows.map { it.removeSuffix("\\").padEnd(columns) }
        override val columns = columns
        override val firstRow = 0
        override val lastRow = rows.size - 1
        override fun wraps(row: Int) = rows[row].endsWith("\\")
        override fun isWideTail(row: Int, column: Int) = cells[row][column] == '>'
        override fun isBlank(row: Int, column: Int) = cells[row][column] == ' '
        override fun text(row: Int, column: Int) = when (val c = cells[row][column]) {
            '>' -> ""
            '<' -> "W"
            else -> c.toString()
        }
    }

    @Test fun `a tap on the link finds it and a tap beside it does not`() {
        val g = grid(40, "Hit:1 https://deb.example.com/wb8 stable", "Reading package lists... Done")
        assertEquals("https://deb.example.com/wb8", linkAt(g, false, 0, 6))
        assertEquals("https://deb.example.com/wb8", linkAt(g, false, 0, 20))
        assertEquals("https://deb.example.com/wb8", linkAt(g, false, 0, 32))
        assertNull(linkAt(g, false, 0, 5))
        assertNull(linkAt(g, false, 0, 33))
        assertNull(linkAt(g, false, 0, 36))
        assertNull(linkAt(g, false, 0, 39))
        assertNull(linkAt(g, false, 1, 3))
        assertNull(linkAt(g, false, 5, 3))
        assertNull(linkAt(g, false, -1, 3))
    }

    @Test fun `the trailing full stop is not part of the tapped link`() {
        val g = grid(40, "see https://example.com/x.")
        assertEquals("https://example.com/x", linkAt(g, false, 0, 10))
        assertNull(linkAt(g, false, 0, 25))
    }

    @Test fun `a link the emulator wrapped is one link from either row`() {
        val g = grid(16, "see https://exam\\", "ple.com/path/to/\\", "file ok")
        val whole = "https://example.com/path/to/file"
        assertEquals(whole, linkAt(g, false, 0, 6))
        assertEquals(whole, linkAt(g, false, 1, 3))
        assertEquals(whole, linkAt(g, false, 2, 2))
        assertNull(linkAt(g, false, 2, 6))
        assertNull(linkAt(g, false, 0, 1))
    }

    @Test fun `a wide character at the wrap does not break the link`() {
        // Row 0 has a blank last column: the wide character after it went to the next row.
        val g = grid(10, "https://a \\", "<>.com/x", "")
        assertEquals("https://aW.com/x", linkAt(g, false, 0, 5))
        assertEquals("https://aW.com/x", linkAt(g, false, 1, 0))
        assertEquals("https://aW.com/x", linkAt(g, false, 1, 1))
        assertEquals("https://aW.com/x", linkAt(g, false, 1, 5))
        assertNull(linkAt(g, false, 1, 9))
    }

    @Test fun `under tmux a link running into the last column joins the next row`() {
        // No wrap mark: tmux draws each row by itself.
        val g = grid(17, "  https://example", "-long.org/page ok")
        assertEquals("https://example-long.org/page", linkAt(g, true, 0, 4))
        assertEquals("https://example-long.org/page", linkAt(g, true, 1, 3))
        // Without tmux's rule the rows stay apart.
        assertEquals("https://example", linkAt(g, false, 0, 4))
        assertNull(linkAt(g, false, 1, 3))
    }

    @Test fun `under tmux three rows join while each reaches the last column`() {
        val g = grid(10, "x http://a", "bbbbbbbbbb", "ccc.com/z")
        assertEquals("http://abbbbbbbbbbccc.com/z", linkAt(g, true, 2, 1))
        assertEquals("http://abbbbbbbbbbccc.com/z", linkAt(g, true, 0, 3))
    }

    @Test fun `under tmux a link that stops short of the last column does not join`() {
        val g = grid(17, "https://a.com/x  ", "next line here")
        assertEquals("https://a.com/x", linkAt(g, true, 0, 3))
        assertNull(linkAt(g, true, 1, 2))
    }

    @Test fun `under tmux a row that fills the last column but is not a link does not glue`() {
        val g = grid(15, "xxxxxxxxxxxxxxx", "https://z.com/q")
        assertEquals("https://z.com/q", linkAt(g, true, 1, 3))
        assertNull(linkAt(g, true, 0, 3))
        // Nor a link that starts on the next row after a full row of words.
        val words = grid(13, "plain words x", "https://a.com")
        assertEquals("https://a.com", linkAt(words, true, 1, 2))
    }

    @Test fun `under tmux the next row must go on with address characters`() {
        val g = grid(14, "see https://ex", "  more text")
        assertEquals("https://ex", linkAt(g, true, 0, 6))
        assertNull(linkAt(g, true, 1, 3))
    }

    @Test fun `a link that is cut at the start of the screen has no scheme and is none`() {
        val g = grid(15, "mple.com/path q")
        assertNull(linkAt(g, true, 0, 2))
    }

    @Test fun `a very long wrapped line does not stall and is no link when too long`() {
        val rows = Array(3000) { "a".repeat(80) + "\\" }
        rows[0] = "https://" + "a".repeat(72) + "\\"
        val g = grid(80, *rows)
        val started = System.nanoTime()
        assertNull(linkAt(g, false, 0, 4))
        assertNull(linkAt(g, false, 1500, 4))
        assertTrue(linksOnScreen(g, false).isEmpty())
        assertTrue((System.nanoTime() - started) / 1_000_000 < 5_000)
        // Short enough: a link.
        val ok = grid(80, "https://" + "a".repeat(72) + "\\", "a".repeat(80) + "\\", "end")
        assertNotNull(linkAt(ok, false, 0, 4))
    }

    @Test fun `the links on the screen are listed once each in reading order`() {
        val g = grid(
            40,
            "Hit:1 https://deb.example.com/wb8 stable",
            "http://localhost:5173/ and ftp://host/f",
            "again https://deb.example.com/wb8.",
            "nothing here",
            "(https://github.com/org/repo_(x))",
        )
        assertEquals(
            listOf("https://deb.example.com/wb8", "http://localhost:5173/", "ftp://host/f", "https://github.com/org/repo_(x)"),
            linksOnScreen(g, false),
        )
        assertEquals(emptyList<String>(), linksOnScreen(grid(20, "no links", "here"), false))
    }

    @Test fun `the list is capped`() {
        val rows = Array(300) { "https://a.com/$it" }
        assertEquals(100, linksOnScreen(grid(30, *rows), false).size)
        assertEquals(5, linksOnScreen(grid(30, *rows), false, limit = 5).size)
    }

    // ---- where a tap goes ----------------------------------------------------------------------------------------------------------

    @Test fun `a tap on a link in output opens the link`() {
        val g = grid(40, "Hit:1 https://deb.example.com/wb8", "root@wb:~# ")
        // The cursor is on the prompt (row 1).
        assertEquals(TapRoute.Link("https://deb.example.com/wb8"), routeTap(g, true, false, 1, 11, 0, 10))
    }

    @Test fun `a tap on the cursor's line stays the cursor's even with an address typed there`() {
        val g = grid(40, "root@wb:~# curl https://example.com/x")
        // Cursor at the end; the tap on the address.
        val route = routeTap(g, true, false, 0, 37, 0, 20)
        assertTrue(route is TapRoute.Cursor)
        assertEquals(20 - 37, (route as TapRoute.Cursor).moves)
        // Also under tmux.
        assertTrue(routeTap(g, true, true, 0, 37, 0, 20) is TapRoute.Cursor)
    }

    @Test fun `a tap on a link in a wrapped command line is the cursor's too`() {
        val g = grid(20, "root@wb:~# curl http\\", "s://example.com/x")
        assertTrue(routeTap(g, true, false, 1, 10, 0, 16) is TapRoute.Cursor)
    }

    @Test fun `in a full-screen program a tap on a link opens it and a tap elsewhere passes`() {
        val g = grid(40, "  Local: http://localhost:5173/", "  press h to show help")
        assertEquals(TapRoute.Link("http://localhost:5173/"), routeTap(g, false, false, 1, 3, 0, 15))
        assertEquals(TapRoute.Pass, routeTap(g, false, false, 1, 3, 1, 5))
        assertEquals(TapRoute.Pass, routeTap(g, false, false, 1, 3, 0, 2))
    }

    @Test fun `a tap beside the cursor's line and any link passes to the view`() {
        val g = grid(40, "output line", "root@wb:~# ")
        assertEquals(TapRoute.Pass, routeTap(g, true, false, 1, 11, 0, 3))
    }

    @Test fun `with the shell not reading the line a tap on the cursor row is a link tap`() {
        // A session that is closed (onShellLine false): the line is not the shell's any more.
        val g = grid(40, "root@wb:~# curl https://example.com/x")
        assertEquals(TapRoute.Link("https://example.com/x"), routeTap(g, false, false, 0, 37, 0, 20))
    }
}
