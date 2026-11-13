package ru.adsrv.frpjump.ops.direct

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.adsrv.frpjump.ops.DirectRefusal
import ru.adsrv.frpjump.ops.Effect
import ru.adsrv.frpjump.ops.OpResult

/** "Retry direct" and "Keep through the server": the Boolean of the choice is a refusal when nothing changed. */
class DirectChoiceTest {
    private val input = DirectChoiceInput("home", "wb")

    private class Choices(val keepAnswer: Boolean) : DirectChoicesPort {
        val calls = mutableListOf<String>()
        override suspend fun retryDirect(profile: String, device: String) { calls += "retry $profile $device" }
        override suspend fun keepThroughServer(profile: String, device: String): Boolean { calls += "keep $profile $device"; return keepAnswer }
    }

    @Test fun `declare their names and that they change only this device`() {
        val retry = DirectRetry(Choices(true))
        val keep = DirectKeep(Choices(true))
        assertEquals(listOf("direct_retry", "direct_keep"), listOf(retry.name, keep.name))
        assertEquals(listOf(Effect.CHANGE_LOCAL, Effect.CHANGE_LOCAL), listOf(retry.effect, keep.effect))
        assertNull(retry.target(input)); assertNull(keep.target(input))
    }

    @Test fun `retry asks for the device and is done`() = runBlocking {
        val choices = Choices(false)
        assertEquals(OpResult.Done(Unit), DirectRetry(choices)(input))
        assertEquals(listOf("retry home wb"), choices.calls)
    }

    @Test fun `keep is done when the choice was tied to a network`() = runBlocking {
        val choices = Choices(true)
        assertEquals(OpResult.Done(Unit), DirectKeep(choices)(input))
        assertEquals(listOf("keep home wb"), choices.calls)
    }

    @Test fun `keep is refused, as nothing changed, when there is no network`() = runBlocking {
        assertEquals(OpResult.Refused(DirectRefusal.NoNetwork), DirectKeep(Choices(false))(input))
    }
}
