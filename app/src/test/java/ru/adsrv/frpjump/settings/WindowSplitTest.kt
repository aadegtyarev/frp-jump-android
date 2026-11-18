package ru.adsrv.frpjump.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.assistant.AssistantProviderSettings
import ru.adsrv.frpjump.assistant.adoptWindowSplitFrom
import ru.adsrv.frpjump.ssh.MemoryStorage

/** The window split: history, reply and summary shares of the model's window; the two rules; the conversion of the old values; how they are stored and read. */
class WindowSplitTest {
    @Test fun `defaults and the derived margin, trigger and prune size`() {
        val d = WindowSplit.DEFAULT
        assertEquals(listOf(75, 5, 15), listOf(d.history, d.reply, d.summary))
        assertEquals(20, d.margin); assertEquals(80, d.compactAt); assertEquals(60, d.pruneTo)
        assertEquals(WindowSplit.DEFAULT, AssistantLimits().split); assertTrue(validAssistantLimits(AssistantLimits()))
    }

    @Test fun `tokens are the share of the window, the reply and the summary at least one`() {
        val s = WindowSplit(75, 5, 15)
        assertEquals(25_600, s.replyTokens(512_000)); assertEquals(76_800, s.summaryTokens(512_000))
        assertEquals(1, WindowSplit(75, 1, 15).replyTokens(50)); assertEquals(13_107, s.replyTokens(262_144))
    }

    @Test fun `both rules keep a 5 percent margin and each stepper's top follows them`() {
        val s = WindowSplit(75, 5, 15)
        assertEquals(80, s.historyMax()); assertEquals(20, s.replyMax()); assertEquals(20, s.summaryMax())
        // History + summary at 95: the summary's plus and the history's plus are off.
        val a = WindowSplit(80, 5, 15)
        assertEquals(80, a.historyMax()); assertEquals(15, a.summaryMax()); assertEquals(15, a.replyMax()); assertTrue(a.valid)
        // History + reply at 95.
        val b = WindowSplit(75, 20, 15)
        assertEquals(75, b.historyMax()); assertEquals(20, b.replyMax()); assertTrue(b.valid)
        val c = WindowSplit(90, 5, 5)
        assertEquals(90, c.historyMax()); assertEquals(5, c.replyMax()); assertEquals(5, c.summaryMax()); assertTrue(c.valid)
        assertFalse(WindowSplit(80, 16, 15).valid); assertFalse(WindowSplit(80, 5, 16).valid)
        // Every combination of the three that the tops allow is valid.
        for (h in WindowSplit.HISTORY_RANGE) for (r in WindowSplit.REPLY_RANGE step 3) for (m in WindowSplit.SUMMARY_RANGE step 5) {
            val s2 = WindowSplit(h, r, m)
            assertEquals("$s2", s2.rulesHold, h <= s2.historyMax() && r <= s2.replyMax() && m <= s2.summaryMax())
        }
    }

    @Test fun `old values become the shares once - the usual case 80 minus 5 is 75`() {
        // Window 512,000, reserve 25,600 = 5 %, trigger 80.
        val r = WindowSplit.fromLegacy(512_000, 25_600, 80, 15)
        assertEquals(WindowSplit(75, 5, 15), r.split); assertNull(r.limitedFrom)
        // The reply share is rounded up so the new limit is never below the old one.
        for ((window, old) in listOf(262_144 to 2_048, 100_000 to 5_001, 12_345 to 1_000, 512_000 to 25_601)) {
            val split = WindowSplit.fromLegacy(window, old, 80, 15).split
            assertTrue("$window/$old", split.replyTokens(window) >= old)
        }
    }

