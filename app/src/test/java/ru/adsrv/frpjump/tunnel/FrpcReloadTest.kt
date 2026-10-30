package ru.adsrv.frpjump.tunnel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * [FrpcRunner] against a stand-in for frpc's admin API (`GET /api/reload`
 * with basic auth, which re-reads the config file), written in Python.
 */
class FrpcReloadTest {
    private val dir = File("build/tmp/frpc-reload-test-${System.nanoTime()}").absoluteFile
    private val work = File(dir, "frpc")
    private val fake = File(dir, "fake-frpc")
    private lateinit var runner: FrpcRunner

    @Before
    fun setUp() {
        assumeTrue("python3 is needed for the fake frpc", File("/usr/bin/python3").canExecute())
        dir.mkdirs()
        fake.writeText(FAKE_FRPC)
        fake.setExecutable(true)
        runner = FrpcRunner(fake, work)
    }

    @After
    fun tearDown() {
        if (::runner.isInitialized) runner.wipe()
        dir.deleteRecursively()
    }

    /** The fake's config: what the real one says about the admin API, plus a switch to refuse reloads. */
    private fun launch(vararg grants: String, relayOnly: Boolean = false, refuseReload: Boolean = false) = FrpcLaunch(
        configFor = { _, admin ->
            "port=${admin.port}\nuser=${admin.user}\npassword=${admin.password}\nrefuse=$refuseReload\nvisitors=${grants.joinToString(",")}\n"
        },
        certPem = "cert",
        keyPem = "key",
        caPem = "ca",
        visitors = grants.mapIndexed { i, g -> VisitorSpec(g, "s", 47300 + i) },
        relayOnly = relayOnly,
        connection = "relay",
    )

    private fun waitForLog(text: String, count: Int = 1) {
        val deadline = System.currentTimeMillis() + 5_000
        while (runner.recentLog().count { text in it } < count && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("log has $count x \"$text\": ${runner.recentLog()}", runner.recentLog().count { text in it } >= count)
    }

    @Test
    fun `a tunnel added or removed reloads the running frpc`() {
        runner.apply(launch("g1"), setOf(47300))
        assertEquals(FrpcRunner.Change.STARTED, runner.lastChange)
        waitForLog("admin ready")
        runner.apply(launch("g1", "g2"), setOf(47300, 47301))
        assertEquals(FrpcRunner.Change.RELOADED, runner.lastChange)
        waitForLog("reloaded visitors=g1,g2")
        runner.apply(launch("g2"), setOf(47301))
        assertEquals(FrpcRunner.Change.RELOADED, runner.lastChange)
        waitForLog("reloaded visitors=g2")
        // Still the first process: it announced itself once.
        assertEquals(1, runner.recentLog().count { "admin ready" in it })
    }

    @Test
    fun `an unchanged config does nothing`() {
        runner.apply(launch("g1"), setOf(47300))
        waitForLog("admin ready")
        runner.apply(launch("g1"), setOf(47300))
        assertEquals(FrpcRunner.Change.NONE, runner.lastChange)
    }

    @Test
    fun `a refused reload or a relay-only switch restarts frpc`() {
        runner.apply(launch("g1"), setOf(47300))
        waitForLog("admin ready")
        runner.apply(launch("g1", "g2", refuseReload = true), setOf(47300, 47301))
        assertEquals(FrpcRunner.Change.STARTED, runner.lastChange)
        waitForLog("admin ready", count = 2)
        runner.apply(launch("g1", "g2", relayOnly = true), setOf(47300, 47301))
        assertEquals(FrpcRunner.Change.STARTED, runner.lastChange)
        waitForLog("admin ready", count = 3)
    }

    private companion object {
        val FAKE_FRPC = """
            #!/usr/bin/python3
            import base64, sys
            from http.server import BaseHTTPRequestHandler, HTTPServer

            path = sys.argv[2]
            def conf():
                return dict(l.split("=", 1) for l in open(path).read().splitlines() if "=" in l)

            start = conf()
            expected = "Basic " + base64.b64encode((start["user"] + ":" + start["password"]).encode()).decode()

            class Admin(BaseHTTPRequestHandler):
                def log_message(self, *args):
                    pass
                def do_GET(self):
                    now = conf()
                    if self.headers.get("Authorization") != expected:
                        self.send_response(401)
                    elif not self.path.startswith("/api/reload") or now["refuse"] == "true":
                        self.send_response(500)
                    else:
                        print("reloaded visitors=" + now["visitors"], flush=True)
                        self.send_response(200)
                    self.end_headers()

            server = HTTPServer(("127.0.0.1", int(start["port"])), Admin)
            print("admin ready", flush=True)
            server.serve_forever()
        """.trimIndent() + "\n"
    }
}
