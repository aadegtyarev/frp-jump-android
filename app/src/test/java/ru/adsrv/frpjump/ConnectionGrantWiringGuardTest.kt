package ru.adsrv.frpjump

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The app's removal of the assistant's persistent permissions sits in code a JVM test cannot run (the screens' actions and the application class). These
 * guards read the sources: each removal is there, and in the order the contract needs ("before the rest, so a failure leaves none behind").
 */
class ConnectionGrantWiringGuardTest {
    private val base = "src/main/java/ru/adsrv/frpjump/"
    private fun source(path: String) = File(base + path).readText()

    @Test fun `deleting a connection removes its permission first, inside the commit that can fail`() {
        val actions = source("ui/connections/ConnectionActions.kt")
        val commit = actions.substring(actions.indexOf("private suspend fun commitDelete"))
        val forget = commit.indexOf("app.connectionGrants.forget(row.profile, row.connection.id)")
        assertTrue("commitDelete forgets the record", forget > 0)
        assertTrue("before its stored data and the connection itself", forget < commit.indexOf("app.mqtt.changeStored") && forget < commit.indexOf("runtime.connections.remove(row.connection.id)"))
        assertTrue("inside the try whose failure keeps the connection", commit.lastIndexOf("val failure = try", forget) > 0)
    }

    @Test fun `forgetting a profile removes its permissions first in both modes, and an enrollment replaced tells them to end`() {
        val app = source("FrpJumpApp.kt")
        val forget = app.substring(app.indexOf("suspend fun forgetProfile"))
        val removal = forget.indexOf("connectionGrants.forgetProfile(id)")
        assertTrue("forgetProfile forgets the records", removal > 0)
        assertTrue("before profiles.forget, whatever keepData is", removal < forget.indexOf("profiles.forget(id, keepData)"))
        assertTrue("the profiles hear of a replaced enrollment", app.contains("enrollmentReplaced = { id -> connectionGrants.forgetProfile(id) }"))
        val profiles = source("profiles/Profiles.kt")
        assertTrue("and tell it before publishing", profiles.indexOf("enrollmentReplaced(id)") in 1 until profiles.indexOf("_state.value = ProfilesState(loaded = true"))
    }

    @Test fun `the card's Forget clears both lifetimes and the chat is built with the persistent permissions`() {
        val actions = source("ui/connections/ConnectionActions.kt")
        val forget = actions.substring(actions.indexOf("fun forgetAssistant"))
        assertTrue(forget.indexOf("app.sessionGrants.forgetConnection(") in 1 until forget.indexOf("app.connectionGrants.forget("))
        assertTrue(source("FrpJumpApp.kt").contains("grants = sessionGrants, connectionGrants = connectionGrants"))
    }
}
