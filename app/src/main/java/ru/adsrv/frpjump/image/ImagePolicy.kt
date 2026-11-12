package ru.adsrv.frpjump.image

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Random

/** What an image is sent as. Nothing is ever uploaded in another format. */
enum class ImageFormat(val extension: String) { JPEG("jpg"), PNG("png") }

/** Why inserting an image failed; each has one short message on the screen. */
enum class ImageFailure {
    /** Not a picture the phone can open (ImageDecoder calls an unknown format malformed, so the two are not told apart). */
    UNREADABLE,
    TOO_LARGE,

    /** The tab's connection is gone or the tab is no longer the one asked. */
    CONNECTION_LOST,

    /** The device said permission denied, and only then. */
    PERMISSION_DENIED,

    /** The device said it has no space (or quota), and only then. */
    NO_SPACE,

    /** No SFTP channel could be opened and started on the connection. */
    NOT_AVAILABLE,

    /** The device did not save the image for another reason (a generic SFTP failure, names that kept clashing). */
    SAVE_FAILED,
    PATH_UNSAFE,
    FOLDER_UNSAFE,
    INSERT_REFUSED,
}

/** A failure whose [reason] is already known; never carries a path, a URI or image bytes in its message. */
class ImageFailureException(val reason: ImageFailure, cause: Throwable? = null) : Exception(reason.name, cause)

/** An image ready to upload: the encoded bytes and their [format]. */
class EncodedImage(val bytes: ByteArray, val format: ImageFormat)

/** Limits of the image flow (docs/contract.md, SSH console; docs/ssh-persist-contract.md, remote image files). */
object ImageLimits {
    const val INPUT_BYTES = 40L * 1024 * 1024
    const val INPUT_PIXELS = 250_000_000L
    const val OUTPUT_BYTES = 8 * 1024 * 1024
    const val LONG_SIDE = 2000
    const val JPEG_QUALITY = 85
    const val NAME_TRIES = 4

    /** The most images one insert takes; the picker and the clipboard offer are cut to it. */
    const val BATCH_MAX = 10
    const val CLEANUP_AGE_SECONDS = 60L * 60
    const val CLEANUP_ENTRIES = 500
}

/** The size to decode to: the long side at most [maxLong], aspect kept, never larger than the source. */
fun targetSize(width: Int, height: Int, maxLong: Int = ImageLimits.LONG_SIDE): Pair<Int, Int> {
    val long = maxOf(width, height)
    if (long <= maxLong || width <= 0 || height <= 0) return width to height
    val scale = maxLong.toDouble() / long
    return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
}

/** Whether a source of [width] x [height] pixels is within the pixel limit (decoding is downsampled, so 200 MP phone photos pass). */
fun pixelsAllowed(width: Int, height: Int): Boolean = width > 0 && height > 0 && width.toLong() * height <= ImageLimits.INPUT_PIXELS

/**
 * The format for a source of [mime]: PNG stays PNG, a GIF (first frame) becomes PNG, anything else is JPEG unless it
 * has real transparency ([transparent]), then PNG.
 */
fun chooseFormat(mime: String?, transparent: Boolean): ImageFormat = when {
    mime.equals("image/png", ignoreCase = true) || mime.equals("image/gif", ignoreCase = true) -> ImageFormat.PNG
    transparent -> ImageFormat.PNG
    else -> ImageFormat.JPEG
}

/** Whether any of the first [count] ARGB [pixels] is not fully opaque. */
fun hasTransparentPixel(pixels: IntArray, count: Int = pixels.size): Boolean {
    for (i in 0 until count) if ((pixels[i] ushr 24) != 0xFF) return true
    return false
}

private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
private const val SUFFIX_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

/** The app's own image name: `img-YYYYMMDD-HHMM-xxxx.jpg`, no spaces, nothing to quote. */
fun imageName(at: LocalDateTime, suffix: String, format: ImageFormat): String = "img-${STAMP.format(at)}-$suffix.${format.extension}"

/** Four random characters of `[a-z0-9]`; [random] should be a SecureRandom. */
fun randomSuffix(random: Random): String = buildString { repeat(4) { append(SUFFIX_ALPHABET[random.nextInt(SUFFIX_ALPHABET.length)]) } }

/** Exactly the names [imageName] makes: the only files the cleanup may delete. */
val OWN_IMAGE_NAME = Regex("^img-\\d{8}-\\d{4}-[a-z0-9]{4}\\.(jpg|png)$")

private val SAFE_PATH = Regex("^/[A-Za-z0-9._/-]+$")

/** Whether an absolute remote path needs no quoting when typed into a shell. */
fun isSafeRemotePath(path: String): Boolean = SAFE_PATH.matches(path) && path.split('/').none { it == ".." || it == "." }

/**
 * Whether what `lstat` found at one of the app's own folders is fine to use: a real folder (a link is `directory` false)
 * owned by [owner], the login user's id from the home folder. An unknown owner is not proof.
 */
fun isOwnFolder(directory: Boolean, uid: Long?, owner: Long): Boolean = directory && uid != null && uid == owner

/** What the device lists for a name in the images folder; [modified] 0 means the device gave no time. */
data class ImageEntry(val name: String, val regularFile: Boolean, val modified: Long)

/**
 * The names to delete from the images folder: the app's own names, regular files only (a link or anything else is never
 * touched, nor a file with no known time), more than [ageSeconds] older than [reference] (the device's own mtime of the file just written, so the
 * phone's clock does not matter), never [keep], at most [limit] entries looked at.
 */
fun expiredImages(
    entries: List<ImageEntry>,
    reference: Long,
    keep: String,
    ageSeconds: Long = ImageLimits.CLEANUP_AGE_SECONDS,
    limit: Int = ImageLimits.CLEANUP_ENTRIES,
): List<String> = entries.asSequence().take(limit)
    .filter { it.name != keep && it.regularFile && it.modified > 0 && OWN_IMAGE_NAME.matches(it.name) && reference - it.modified > ageSeconds }
    .map { it.name }.toList()
