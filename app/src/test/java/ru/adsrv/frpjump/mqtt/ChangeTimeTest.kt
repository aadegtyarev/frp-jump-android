package ru.adsrv.frpjump.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeTimeTest {
    private var clock = 1_000L
    private val tree = TopicTree { clock }
    private fun p(text: String) = MqttPath.parse(text)
    private fun send(topic: String, payload: String, retain: Boolean = true, onSubscribe: Boolean = false) =
        tree.receive(p(topic), payload.toByteArray(), retain, qos = 0, version = MqttVersion.V5, onSubscribe = onSubscribe)
    private fun node(topic: String) = tree.node(p(topic))!!

    @Test
    fun `retained values re-sent on subscribe are before the session until they change`() {
        send("a/t", "1", onSubscribe = true)
        assertTrue(node("a/t").value!!.beforeSession)
        assertNull(node("a/t").value!!.changedAt)
        assertEquals(LastChange.BeforeSession, lastChange(node("a/t")))
        // The branch counts it but has seen no change.
        assertEquals(0L, node("a").newestChangeAt)
        assertEquals(LastChange.BeforeSession, lastChange(node("a")))

        // Re-sent unchanged (a reconnect): still from before.
        clock = 5_000
        send("a/t", "1", onSubscribe = true)
        assertTrue(node("a/t").value!!.beforeSession)

        // A new value: seen now, also on its branch; the history keeps the old one as from before.
        clock = 9_000
        send("a/t", "2")
        assertEquals(LastChange.At(9_000), lastChange(node("a/t")))
        assertEquals(LastChange.At(9_000), lastChange(node("a")))
        assertEquals(listOf(9_000L, null), tree.history(p("a/t")).map { it.changedAt })
    }

    @Test
    fun `a value that is not retained, or not delivered on subscribe, is live`() {
        send("a/n", "1", retain = false, onSubscribe = true)
        send("a/l", "1", retain = true, onSubscribe = false)
        assertFalse(node("a/n").value!!.beforeSession)
        assertFalse(node("a/l").value!!.beforeSession)
        assertEquals(LastChange.At(1_000), lastChange(node("a")))
    }

    @Test
    fun `the branch card's last change is shown only when it says more than the value's time`() {
        // A value from before with children from before: nothing to add.
        send("v", "1", onSubscribe = true)
        send("v/c", "1", onSubscribe = true)
        assertNull(branchChangeLine(node("v")))
        // A branch without a value of its own: "before connecting".
        assertEquals(LastChange.BeforeSession, branchChangeLine(node("v").copy(value = null)))
        // A child changed later than the value: that time.
        clock = 3_000
        send("v/c", "2")
        assertEquals(LastChange.At(3_000), branchChangeLine(node("v")))
        // The value itself is the newest change: the same time, not repeated.
        clock = 4_000
        send("v", "2")
        assertNull(branchChangeLine(node("v")))
        // Nothing seen at all (the root's `$SYS`): no line.
        assertNull(branchChangeLine(TopicNode(p("\$SYS"), null, 0, 0, false)))
    }
}