    @Test fun `an owner who kept both old defaults gets the new defaults, never fewer output tokens than before`() {
        // Reserve 2,048 and trigger 80 are the old defaults: 75 / 5 / 15 on a big window; a small window keeps at least the old tokens (to 20 %).
        for ((window, reply) in listOf(512_000 to 5, 262_144 to 5, 65_536 to 5, 32_768 to 7, 16_384 to 13, 10_240 to 20)) {
            val r = WindowSplit.fromLegacy(window, 2_048, 80, 15)
            assertEquals("$window", WindowSplit(75, reply, 15), r.split); assertNull("$window", r.limitedFrom)
            assertTrue("$window: ${r.split.replyTokens(window)} tokens", r.split.replyTokens(window) >= 2_048)
        }
        // Over 20 % of the window the old reserve cannot be kept: 20 %, and the form says what it was.
        for (window in listOf(4_096, 8_192)) {
            val r = WindowSplit.fromLegacy(window, 2_048, 80, 15)
            assertEquals("$window", WindowSplit(75, 20, 15), r.split); assertEquals("$window", 2_048, r.limitedFrom)
        }
        // The summary size is always the old one, the rules made to hold.
        assertEquals(WindowSplit(75, 5, 20), WindowSplit.fromLegacy(262_144, 2_048, 80, 20).split)
        // Only the trigger changed: converted by the formula (2,048 of 262,144 rounds up to 1 %).
        assertEquals(WindowSplit(59, 1, 15), WindowSplit.fromLegacy(262_144, 2_048, 60, 15).split)
        // Only the reserve changed: converted.
        assertEquals(WindowSplit(70, 10, 15), WindowSplit.fromLegacy(512_000, 51_200, 80, 15).split)
        assertEquals(WindowSplit(75, 5, 15), WindowSplit.fromLegacy(512_000, 25_600, 80, 15).split)
        assertEquals(WindowSplit(50, 10, 25), WindowSplit.fromLegacy(100_000, 10_000, 60, 25).split)
        // A set share is kept: no default shortcut.
        assertEquals(WindowSplit(60, 1, 15), WindowSplit.fromLegacy(262_144, 2_048, 80, 15, history = 60).split)
    }

    @Test fun `the cut-reserve line goes when a window edit makes the old tokens fit`() {
        assertEquals(256_000, WindowSplit.stillLimited(256_000, 512_000)); assertEquals(256_000, WindowSplit.stillLimited(256_000, null))
        // Even at the biggest window (1,000,000, 20 % = 200,000) 256,000 is still cut; 20 % of 1,280,000 would be 256,000.
        assertEquals(256_000, WindowSplit.stillLimited(256_000, 1_000_000)); assertNull(WindowSplit.stillLimited(256_000, 1_280_000)); assertNull(WindowSplit.stillLimited(null, 512_000))
        assertEquals(2_048, WindowSplit.stillLimited(2_048, 4_096)); assertNull(WindowSplit.stillLimited(2_048, 10_240)); assertNull(WindowSplit.stillLimited(2_048, 100_000))
    }

    @Test fun `a save also writes the old trigger so a downgrade and a later conversion read the same shares`() {
        for (h in listOf(40, 55, 60, 75)) for (r in listOf(1, 5, 15, 20)) for (m in listOf(5, 15, 30)) {
            val split = WindowSplit(h, r, m)
            if (!split.valid || h + r > 90) continue
            val l = AssistantLimits().withSplit(split)
            assertEquals("$split", h + r, l.summaryPercentSet)
            // The provider form writes the reserve in tokens beside it; the conversion reads the same shares back.
            val back = WindowSplit.fromLegacy(262_144, split.replyTokens(262_144), l.summaryPercentSet!!, l.summarySizePercent)
            assertEquals("$split", split, back.split); assertNull(back.limitedFrom)
        }
        // Over 90 the old key holds 90 (its range).
        assertEquals(90, AssistantLimits().withSplit(WindowSplit(80, 15, 15)).summaryPercentSet)
        val storage = MemoryStorage(); SettingsStore(storage).setAssistantLimits(AssistantLimits().withSplit(WindowSplit(70, 10, 15)))
        assertTrue(storage.read()!!, storage.read()!!.contains("\"summary_percent\":80") && storage.read()!!.contains("\"history_percent\":70"))
    }

