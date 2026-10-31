package ru.adsrv.frpjump.files.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which files open in the viewer, and in which format. */
class TextFormatTest {
    @Test
    fun `formats by extension and whole name, ignoring case`() {
        val cases = mapOf(
            "README.md" to TextFormat.MARKDOWN,
            "app.log" to TextFormat.LOG,
            "app.log.1" to TextFormat.LOG,
            "syslog" to TextFormat.LOG,
            "syslog.3" to TextFormat.LOG,
            "data.JSON" to TextFormat.JSON,
            "compose.yml" to TextFormat.YAML,
            "wb-mqtt-serial.conf" to TextFormat.INI,
            "frpc.toml" to TextFormat.INI,
            "nginx.service" to TextFormat.INI,
            ".env" to TextFormat.INI,
            ".env.local" to TextFormat.INI,
            ".bashrc" to TextFormat.SHELL,
            "deploy.sh" to TextFormat.SHELL,
            "rules.js" to TextFormat.JS,
        )
        for ((name, format) in cases) assertEquals(name, format, formatByName(name))
        for (name in listOf("notes", "notes.txt", "a.", "image.png", "backup.1")) assertNull(name, formatByName(name))
    }

    @Test
    fun `sniffing the first line`() {
        assertEquals(TextFormat.SHELL, sniffFormat("#!/bin/sh\necho hi"))
        assertEquals(TextFormat.SHELL, sniffFormat("\n#!/usr/bin/env bash\n"))
        assertEquals(TextFormat.SHELL, sniffFormat("#!/bin/busybox ash"))
        assertEquals(TextFormat.JS, sniffFormat("#!/usr/bin/env node\n"))
        assertEquals(TextFormat.PLAIN, sniffFormat("#!/usr/bin/python3\n"))
        assertEquals(TextFormat.JSON, sniffFormat("{\"a\": 1}"))
        assertEquals(TextFormat.JSON, sniffFormat("[{\"a\": 1}]"))
        assertEquals(TextFormat.YAML, sniffFormat("---\na: 1"))
        assertEquals(TextFormat.INI, sniffFormat("[Unit]\nDescription=x"))
        assertEquals(TextFormat.LOG, sniffFormat("2026-10-31 12:00:01 INFO started"))
        assertEquals(TextFormat.LOG, sniffFormat("Oct 31 12:00:01 wb7 kernel: x"))
        assertEquals(TextFormat.LOG, sniffFormat("[    0.000000] Booting Linux"))
        assertEquals(TextFormat.PLAIN, sniffFormat("just some words"))
        assertEquals(TextFormat.PLAIN, sniffFormat(""))
    }

    @Test
    fun `a telling name wins over the content`() {
        assertEquals(TextFormat.MARKDOWN, detectFormat("x.md", "{\"a\": 1}"))
        assertEquals(TextFormat.SHELL, detectFormat("run", "#!/bin/bash"))
        assertEquals(TextFormat.PLAIN, detectFormat("notes.txt", "plain"))
    }

    @Test
    fun `open routes by name and size`() {
        assertEquals(OpenRoute.VIEW, openRoute("app.log", 100))
        assertEquals(OpenRoute.VIEW, openRoute("notes.TXT", 100))
        assertEquals(OpenRoute.VIEW, openRoute("Makefile", 100))
        assertEquals(OpenRoute.VIEW, openRoute("big.log", MAX_VIEW_BYTES))
        assertEquals(OpenRoute.TOO_LARGE, openRoute("big.log", MAX_VIEW_BYTES + 1))
        assertEquals(OpenRoute.SNIFF, openRoute("wb-rules-run", 100))
        assertEquals(OpenRoute.SNIFF, openRoute("backup.1", 100))
        assertEquals(OpenRoute.OTHER_APP, openRoute("photo.JPG", 100))
        assertEquals(OpenRoute.OTHER_APP, openRoute("a.tar.gz", 100))
        // Binary by name before the size: another app either way, without the note.
        assertEquals(OpenRoute.OTHER_APP, openRoute("film.mp4", 10 * MAX_VIEW_BYTES))
    }

    @Test
    fun `text or binary by the first bytes`() {
        assertTrue(looksLikeText("hello\tworld\r\n".toByteArray()))
        assertTrue(looksLikeText("привет, мир — ok ✓".toByteArray()))
        assertTrue(looksLikeText("\u001b[31mred\u001b[0m".toByteArray()))
        assertTrue(looksLikeText(ByteArray(0)))
        assertFalse(looksLikeText(byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 0, 1)))
        assertFalse(looksLikeText(ByteArray(100) { (it * 37 + 128).toByte() }))
        // A sequence cut by the end of the sample does not count against it.
        val cut = "abc".toByteArray() + "ж".toByteArray().copyOf(1)
        assertTrue(looksLikeText(cut))
        // A few stray bytes in a long text pass; Latin-1 text throughout does not.
        assertTrue(looksLikeText("a".repeat(200).toByteArray() + byteArrayOf(0xFF.toByte())))
        assertFalse(looksLikeText("café ".repeat(20).toByteArray(Charsets.ISO_8859_1)))
    }
}
