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

    @Test
    fun `the verdict on a downloaded file`() {
        val text = "hello".toByteArray()
        assertEquals(Viewable.YES, viewable(5, text, text.size))
        assertEquals(Viewable.TOO_LARGE, viewable(MAX_VIEW_BYTES + 1, text, text.size))
        // A text name holding binary is told by its bytes.
        assertEquals(Viewable.BINARY, viewable(4, byteArrayOf(0x50, 0x4B, 3, 0), 4))
    }

    @Test
    fun `secret-looking names`() {
        for (name in listOf(".env", ".env.local", "prod.env", "server.KEY", "ca.pem", "home.nmconnection", "shadow")) assertTrue(name, looksSecret(name))
        for (name in listOf("env.txt", "app.log", "keys.md", "README")) assertFalse(name, looksSecret(name))
    }

    @Test
    fun `a conf or cfg holding JSON is JSON, else INI`() {
        val serial = "{\n  \"debug\": false,\n  \"ports\": [\n  ]\n}\n"
        assertEquals(TextFormat.JSON, detectFormat("wb-mqtt-serial.conf", serial))
        assertEquals(TextFormat.JSON, detectFormat("x.cfg", "\uFEFF[ {\"a\": 1} ]"))
        assertEquals(TextFormat.INI, detectFormat("mosquitto.conf", "listener 1883\n"))
        // `[section]` of INI, even one closing the file, is not a JSON array.
        assertEquals(TextFormat.INI, detectFormat("x.conf", "[main]\nkey=1\n[empty]"))
        assertEquals(TextFormat.INI, detectFormat("x.conf", "[null-sink]"))
        // A broken or cut JSON start alone is not enough.
        assertEquals(TextFormat.INI, detectFormat("x.conf", "{\"a\": 1,"))
        // Other names keep their format whatever the text.
        assertEquals(TextFormat.INI, detectFormat("x.ini", "{}"))
        assertEquals(TextFormat.JSON, detectFormat("noext", "{\"a\": 1}"))
        assertTrue(looksLikeJson("[1, 2]"))
        assertTrue(looksLikeJson("[true]"))
        assertFalse(looksLikeJson("[x]"))
        assertFalse(looksLikeJson(""))
    }
}
