package ru.adsrv.frpjump.ui.ssh

/**
 * What to send when a keyboard's composing text changes from [sent] (already
 * on the terminal) to [next]: [erase] backspaces, then [append].
 */
data class ComposingDelta(val erase: Int, val append: String)

fun composingDelta(sent: String, next: String): ComposingDelta {
    var common = sent.commonPrefixWith(next).length
    // Never split a surrogate pair: the shell erases whole characters.
    if (common > 0 && Character.isHighSurrogate(sent[common - 1])) common--
    return ComposingDelta(sent.codePointCount(common, sent.length), next.substring(common))
}
