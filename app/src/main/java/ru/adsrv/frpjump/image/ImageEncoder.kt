package ru.adsrv.frpjump.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/**
 * A picture re-encoded for the device: decoded (the EXIF rotation applied by the decoder, any gain map dropped), at most
 * [ImageLimits.LONG_SIDE] px on the long side, in sRGB, written again as JPEG or PNG, then stripped of everything that is
 * not the picture ([ImageSanitizer]: the platform's encoders write a colour profile even for sRGB) and checked again
 * ([ImageContainerScan]). A GIF is its first frame as PNG. Blocking; throws [ImageFailureException] with nothing about the
 * picture or where it came from; running out of memory is "too large".
 */
object ImageEncoder {
    /**
     * Reads [uri] (at most [ImageLimits.INPUT_BYTES]) and encodes it. [track] is given what to close to make a blocked
     * read return, so Cancel works while the picture is being read.
     */
    fun encode(resolver: ContentResolver, uri: Uri, track: (Closeable) -> Unit = {}): EncodedImage {
        val bytes = try {
            read(resolver, uri, track)
        } catch (e: ImageFailureException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw ImageFailureException(ImageFailure.TOO_LARGE)
        } catch (e: Exception) {
            throw ImageFailureException(ImageFailure.UNREADABLE)
        }
        return encode(bytes)
    }

    private fun read(resolver: ContentResolver, uri: Uri, track: (Closeable) -> Unit): ByteArray {
        val descriptor = try {
            resolver.openAssetFileDescriptor(uri, "r")
        } catch (e: Exception) {
            null
        }
        if (descriptor != null) {
            track(descriptor)
            descriptor.use {
                val known = descriptor.length
                if (known > ImageLimits.INPUT_BYTES) throw ImageFailureException(ImageFailure.TOO_LARGE)
                val stream = descriptor.createInputStream()
                track(stream)
                return stream.use { if (known >= 0) readKnown(it, known.toInt()) else readAtMost(it, ImageLimits.INPUT_BYTES) }
            }
        }
        val stream = resolver.openInputStream(uri) ?: throw ImageFailureException(ImageFailure.UNREADABLE)
        track(stream)
        return stream.use { readAtMost(it, ImageLimits.INPUT_BYTES) }
    }

    /** [length] bytes into one array allocated once (no doubling). A source that ends early gives what it had; one that has more than it said is refused. */
    private fun readKnown(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var n = 0
        while (n < length) {
            val r = input.read(bytes, n, length - n)
            if (r < 0) return bytes.copyOf(n)
            n += r
        }
        if (input.read() >= 0) throw ImageFailureException(ImageFailure.UNREADABLE)
        return bytes
    }

    /** A source of unknown length: chunks, joined once, so the peak is twice the size and not more. */
    private fun readAtMost(input: InputStream, limit: Long): ByteArray {
        val chunks = ArrayList<ByteArray>()
        var total = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) throw ImageFailureException(ImageFailure.TOO_LARGE)
            chunks += buffer.copyOf(n)
        }
        val all = ByteArray(total.toInt())
        var at = 0
        for (c in chunks) { c.copyInto(all, at); at += c.size }
        return all
    }

    fun encode(source: ByteArray): EncodedImage = try {
        encodeBytes(source)
    } catch (e: OutOfMemoryError) {
        throw ImageFailureException(ImageFailure.TOO_LARGE)
    }

    private fun encodeBytes(source: ByteArray): EncodedImage {
        var mime: String? = null
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(source))) { decoder, info, _ ->
                mime = info.mimeType
                if (!pixelsAllowed(info.size.width, info.size.height)) throw ImageFailureException(ImageFailure.TOO_LARGE)
                val (w, h) = targetSize(info.size.width, info.size.height)
                if (w != info.size.width || h != info.size.height) decoder.setTargetSize(w, h)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
            }
        } catch (e: ImageFailureException) {
            throw e
        } catch (e: IOException) {
            // ImageDecoder calls an unknown format malformed too, so "unsupported" would not be true.
            throw ImageFailureException(ImageFailure.UNREADABLE)
        }
        try {
            // An Ultra HDR photo decodes with its gain map, and compress would write it back (XMP, MPF, a second JPEG).
            if (Build.VERSION.SDK_INT >= 34 && bitmap.hasGainmap()) runCatching { bitmap.gainmap = null }
            val format = chooseFormat(mime, bitmap.hasAlpha() && transparent(bitmap))
            var chosen = format
            var out = finish(bitmap, format)
            if (out.size > ImageLimits.OUTPUT_BYTES && format == ImageFormat.PNG) {
                // Once: a PNG too big for the device is sent as a JPEG, flattened on white.
                val flat = flatten(bitmap)
                try { out = finish(flat, ImageFormat.JPEG) } finally { flat.recycle() }
                chosen = ImageFormat.JPEG
            }
            if (out.size > ImageLimits.OUTPUT_BYTES) throw ImageFailureException(ImageFailure.TOO_LARGE)
            return EncodedImage(out, chosen)
        } finally {
            bitmap.recycle()
        }
    }

    /** Compressed, stripped to the picture, and checked: a result that is not clean is not returned. */
    private fun finish(bitmap: Bitmap, format: ImageFormat): ByteArray {
        val clean = ImageSanitizer.strip(compress(bitmap, format), format)
        if (ImageContainerScan.find(clean, format) != null) throw ImageFailureException(ImageFailure.UNREADABLE)
        return clean
    }

    private fun transparent(bitmap: Bitmap): Boolean {
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            if (hasTransparentPixel(row)) return true
        }
        return false
    }

    private fun flatten(bitmap: Bitmap): Bitmap {
        val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
        return flat
    }

    private fun compress(bitmap: Bitmap, format: ImageFormat): ByteArray {
        val out = ByteArrayOutputStream()
        val ok = when (format) {
            ImageFormat.JPEG -> bitmap.compress(Bitmap.CompressFormat.JPEG, ImageLimits.JPEG_QUALITY, out)
            ImageFormat.PNG -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        if (!ok) throw ImageFailureException(ImageFailure.UNREADABLE)
        return out.toByteArray()
    }
}
