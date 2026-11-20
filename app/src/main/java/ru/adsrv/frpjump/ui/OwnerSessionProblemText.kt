package ru.adsrv.frpjump.ui

import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.assistant.OwnerSessionAction
import ru.adsrv.frpjump.assistant.OwnerSessionFailure
import ru.adsrv.frpjump.assistant.OwnerSessionProblem

/** Shared wording for the session's inline recovery and the owner's notice. */
fun ownerSessionProblemText(problem: OwnerSessionProblem): Int = if (problem.failure == OwnerSessionFailure.RECORD_SAVE_FAILED) R.string.assistant_session_record_failed else when (problem.action) {
    OwnerSessionAction.OPEN -> if (problem.failure == OwnerSessionFailure.NEEDS_SETUP) R.string.assistant_session_open_setup else R.string.assistant_session_open_unreachable
    OwnerSessionAction.CLOSE -> when (problem.failure) {
        OwnerSessionFailure.NEEDS_SETUP -> R.string.assistant_session_close_setup
        OwnerSessionFailure.UNREACHABLE -> R.string.assistant_session_close_unreachable
        OwnerSessionFailure.CLOSE_UNCONFIRMED -> R.string.assistant_session_close_unconfirmed
        OwnerSessionFailure.RECORD_SAVE_FAILED -> R.string.assistant_session_record_failed
    }
}
