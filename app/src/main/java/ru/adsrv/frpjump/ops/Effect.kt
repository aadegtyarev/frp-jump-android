package ru.adsrv.frpjump.ops

/** What an operation may do. Declared only for now; nothing decides on it yet. */
enum class Effect {
    READ_LOCAL, READ_REMOTE, CHANGE_LOCAL, CHANGE_REMOTE;

    val changes: Boolean get() = this == CHANGE_LOCAL || this == CHANGE_REMOTE
}
