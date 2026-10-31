package ru.adsrv.frpjump.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApkCheckTest {
    private val key = "a".repeat(64)
    private val installed = ApkFacts("ru.adsrv.frpjump", 3, "0.2.0-beta1", setOf(key))
    private val update = ApkFacts("ru.adsrv.frpjump", 4, "0.2.1", setOf(key))

    @Test
    fun `this app, newer, the release's version, the same key`() {
        assertNull(apkProblem(update, installed, "0.2.1"))
        // The tag may carry a "v".
        assertNull(apkProblem(update, installed, "v0.2.1"))
    }

    @Test
    fun `an unreadable or unsigned file`() {
        assertEquals(ApkProblem.UNREADABLE, apkProblem(null, installed, "0.2.1"))
        assertEquals(ApkProblem.UNREADABLE, apkProblem(update.copy(signers = emptySet()), installed, "0.2.1"))
    }

    @Test
    fun `another app`() {
        assertEquals(ApkProblem.OTHER_APP, apkProblem(update.copy(packageName = "com.example.evil"), installed, "0.2.1"))
    }

    @Test
    fun `not a higher versionCode`() {
        assertEquals(ApkProblem.NOT_NEWER, apkProblem(update.copy(versionCode = 3), installed, "0.2.1"))
        assertEquals(ApkProblem.NOT_NEWER, apkProblem(update.copy(versionCode = 2), installed, "0.2.1"))
    }

    @Test
    fun `not the version the release names`() {
        assertEquals(ApkProblem.OTHER_VERSION, apkProblem(update.copy(versionName = "0.2.0"), installed, "0.2.1"))
        assertEquals(ApkProblem.OTHER_VERSION, apkProblem(update.copy(versionName = null), installed, "0.2.1"))
        assertEquals(ApkProblem.OTHER_VERSION, apkProblem(update.copy(versionName = "dev"), installed, "0.2.1"))
    }

    @Test
    fun `another signing key, an extra one, or none known for the installed app`() {
        assertEquals(ApkProblem.OTHER_SIGNER, apkProblem(update.copy(signers = setOf("b".repeat(64))), installed, "0.2.1"))
        assertEquals(ApkProblem.OTHER_SIGNER, apkProblem(update.copy(signers = setOf(key, "b".repeat(64))), installed, "0.2.1"))
        assertEquals(ApkProblem.OTHER_SIGNER, apkProblem(update, installed.copy(signers = emptySet()), "0.2.1"))
    }
}
