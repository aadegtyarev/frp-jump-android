package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.agent.ToolOutcome
import ru.adsrv.frpjump.settings.AssistantTools

class WebDocumentsTest {
    private fun html(text: String, status: Int = 200, retry: String? = null) = WebResponse("https://public.org/docs", "text/html", text.toByteArray(), status, retry)
    private val normalSearch = """<a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fpublic.org%2Fdoc">Documentation</a>"""
    @Test fun `DOM reader preserves code whitespace resolves links images and never includes executable markup`() {
        val page = WebDocuments.page(html("""<title>Guide</title><base href="https://public.org/base/"><main><h1>Documentation</h1><p>Useful static content with enough text to read.</p><pre>  line one
    line two</pre><ul><li>First item</li><li>Second item</li></ul><a href="next">Next</a><img src="i.png" alt="Diagram"><script>secret script</script></main>"""))
        assertEquals("Guide", page.title); assertTrue(page.text.contains("  line one\n    line two")); assertFalse(page.text.contains("secret script"))
        assertEquals(ChatSource("Next", "https://public.org/base/next"), page.links.single())
        assertEquals(ChatSource("Diagram", "https://public.org/base/i.png"), page.images.single())
        assertTrue(page.result("https://public.org").contains("not visually inspected"))
    }
    @Test fun `page MIME shells caps and clipped markers are truthful`() {
        assertThrows(WebException::class.java) { WebDocuments.page(html("<div id=app></div><script>load()</script>")) }
        assertThrows(WebException::class.java) { WebDocuments.page(WebResponse("https://public.org", "application/pdf", byteArrayOf(), 200, null)) }
        val page = WebDocuments.page(html("<p>${"x".repeat(9000)}</p>" + (1..110).joinToString("") { "<a href='/p$it'>link</a><img src='/i$it.png'>" }))
        assertEquals(8000, page.text.length); assertEquals(100, page.links.size); assertEquals(20, page.images.size)
        assertTrue(page.clipped); assertTrue(page.referencesClipped); assertTrue(page.result("url").contains("reference list clipped"))
        val plain = WebDocuments.page(WebResponse("https://public.org", "text/markdown", "# Code\n\n    keep whitespace".toByteArray(), 200, null))
        assertTrue(plain.text.contains("    keep whitespace"))
    }
    @Test fun `DuckDuckGo results block drift empty and rate failures use explicit dispositions`() {
        assertEquals(listOf(ChatSource("Documentation", "https://public.org/doc")), WebDocuments.search(html(normalSearch)))
        assertEquals("CAPTCHA guide", WebDocuments.search(html(normalSearch.replace("Documentation", "CAPTCHA guide"))).single().title)
        for (response in listOf(html("CAPTCHA"), html("<form id=challenge-form></form>"), html("<form id=challenge-form></form>", 202), html("", 403), html("", 429))) {
            val failed = assertThrows(WebException::class.java) { WebDocuments.search(response) }
            assertEquals(WebFailure.BLOCKED, failed.reason)
            assertEquals(response.status, failed.status)
        }
        for (text in listOf("No results", "<a class=changed href='https://public.org'>drift</a>"))
            assertEquals(WebFailure.EMPTY, assertThrows(WebException::class.java) { WebDocuments.search(html(text)) }.reason)
    }
    @Test fun `monotonic process and per send limits count failures no cooldown queue without content cache`() {
        var now = 0L; val state = WebSearchState { now }; val budget = WebBudget()
        state.charge(budget)
        assertThrows(WebException::class.java) { state.charge(budget) }
        now += 10_000; state.charge(budget); now += 10_000; state.charge(budget); now += 10_000
        assertThrows(WebException::class.java) { state.charge(budget) }
        repeat(17) { state.charge(WebBudget()); now += 10_000 }
        assertThrows(WebException::class.java) { state.charge(WebBudget()) }
        now += 3_600_000; state.charge(WebBudget())
        state.httpDispatch(); assertThrows(WebException::class.java) { state.httpDispatch() }
        val blocked = WebBudget(); state.block(blocked, "999999999")
        now += 900_000; assertThrows(WebException::class.java) { state.charge(WebBudget()) }
        now += 86_400_000; state.charge(WebBudget())
        assertThrows(WebException::class.java) { state.charge(blocked) }
    }
    @Test fun `fixed tools never reuse shared search payload and allow read after block`() = runBlocking {
        var now = 0L; var requests = 0; var blocked = false
        val search = WebSearchState { now }
        val fetcher = PublicFetcher { url, _, dispatch ->
            dispatch("public-dispatch") { requests++ }
            if (url.contains("duckduckgo")) html(if (blocked) "CAPTCHA" else normalSearch)
            else html("<title>Read</title><p>Useful documentation with enough content for a successful static read.</p>")
        }
        fun session() = AssistantWebTools(AssistantTools(true, true), fetcher, search, null, { _, start -> start() }, {})
        fun query(text: String = "q") = buildJsonObject { put("query", text) }
        val one = session()
        assertEquals(listOf("web_search", "read_page"), one.tools.map { it.spec.name })
        assertEquals(ToolOutcome.SUCCESS, one.tools[0].execute(query()).outcome)
        val immediate = session()
        assertEquals(ToolOutcome.FAILED, immediate.tools[0].execute(query()).outcome)
        assertTrue(immediate.sources.isEmpty()); assertEquals(1, requests)
        now += 10_000
        assertEquals(ToolOutcome.SUCCESS, session().tools[0].execute(query()).outcome); assertEquals(2, requests)
        now += 10_000; blocked = true
        val two = session()
        assertEquals(ToolOutcome.FAILED, two.tools[0].execute(query("blocked")).outcome)
        assertEquals(ToolOutcome.FAILED, two.tools[0].execute(query("other")).outcome); assertEquals(3, requests)
        assertEquals(ToolOutcome.SUCCESS, two.tools[1].execute(buildJsonObject { put("url", "https://public.org") }).outcome)
        assertEquals(4, requests); assertEquals(1, two.sources.size)
    }
    @Test fun `DNS failures consume search attempts and cooldown refuses before another resolution`() = runBlocking {
        var attempts = 0
        val session = AssistantWebTools(AssistantTools(search = true), PublicFetcher { _, _, dispatch ->
            dispatch("dns-dispatch") { attempts++ }; throw WebException(WebFailure.NETWORK)
        }, WebSearchState { 0L }, null, { _, start -> start() }, {})
        val query = buildJsonObject { put("query", "q") }
        assertEquals(ToolOutcome.FAILED, session.tools.single().execute(query).outcome)
        assertEquals(ToolOutcome.FAILED, session.tools.single().execute(query).outcome)
        assertEquals(1, attempts)
    }