    @Test fun `the app start converts the saved provider's old values by name, and nothing without a provider`() {
        val storage = MemoryStorage(); val store = SettingsStore(storage)
        store.adoptWindowSplitFrom(null)
        assertNull(SettingsStore(storage).settings.value.assistantLimits.historyPercentSet)
        // Window 512,000, reserve 25,600 (5 %), trigger 60: not the old defaults, so the formula: reply 5, history 55.
        store.adoptWindowSplitFrom(AssistantProviderSettings(contextTokens = 512_000, outputTokens = 25_600, summaryPercent = 60))
        assertEquals(WindowSplit(55, 5, 15), SettingsStore(storage).settings.value.assistantLimits.split)
        val cut = MemoryStorage(); SettingsStore(cut).adoptWindowSplitFrom(AssistantProviderSettings(contextTokens = 512_000, outputTokens = 256_000, summaryPercent = 80))
        val l = SettingsStore(cut).settings.value.assistantLimits
        assertEquals(WindowSplit(60, 20, 15), l.split); assertEquals(256_000, l.replyLimitedFrom)
    }

    @Test fun `the summary stays at least 10 percent of the window under the history, and the history's lowest follows the summary`() {
        assertTrue(WindowSplit(75, 5, 15).valid); assertTrue(WindowSplit(50, 5, 40).valid); assertTrue(WindowSplit(40, 5, 30).valid)
        assertFalse(WindowSplit(45, 5, 40).valid); assertFalse(WindowSplit(40, 5, 31).valid); assertFalse(WindowSplit(40, 5, 40).valid)
        assertEquals(40, WindowSplit(75, 5, 15).historyMin()); assertEquals(45, WindowSplit(75, 5, 35).historyMin()); assertEquals(50, WindowSplit(75, 5, 40).historyMin())
        assertEquals(30, WindowSplit(40, 5, 15).summaryMax()); assertEquals(35, WindowSplit(60, 5, 15).summaryMax()); assertEquals(20, WindowSplit(75, 5, 15).summaryMax())
        // Where the gap is what stops it, it is below the sum: history 45 allows a summary of 35 (gap), not 40 (sum would allow 50).
        assertEquals(35, WindowSplit(45, 5, 15).summaryMax())
        // Made to hold: the history is lowered for the sums first, then the summary gives way to the gap; nothing is ever raised.
        assertEquals(WindowSplit(40, 5, 30), WindowSplit(40, 5, 40).fitted())
        assertEquals(WindowSplit(55, 5, 40), WindowSplit(90, 5, 40).fitted())
        assertEquals(WindowSplit(45, 5, 35), WindowSplit(45, 5, 40).fitted())
        for (h in WindowSplit.HISTORY_RANGE) for (r in WindowSplit.REPLY_RANGE step 4) for (m in WindowSplit.SUMMARY_RANGE) assertTrue("$h/$r/$m", WindowSplit(h, r, m).fitted().valid)
        // A conversion with a low trigger and a big summary: 40 history leaves a summary of 30.
        assertEquals(WindowSplit(40, 5, 30), WindowSplit.fromLegacy(100_000, 5_000, 20, 40).split)
    }

    @Test fun `token counts for labels are short, with the locale's decimal mark`() {
        assertEquals("197K", WindowSplit.compactTokens(196_608)); assertEquals("13K", WindowSplit.compactTokens(13_107)); assertEquals("52K", WindowSplit.compactTokens(52_428))
        assertEquals("2.6K", WindowSplit.compactTokens(2_621)); assertEquals("2,6K", WindowSplit.compactTokens(2_621, ',')); assertEquals("1K", WindowSplit.compactTokens(1_000))
        assertEquals("999", WindowSplit.compactTokens(999)); assertEquals("5", WindowSplit.compactTokens(5)); assertEquals("10K", WindowSplit.compactTokens(10_000))
        assertEquals("1M", WindowSplit.compactTokens(1_000_000)); assertEquals("1.5M", WindowSplit.compactTokens(1_500_000)); assertEquals("200K", WindowSplit.compactTokens(200_000))
        assertEquals("26K", WindowSplit.compactTokens(25_600)); assertEquals("77K", WindowSplit.compactTokens(76_800))
    }

