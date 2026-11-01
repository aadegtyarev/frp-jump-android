package ru.adsrv.frpjump.tunnel

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FrpcRunnerTest {
    // Under the build dir, not the system temp dir.
    private val dir = File("build/tmp/frpc-runner-test-${System.nanoTime()}").absoluteFile
    private val work = File(dir, "frpc")
    private val key = File(work, "tls.key")
    private val config = File(work, "frpc.toml")

    /** A stand-in for frpc that prints a line and exits at once. */
    private val exitingFrpc = File(dir, "fake-frpc").apply {
        parentFile.mkdirs()
        writeText("#!/bin/sh\necho 'start frpc service'\nexit 1\n")
        setExecutable(true)
    }

    private fun launch() = FrpcLaunch(
        configFor = { _, _ -> "config" },
        certPem = "cert",
        keyPem = "key",
        caPem = "ca",
        visitors = listOf(VisitorSpec("g1", "s1", 47200)),
        connection = "server",
    )

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `a new runner deletes a key and config left by a killed process`() {
        work.mkdirs()
        key.writeText("old key")
        config.writeText("old config")
        FrpcRunner(exitingFrpc, work)
        assertFalse(key.exists())
        assertFalse(config.exists())
    }

    @Test
    fun `frpc exiting by itself takes the key and config with it`() {
        val runner = FrpcRunner(exitingFrpc, work)
        runner.apply(launch(), setOf(47200))
        val deadline = System.currentTimeMillis() + 5_000
        while ((key.exists() || config.exists()) && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertFalse(key.exists())
        assertFalse(config.exists())
        assertTrue(runner.heldPorts.isEmpty())
    }

    @Test
    fun `wipe removes the whole work dir`() {
        val runner = FrpcRunner(exitingFrpc, work)
        runner.apply(launch(), setOf(47200))
        runner.wipe()
        assertFalse(work.exists())
    }
}