    @Test fun `WEB-READER-02 amended policy returns bounded literal static data without JS detection`() = runBlocking {
        val available = listOf("v1.2", "port=443", "OK", "Версия 2", "v1.2 requires JavaScript", "Config: enable JavaScript = false",
            "Please enable JavaScript", "Please enable JavaScript to continue using this website.", "JavaScript is required", "requires JavaScript", "enable JavaScript",
            "Sign in to read this article", "Article text. JavaScript is required for interactive diagrams.")
        for (mime in listOf("text/html", "application/xhtml+xml")) {
            for (text in available) {
                val response = WebResponse("https://public.org/doc", mime, "<title>Static page</title><p>$text</p><script>loadArticle()</script>".toByteArray(), 200, null)
                val page = WebDocuments.page(response)
                assertEquals(text, page.text)
                assertEquals("Static page", page.title)
                val result = page.result(response.url)
                assertTrue(result.contains("no JavaScript was executed")); assertTrue(result.contains("Content may be incomplete"))
                assertFalse(result.contains("loadArticle()"))
                val session = AssistantWebTools(AssistantTools(readPage = true), PublicFetcher { _, _, _ -> response }, WebSearchState(), null, { _, start -> start() }, {})
                assertEquals(ToolOutcome.SUCCESS, session.tools.single().execute(buildJsonObject { put("url", response.url) }).outcome)
                assertEquals(listOf(ChatSource("Static page", response.url)), session.sources)
            }
            assertEquals("Fallback v1", WebDocuments.page(WebResponse("https://public.org", mime, "<body><noscript>Fallback v1</noscript></body>".toByteArray(), 200, null)).text)
            for (empty in listOf("", "<div></div>", "<div id=app></div><script>load()</script>"))
                assertEquals(WebFailure.EMPTY, assertThrows(WebException::class.java) { WebDocuments.page(WebResponse("https://public.org", mime, empty.toByteArray(), 200, null)) }.reason)
            assertEquals(WebFailure.HTTP, assertThrows(WebException::class.java) { WebDocuments.page(WebResponse("https://public.org", mime, "available".toByteArray(), 503, null)) }.reason)
            val clipped = WebDocuments.page(WebResponse("https://public.org", mime, ("<p>JavaScript is required. " + "x".repeat(9000) + "</p>").toByteArray(), 200, null))
            assertEquals(8000, clipped.text.length); assertTrue(clipped.clipped)
        }
    }