    @Test fun `a reserve over 20 percent is cut to 20 and the old tokens are kept to say so`() {
        val r = WindowSplit.fromLegacy(512_000, 256_000, 80, 15)
        assertEquals(20, r.split.reply); assertEquals(256_000, r.limitedFrom)
        assertEquals(60, r.split.history)
        // Exactly 20 % is not a cut.
        assertNull(WindowSplit.fromLegacy(100_000, 20_000, 80, 15).limitedFrom)
        assertEquals(20, WindowSplit.fromLegacy(100_000, 20_000, 80, 15).split.reply)
        assertEquals(256_000, WindowSplit.fromLegacy(100_000, 256_000, 80, 15).limitedFrom)
    }

    @Test fun `history is the old trigger less the reply, held in 40 to 90, then both rules lower it first`() {
        assertEquals(40, WindowSplit.fromLegacy(100_000, 5_000, 20, 15).split.history)
        assertEquals(40, WindowSplit.fromLegacy(100_000, 5_000, 1, 15).split.history)
        // Trigger 100, reply 1: 99 held to 90, then history + summary 15 passes 95: history 80.
        assertEquals(WindowSplit(80, 1, 15), WindowSplit.fromLegacy(100_000, 1_000, 100, 15).split)
        // Summary 40: history at most 55; reply 20: history at most 75.
        assertEquals(WindowSplit(55, 5, 40), WindowSplit.fromLegacy(100_000, 5_000, 95, 40).split)
        assertEquals(WindowSplit(75, 20, 15), WindowSplit.fromLegacy(100_000, 20_000, 100, 15).split)
        for (trigger in listOf(1, 10, 45, 80, 90, 100)) for (out in listOf(1, 500, 5_000, 20_000, 80_000)) for (summary in listOf(5, 15, 40)) {
            val s = WindowSplit.fromLegacy(100_000, out, trigger, summary).split
            assertTrue("$trigger/$out/$summary $s", s.valid)
        }
    }

    @Test fun `a share that is already set is kept, the others are converted`() {
        val r = WindowSplit.fromLegacy(100_000, 5_000, 80, 15, history = 60, reply = null)
        assertEquals(WindowSplit(60, 5, 15), r.split)
        assertEquals(WindowSplit(70, 10, 15), WindowSplit.fromLegacy(100_000, 90_000, 80, 15, history = 70, reply = 10).split)
        assertNull(WindowSplit.fromLegacy(100_000, 90_000, 80, 15, history = null, reply = 10).limitedFrom)
    }

    private fun converted(provider: Triple<Int, Int, Int>, file: String = "{}"): AssistantLimits {
        val storage = MemoryStorage(file)
        SettingsStore(storage).adoptWindowSplit(provider.first, provider.second, provider.third)
        return SettingsStore(storage).settings.value.assistantLimits
    }

    @Test fun `the store converts the provider's reserve and trigger once, and the 256000 of 512000 case says it was limited`() {
        val l = converted(Triple(512_000, 256_000, 80))
        assertEquals(20, l.replyPercent); assertEquals(60, l.historyPercent); assertEquals(256_000, l.replyLimitedFrom); assertEquals(15, l.summarySizePercent)
        // The old defaults (2,048 tokens, trigger 80) become the new defaults in the file too.
        val kept = converted(Triple(262_144, 2_048, 80))
        assertEquals(WindowSplit(75, 5, 15), kept.split); assertEquals(75, kept.historyPercentSet); assertEquals(5, kept.replyPercentSet); assertNull(kept.replyLimitedFrom)
        val usual = converted(Triple(512_000, 25_600, 80))
        assertEquals(75, usual.historyPercent); assertEquals(5, usual.replyPercent); assertNull(usual.replyLimitedFrom)
        // Once: another value of the provider afterwards changes nothing.
        val storage = MemoryStorage(); val store = SettingsStore(storage)
        store.adoptWindowSplit(512_000, 25_600, 80); store.adoptWindowSplit(100_000, 90_000, 40)
        assertEquals(WindowSplit(75, 5, 15), SettingsStore(storage).settings.value.assistantLimits.split)
    }

