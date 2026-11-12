package ru.adsrv.frpjump.ops

import ru.adsrv.frpjump.settings.GrantTarget

/** What is known about the remote side after an operation that did not finish. */
enum class RemoteOutcome { NOT_STARTED, UNKNOWN, KNOWN }

/** The result of an [Operation]; the caller (a screen or an assistant tool) decides how to say it. */
sealed interface OpResult<out T> {
    data class Done<T>(val value: T) : OpResult<T>

    /** Nothing was started. */
    data class Refused(val reason: OpRefusal) : OpResult<Nothing>

    /** Started; the remote effect may be unknown. [outcome] says what is known about it. */
    data class Failed(val reason: OpFailure, val outcome: RemoteOutcome) : OpResult<Nothing>
}

/**
 * One thing the app can do, independent of who asks: the UI and the assistant are two adapters over it. It never speaks to a person
 * or a model: failures are typed ([OpRefusal], [OpFailure]) and each adapter words them. Cancellation propagates; it is never a result.
 */
interface Operation<in I, out O> {
    val name: String
    val effect: Effect

    /** For changing operations: the grant/confirmation target of [input]; null when there is none. */
    fun target(input: I): GrantTarget? = null

    suspend operator fun invoke(input: I): OpResult<O>
}
