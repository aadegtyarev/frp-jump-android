package ru.adsrv.frpjump.ops.direct

import ru.adsrv.frpjump.ops.DirectRefusal
import ru.adsrv.frpjump.ops.Effect
import ru.adsrv.frpjump.ops.OpResult
import ru.adsrv.frpjump.ops.Operation

/** The device of a profile whose tunnels the answer to "Direct didn't work" is about. */
data class DirectChoiceInput(val profile: String, val device: String)

/** The two answers, as `direct/DirectChoices.kt` does them. */
interface DirectChoicesPort {
    suspend fun retryDirect(profile: String, device: String)
    /** False when the phone is on no network it can tie the choice to: nothing changed. */
    suspend fun keepThroughServer(profile: String, device: String): Boolean
}

/** "Retry direct": every tunnel of the device that is not direct tries again, here and now. */
class DirectRetry(private val choices: DirectChoicesPort) : Operation<DirectChoiceInput, Unit> {
    override val name = "direct_retry"
    override val effect = Effect.CHANGE_LOCAL

    override suspend fun invoke(input: DirectChoiceInput): OpResult<Unit> {
        choices.retryDirect(input.profile, input.device)
        return OpResult.Done(Unit)
    }
}

/** "Keep through the server" on this network; refused, with nothing changed, when there is no network to tie it to. */
class DirectKeep(private val choices: DirectChoicesPort) : Operation<DirectChoiceInput, Unit> {
    override val name = "direct_keep"
    override val effect = Effect.CHANGE_LOCAL

    override suspend fun invoke(input: DirectChoiceInput): OpResult<Unit> =
        OpResult.Done(Unit).also { choices.keepThroughServer(input.profile, input.device) }
}
