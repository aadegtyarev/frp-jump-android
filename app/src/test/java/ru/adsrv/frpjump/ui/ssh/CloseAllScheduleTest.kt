package ru.adsrv.frpjump.ui.ssh

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import ru.adsrv.frpjump.ssh.CloseAllPlan
import ru.adsrv.frpjump.undo.PendingUndo

/** "Close all sessions of this phone" behind the Undo notice: nothing until it goes, then the tabs, then one kill. */
class CloseAllScheduleTest {
    private var timer: (() -> Unit)? = null
    private var shown: Int? = null
    private var undo: (() -> Unit)? = null

    private val pending = PendingUndo(
        timer = { _, block ->
            timer = block
            val cancel: () -> Unit = { if (timer === block) timer = null }
            cancel
        },
        run = { commit -> runBlocking { commit() } },
        announce = { counts, u ->
            shown = counts?.values?.sum()
            undo = u.takeIf { counts != null }
        },
    )

    /** What happened, in order: "close <id>" and "kill <names>". */
    private val log = mutableListOf<String>()

    private val plan = CloseAllPlan(tabs = listOf(1, 2), orphans = listOf("frpjump-0a1b2c-5"), kill = listOf("frpjump-0a1b2c-1", "frpjump-0a1b2c-5"))

    private fun schedule(p: CloseAllPlan = plan) = scheduleCloseAll(
        pending, p,
        orphanKey = { "tmux:x/$it" },
        closeTab = { log += "close $it" },
        endOnDevice = { log += "kill " + it.joinToString(",") },
    )

    @Test
    fun `nothing is done until the notice goes, then the tabs close and the sessions end once`() {
        assertEquals(3, schedule())
        assertEquals(3, shown)
        assertEquals(emptyList<String>(), log)
        checkNotNull(timer).invoke()
        assertEquals(listOf("close 1", "close 2", "kill frpjump-0a1b2c-1,frpjump-0a1b2c-5"), log)
    }

    @Test
    fun `undo closes and ends nothing`() {
        schedule()
        checkNotNull(undo).invoke()
        assertEquals(emptyList<String>(), pending.hidden.value.toList())
        timer?.invoke()
        assertEquals(emptyList<String>(), log)
    }

    @Test
    fun `a second close all while the notice is up adds nothing and still ends the sessions once`() {
        schedule()
        assertEquals(0, schedule())
        assertEquals(3, shown)
        checkNotNull(timer).invoke()
        assertEquals(1, log.count { it.startsWith("kill") })
    }

    @Test
    fun `a second close all that found more ends its own sessions with its last new action`() {
        schedule()
        val more = plan.copy(orphans = plan.orphans + "frpjump-0a1b2c-6", kill = plan.kill + "frpjump-0a1b2c-6")
        assertEquals(1, schedule(more))
        checkNotNull(timer).invoke()
        assertEquals(
            listOf("close 1", "close 2", "kill frpjump-0a1b2c-1,frpjump-0a1b2c-5", "kill frpjump-0a1b2c-1,frpjump-0a1b2c-5,frpjump-0a1b2c-6"),
            log,
        )
    }

    @Test
    fun `the device could not be asked - the tabs still close, nothing is ended`() {
        assertEquals(2, schedule(CloseAllPlan(tabs = listOf(1, 2), orphans = emptyList(), kill = emptyList())))
        checkNotNull(timer).invoke()
        assertEquals(listOf("close 1", "close 2"), log)
    }
}