    @Test fun `the old trigger limit is preferred to the provider's, the old keys stay in the file and the old summary size is kept`() {
        val file = """{"assistant_limits":{"summary_percent":60,"summary_size_percent":25,"fresh_tool_percent":30,"reply_chars":64000}}"""
        val storage = MemoryStorage(file)
        SettingsStore(storage).adoptWindowSplit(100_000, 5_000, 90)
        val l = SettingsStore(storage).settings.value.assistantLimits
        assertEquals(55, l.historyPercent); assertEquals(5, l.replyPercent); assertEquals(25, l.summarySizePercent); assertEquals(60, l.summaryPercentSet)
        // The old trigger stays in the file as it was, for an older version; the new keys are beside it.
        val text = storage.read()!!
        assertTrue(text, text.contains("\"summary_percent\":60") && text.contains("\"history_percent\":55") && text.contains("\"reply_percent\":5"))
        assertFalse(text, text.contains("reply_chars") || text.contains("fresh_tool"))
    }

    @Test fun `without a provider the shares stay absent and read as 75, 5 and 15`() {
        val l = SettingsStore(MemoryStorage("""{"assistant_enabled":true}""")).settings.value.assistantLimits
        assertNull(l.historyPercentSet); assertNull(l.replyPercentSet); assertEquals(WindowSplit.DEFAULT, l.split)
    }

    @Test fun `a stored share is clamped into its range and the rules are made to hold on read, history lowered first`() {
        fun read(json: String) = SettingsStore(MemoryStorage("""{"assistant_limits":$json}""")).settings.value.assistantLimits
        assertEquals(90, read("""{"history_percent":99,"reply_percent":1,"summary_size_percent":5}""").historyPercent)
        assertEquals(40, read("""{"history_percent":3}""").historyPercent)
        assertEquals(20, read("""{"reply_percent":50}""").replyPercent); assertEquals(1, read("""{"reply_percent":0}""").replyPercent)
        assertEquals(40, read("""{"summary_size_percent":90}""").summarySizePercent); assertEquals(5, read("""{"summary_size_percent":1}""").summarySizePercent)
        val tight = read("""{"history_percent":90,"reply_percent":20,"summary_size_percent":40}""")
        assertEquals(55, tight.historyPercent); assertEquals(20, tight.replyPercent); assertEquals(40, tight.summarySizePercent); assertTrue(validAssistantLimits(tight))
        // A summary size over what the default history leaves, with no history set: the effective history gives way, nothing is refused.
        val unset = read("""{"summary_size_percent":40}""")
        assertNull(unset.historyPercentSet); assertEquals(55, unset.split.history); assertTrue(validAssistantLimits(unset))
        assertEquals(WindowSplit.DEFAULT, read("""{"history_percent":"many","reply_percent":"x"}""").split)
        assertNull(read("""{"reply_limited_from":-5}""").replyLimitedFrom); assertEquals(256_000, read("""{"reply_limited_from":256000}""").replyLimitedFrom)
    }

    @Test fun `the store refuses shares that break a range or a rule, and keeps ones that hold`() {
        val store = SettingsStore(MemoryStorage())
        val d = AssistantLimits()
        assertThrows(IllegalArgumentException::class.java) { store.setAssistantLimits(d.withSplit(WindowSplit(80, 16, 15))) }
        assertThrows(IllegalArgumentException::class.java) { store.setAssistantLimits(d.withSplit(WindowSplit(80, 5, 16))) }
        assertThrows(IllegalArgumentException::class.java) { store.setAssistantLimits(d.withSplit(WindowSplit(39, 5, 15))) }
        assertThrows(IllegalArgumentException::class.java) { store.setAssistantLimits(d.withSplit(WindowSplit(75, 21, 15))) }
        assertThrows(IllegalArgumentException::class.java) { store.setAssistantLimits(d.withSplit(WindowSplit(40, 5, 35))) }
        store.setAssistantLimits(d.withSplit(WindowSplit(80, 15, 15), limitedFrom = 300_000))
        val back = SettingsStore(store.let { MemoryStorage(storageText(it)) }).settings.value.assistantLimits
        assertEquals(WindowSplit(80, 15, 15), back.split); assertEquals(300_000, back.replyLimitedFrom)
    }

    private fun storageText(store: SettingsStore): String = ru.adsrv.frpjump.api.WireJson.encodeToString(AppSettings.serializer(), store.settings.value)
}
