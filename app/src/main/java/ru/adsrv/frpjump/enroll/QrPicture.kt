package ru.adsrv.frpjump.enroll

import com.google.zxing.Binarizer
import com.google.zxing.BinaryBitmap
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer

/**
 * Reads a QR code from a picture (a screenshot, or the code sent as an
 * image to this same phone), given its ARGB pixels; the caller loads and
 * scales the picture (at most [MAX_SIDE] pixels a side, [scaledSize]).
 *
 * The same hints as the camera ([QR_HINTS]: inverted codes read too; a
 * code turned any way reads, the finder patterns give its orientation),
 * first with ZXing's local-threshold binarizer, then with its global one,
 * which does better on clean, evenly lit pictures with large modules.
 * Transparent pixels count as white, as an image viewer shows them.
 */
object QrPicture {
    /** The longest side a picture is decoded at: plenty for a code that fills a fair part of it. */
    const val MAX_SIDE = 2000

    /** The text of a QR code in the [width]×[height] picture [argb] (row by row), or null when there is none. */
    fun decode(argb: IntArray, width: Int, height: Int): String? {
        require(width > 0 && height > 0 && argb.size >= width * height) { "bad picture size" }
        val source = PlanarYUVLuminanceSource(luminance(argb, width * height), width, height, 0, 0, width, height, false)
        val reader = MultiFormatReader().apply { setHints(QR_HINTS) }
        val binarizers: List<(LuminanceSource) -> Binarizer> = listOf(::HybridBinarizer, ::GlobalHistogramBinarizer)
        for (binarizer in binarizers) {
            try {
                return reader.decodeWithState(BinaryBitmap(binarizer(source))).text
            } catch (_: ReaderException) {
                // Next binarizer.
            } finally {
                reader.reset()
            }
        }
        return null
    }

    /**
     * The size to decode a [width]×[height] picture at: as it is when its
     * longest side is at most [maxSide], else scaled down to that, keeping
     * the proportions.
     */
    fun scaledSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= maxSide) return width to height
        val scale = maxSide.toDouble() / longest
        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }

    /** Each pixel's luminance, laid over white by its alpha. */
    private fun luminance(argb: IntArray, count: Int): ByteArray = ByteArray(count) { i ->
        val p = argb[i]
        val a = p ushr 24
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        val y = (r * 299 + g * 587 + b * 114) / 1000
        ((y * a + 255 * (255 - a)) / 255).toByte()
    }
}
