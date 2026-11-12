package ru.adsrv.frpjump.ssh

import android.content.ClipDescription
import ru.adsrv.frpjump.image.ImageLimits

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

/**
 * The pictures of a clip from the content URI of each of its items (null for a text item), in the clip's order and at most
 * [ImageLimits.BATCH_MAX]: several of them are offered as one insert.
 */
fun <T : Any> clipImageItems(itemUris: List<T?>): List<T> = itemUris.filterNotNull().take(ImageLimits.BATCH_MAX)
