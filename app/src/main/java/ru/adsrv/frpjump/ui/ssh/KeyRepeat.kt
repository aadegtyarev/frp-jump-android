package ru.adsrv.frpjump.ui.ssh

import kotlinx.coroutines.delay

/** A held ⌫ or arrow: the first press at once, the second after this... */
const val KEY_REPEAT_DELAY_MS = 400L

/** ...then about 15 a second until released. */
const val KEY_REPEAT_INTERVAL_MS = 66L

/**
 * Fires [fire] at once, again after [KEY_REPEAT_DELAY_MS], then every
 * [KEY_REPEAT_INTERVAL_MS] until the calling coroutine is cancelled (the key
 * is released, the touch is cancelled or leaves the key). [wait] is the clock.
 */
suspend fun repeatWhileHeld(fire: () -> Unit, wait: suspend (Long) -> Unit = { delay(it) }) {
    fire()
    wait(KEY_REPEAT_DELAY_MS)
    while (true) {
        fire()
        wait(KEY_REPEAT_INTERVAL_MS)
    }
}
