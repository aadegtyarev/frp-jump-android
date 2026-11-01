package ru.adsrv.frpjump.licenses

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.BuildConfig
import java.io.File

/**
 * Settings › Components and licenses against what the release APK holds:
 * the AboutLibraries plugin's list for the release (generated before the
 * tests, see app/build.gradle.kts) and the license texts in the assets.
 */
class ComponentsTest {
    private val json = File("build/generated/aboutLibraries/release/res/raw/aboutlibraries.json").readText()
    private val assets = File("src/main/assets")

    @Test
    fun `every license of the release's components is one the app ships the text of`() {
        val licenses = Json.parseToJsonElement(json).jsonObject.getValue("licenses").jsonObject
        val unknown = licenses.filter { (key, value) ->
            val entry = Json { ignoreUnknownKeys = true }.decodeFromJsonElement(LicenseEntry.serializer(), value)
            licenseFor(key, entry) == null && !saysNothing(entry)
        }.keys
        assertTrue("licenses without a text in assets/licenses: $unknown", unknown.isEmpty())
    }

    @Test
    fun `every component has a license, and every license text is in the assets`() {
        val components = componentsFrom(json)
        assertTrue(components.size > 100)
        // The screen's list is keyed by id: names repeat ("Lifecycle ViewModel" is two artifacts).
        assertEquals(components.size, components.map { it.id }.toSet().size)
        val bare = components.filter { it.licenses.isEmpty() }.map { it.name }
        assertTrue("components without a license: $bare", bare.isEmpty())
        val missing = components.flatMap { it.licenses }.map { it.asset }.toSet().filterNot { File(assets, it).isFile }
        assertTrue("license texts missing: $missing", missing.isEmpty())
    }

    @Test
    fun `the app's own entries are there, frpc at the version built in`() {
        val byName = componentsFrom(json).associateBy { it.name }
        val frp = byName.getValue("frp (frpc, built into the APK, a static Go binary)")
        assertEquals(BuildConfig.FRPC_VERSION, frp.version)
        // A static Go binary: the Go standard library and golang.org/x are in it.
        assertEquals(listOf("licenses/Apache-2.0.txt", "licenses/BSD-3-Clause-Go.txt"), frp.licenses.map { it.asset })
        assertTrue(byName.keys.any { it.startsWith("TextMate grammars") })
        assertTrue(byName.keys.any { it.startsWith("Eclipse tm4e") })
        val sora = componentsFrom(json).filter { it.name.startsWith("Sora Editor") }
        assertEquals(2, sora.size)
        sora.forEach { assertEquals(listOf("licenses/LGPL-2.1.txt"), it.licenses.map { l -> l.asset }) }
        assertEquals(listOf("licenses/EPL-2.0.txt"), byName.values.first { it.name.startsWith("Eclipse tm4e") }.licenses.map { it.asset })
    }

    @Test
    fun `Termux's empty license is left out next to its Apache license`() {
        val termux = componentsFrom(json).filter { it.source?.contains("termux") == true }
        assertEquals(2, termux.size)
        termux.forEach { assertEquals(listOf("licenses/Apache-2.0.txt"), it.licenses.map { l -> l.asset }) }
    }

    @Test
    fun `Sora links to the sources of the version in the APK`() {
        componentsFrom(json).filter { it.name.startsWith("Sora Editor") }.forEach {
            assertEquals("https://github.com/Rosemoe/sora-editor/tree/0.23.7", it.source)
        }
    }

    @Test
    fun `the grammars show their copyright holders too`() {
        val grammars = componentsFrom(json).first { it.name.startsWith("TextMate grammars") }
        assertTrue(grammars.licenses.any { it.asset == "textmate/NOTICE.md" })
    }

    @Test
    fun `reflow joins wrapped lines into paragraphs, keeping headings, items and blank lines`() {
        val text = """
            |MIT License
            |
            |Permission is hereby granted, free of charge, to any person obtaining
            |a copy of this software.
            |
            |   TERMS AND CONDITIONS FOR USE
            |
            |   1. Definitions.
            |
            |      "License" shall mean the terms and conditions for use,
            |      reproduction, and distribution.
            |
            |- log: the five single-letter level patterns
            |  after a look-behind are left out;
            |- CSS: next item
            |
            |  The licenses for most software are designed to take away your
            |freedom to share and change it.
        """.trimMargin()
        assertEquals(
            """
            |MIT License
            |
            |Permission is hereby granted, free of charge, to any person obtaining a copy of this software.
            |
            |   TERMS AND CONDITIONS FOR USE
            |
            |   1. Definitions.
            |
            |      "License" shall mean the terms and conditions for use, reproduction, and distribution.
            |
            |- log: the five single-letter level patterns after a look-behind are left out;
            |- CSS: next item
            |
            |  The licenses for most software are designed to take away your freedom to share and change it.
            """.trimMargin(),
            reflow(text),
        )
    }

    @Test
    fun `reflow leaves a Markdown table and a code block as they are`() {
        val text = """
            |Each grammar comes from the upstream project below,
            |under its license.
            |
            || File | Scope |
            ||---|---|
            || json | source.json |
            |
            |    Docker
            |    Copyright 2012-2017 Docker, Inc.
        """.trimMargin()
        assertEquals(
            """
            |Each grammar comes from the upstream project below, under its license.
            |
            || File | Scope |
            ||---|---|
            || json | source.json |
            |
            |    Docker
            |    Copyright 2012-2017 Docker, Inc.
            """.trimMargin(),
            reflow(text, markdown = true),
        )
    }

    @Test
    fun `the grammars' NOTICE keeps every table row and code line`() {
        val notice = File(assets, "textmate/NOTICE.md").readText()
        val kept = reflow(notice, markdown = true).lines().toSet()
        val verbatim = notice.lines().map { it.trimEnd() }.filter { it.startsWith("|") || it.startsWith("    ") }
        assertTrue(verbatim.size > 15)
        assertEquals(emptyList<String>(), verbatim.filterNot { it in kept })
    }
}
