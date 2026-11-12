package ru.adsrv.frpjump.ssh

import android.content.ClipDescription

/** What the clipboard holds as far as Paste is concerned. */
enum class ClipKind { TEXT, IMAGE, NONE }

/**
 * The kind of a clip from its MIME types only (the content is not read, so no "pasted from" notice per check). Any text
 * wins, so a clip with text pastes as it always did; only an image and no text is offered as an image.
 */
fun clipKind(mimeTypes: List<String>): ClipKind = when {
    mimeTypes.any { it.startsWith("text/", ignoreCase = true) } -> ClipKind.TEXT
    mimeTypes.any { it.startsWith("image/", ignoreCase = true) } -> ClipKind.IMAGE
    else -> ClipKind.NONE
}

fun clipKind(description: ClipDescription?): ClipKind =
    if (description == null) ClipKind.NONE else clipKind((0 until description.mimeTypeCount).map { description.getMimeType(it) })
