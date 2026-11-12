package ru.adsrv.frpjump.assistant.chat

import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.ProviderSetupException
import ru.adsrv.frpjump.assistant.ProviderSetupProblem

/** Pure error mapping of a finished Send. */

/** A failed summary step wins; otherwise the kernel's finish, refined by what the transport reported. */
internal fun finishProblem(summaryProblem: ChatProblem?, finish: AgentFinish, transportTimeout: Boolean, invalidReply: Boolean): ChatProblem? =
    summaryProblem ?: when (finish) {
        AgentFinish.COMPLETE -> null; AgentFinish.CONTEXT_FULL -> ChatProblem.CONTEXT_FULL; AgentFinish.MODEL_TIMEOUT -> ChatProblem.TIMEOUT
        AgentFinish.INVALID_REPLY, AgentFinish.STEP_LIMIT -> ChatProblem.INVALID_REPLY
        else -> if (transportTimeout) ChatProblem.TIMEOUT else if (invalidReply) ChatProblem.INVALID_REPLY else ChatProblem.PROVIDER
    }

/** Key and read problems keep their own message; everything else is [fallback] (SETUP for the run, SUMMARY for the summary step). */
internal fun setupProblem(failure: Exception, fallback: ChatProblem): ChatProblem = when ((failure as? ProviderSetupException)?.problem) {
    ProviderSetupProblem.CRYPTO_FAILED, ProviderSetupProblem.ADDRESS_CHANGED, ProviderSetupProblem.INVALID_KEY -> ChatProblem.KEY
    ProviderSetupProblem.READ_FAILED -> ChatProblem.READ
    else -> fallback
}

internal fun isTransportTimeout(kind: ProviderErrorKind): Boolean = kind == ProviderErrorKind.TIMEOUT

internal fun isInvalidReply(kind: ProviderErrorKind): Boolean = kind in setOf(ProviderErrorKind.INVALID_RESPONSE,
    ProviderErrorKind.INCOMPLETE_RESPONSE, ProviderErrorKind.RESPONSE_TOO_LARGE)