    @Test fun `WEB-READER-03 declared headers HTML meta and UTF8 preserve EN RU text or fail explicitly`() {
        val text = "Version / Версия 2"
        for (mime in listOf("text/plain", "text/markdown", "text/x-markdown", "text/html", "application/xhtml+xml")) {
            val content = if (mime.contains("html")) "<p>$text</p>" else text
            for (encoding in listOf("UTF-8", "windows-1251")) {
                val response = WebResponse("https://public.org/doc", mime, content.toByteArray(java.nio.charset.Charset.forName(encoding)), 200, null, encoding)
                assertEquals(text, WebDocuments.page(response).text)
            }
            assertThrows(WebException::class.java) { WebDocuments.page(WebResponse("https://public.org", mime, content.toByteArray(), 200, null, "not-an-encoding")) }
        }
        val markdown = "[руководство](next.md) ![схема](fig.png)".toByteArray(java.nio.charset.Charset.forName("windows-1251"))
        val decoded = WebDocuments.page(WebResponse("https://public.org/base/a.md", "text/markdown", markdown, 200, null, "windows-1251"))
        assertEquals(ChatSource("руководство", "https://public.org/base/next.md"), decoded.links.single())
        assertEquals(ChatSource("схема", "https://public.org/base/fig.png"), decoded.images.single())
        val meta = "<meta charset=windows-1251><p>$text</p>".toByteArray(java.nio.charset.Charset.forName("windows-1251"))
        assertEquals(text, WebDocuments.page(WebResponse("https://public.org", "text/html", meta, 200, null)).text)
        assertThrows(WebException::class.java) { WebDocuments.page(WebResponse("https://public.org", "text/plain", byteArrayOf(-1), 200, null)) }
        assertThrows(WebException::class.java) { WebDocuments.page(WebResponse("https://public.org", "text/plain", text.toByteArray(), 200, null, "x".repeat(65))) }
    }

    @Test fun `WEB-READER-06 Markdown resolves inline full collapsed shortcut and image references but ignores code`() {
        val raw = """[guide](next.md) ![diagram](fig.png)
[full][ref] [ref][] [ref]
![reference alt][picture]
<https://public.org/auto> https://public.org/bare

[ref]: other.md
[picture]: /image.png

`[not link](code.md)`

```
![not image](code.png)
```
"""
        val page = WebDocuments.page(WebResponse("https://public.org/base/readme.md", "text/markdown", raw.toByteArray(), 200, null))
        assertEquals(raw, page.text)
        assertEquals(listOf("https://public.org/base/next.md", "https://public.org/base/other.md", "https://public.org/auto", "https://public.org/bare"), page.links.map { it.url })
        assertEquals(listOf(ChatSource("diagram", "https://public.org/base/fig.png"), ChatSource("reference alt", "https://public.org/image.png")), page.images)
        val many = (1..110).joinToString("\n") { "[link$it](p$it.md) ![alt$it](i$it.png)" } + "\n[unsafe](http://127.0.0.1)"
        val bounded = WebDocuments.page(WebResponse("https://public.org/", "text/x-markdown", many.toByteArray(), 200, null))
        assertEquals(100, bounded.links.size); assertEquals(20, bounded.images.size)
        assertTrue(bounded.referencesClipped); assertTrue(bounded.result("url").contains("reference list clipped"))
    }

    @Test fun `fixed technical failure descriptions include known statuses limits and never raw errors`() = runBlocking {
        for (reason in WebFailure.entries) {
            val message = webFailureDescription(WebException(reason, 503))
            assertTrue(message.length > 25)
            assertFalse(message.contains("secret"))
        }
        assertTrue(webFailureDescription(WebException(WebFailure.HTTP, 503)).contains("HTTP 503"))
        assertTrue(webFailureDescription(WebException(WebFailure.TOO_LARGE)).contains("1 MiB"))
        assertTrue(webFailureDescription(WebException(WebFailure.IMAGE, 400, ru.adsrv.frpjump.agent.openai.ProviderFailure.HTTP)).contains("HTTP 400"))
        for (failure in ru.adsrv.frpjump.agent.openai.ProviderFailure.entries) {
            val description = webFailureDescription(WebException(WebFailure.IMAGE, imageFailure = failure))
            val expected = when (failure) {
                ru.adsrv.frpjump.agent.openai.ProviderFailure.HTTP -> "Image API request failed"
                ru.adsrv.frpjump.agent.openai.ProviderFailure.TIMEOUT -> "Image API request timed out"
                ru.adsrv.frpjump.agent.openai.ProviderFailure.NETWORK -> "Image API network request failed"
                ru.adsrv.frpjump.agent.openai.ProviderFailure.REQUEST_TOO_LARGE -> "Encoded image API request exceeds"
                ru.adsrv.frpjump.agent.openai.ProviderFailure.RESPONSE_TOO_LARGE -> "Image API response exceeds"
                ru.adsrv.frpjump.agent.openai.ProviderFailure.INCOMPLETE_RESPONSE -> "Image API response is incomplete or filtered"
                else -> "Invalid image or image API reply"
            }
            assertTrue(description, description.startsWith(expected))
        }
        val tools = AssistantWebTools(AssistantTools(readPage = true), PublicFetcher { _, _, _ -> error("secret body/key/URL") }, WebSearchState(), null, { _, start -> start() }, {})
        val result = tools.tools.single().execute(buildJsonObject { put("url", "https://public.org") })
        assertEquals(ToolOutcome.FAILED, result.outcome)
        assertTrue(result.text.contains("Network request failed")); assertFalse(result.text.contains("secret"))
        val error = assertThrows(WebException::class.java) { WebDocuments.page(html("private body", 503)) }
        assertEquals(503, error.status)
    }

}
