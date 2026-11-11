package ru.adsrv.frpjump.agent

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SideEffectAdmissionTest {
    @Test fun `absent element does nothing`() = runBlocking {
        admitSideEffect("dispatch")
    }

    @Test fun `refusal throws CancellationException with the category and runs no work`() = runBlocking {
        var work = 0
        try { withContext(SideEffectAdmission { false }) { admitSideEffect("dispatch"); work++ }; fail() }
        catch (refused: CancellationException) { assertTrue(refused.message, refused.message!!.contains("dispatch")) }
        assertEquals(0, work)
    }

    @Test fun `cancelled coroutine is refused before the check is asked`() = runBlocking {
        val asked = CopyOnWriteArrayList<String>()
        val thrown = kotlin.runCatching {
            withContext(SideEffectAdmission { asked += it; true }) {
                currentCoroutineContext().job.cancel()
                admitSideEffect("dispatch")
            }
        }.exceptionOrNull()
        assertTrue(thrown is CancellationException); assertTrue(asked.isEmpty())
    }

    @Test fun `element is inherited by withContext IO async and launch children`() = runBlocking {
        val asked = CopyOnWriteArrayList<String>()
        withContext(SideEffectAdmission { asked += it; true }) {
            withContext(Dispatchers.IO) { admitSideEffect("io") }
            async { admitSideEffect("async") }.await()
            launch { admitSideEffect("launch") }.join()
            coroutineScope { launch(Dispatchers.Default) { admitSideEffect("nested") } }
        }
        assertEquals(setOf("io", "async", "launch", "nested"), asked.toSet()); assertEquals(4, asked.size)
    }

    @Test fun `category strings are passed unchanged`() = runBlocking {
        val categories = listOf("admission", "dispatch", "tool-start", "dns-dispatch", "public-dispatch", "image-dispatch", "source", "ssh-dispatch", "profile-x-dispatch")
        val asked = CopyOnWriteArrayList<String>()
        withContext(SideEffectAdmission { asked += it; true }) { categories.forEach { admitSideEffect(it) } }
        assertEquals(categories, asked.toList())
    }
}
