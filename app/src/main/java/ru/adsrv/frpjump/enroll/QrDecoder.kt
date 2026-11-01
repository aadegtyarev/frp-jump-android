package ru.adsrv.frpjump.enroll

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer

/**
 * Reads a QR code from a camera frame's luminance (the Y plane), with
 * ZXing alone (no Play Services).
 *
 * `frp-jump-client devices add-token --qr` draws the code with block
 * characters for its light modules: on a dark terminal it reads like
 * paper, on a light one it is inverted (light modules dark, the quiet zone
 * a dark frame). [DecodeHintType.ALSO_INVERTED] makes ZXing try the
 * flipped image when the plain one has no code, so both read.
 *
 * Not thread-safe: one instance per analysis thread.
 */
class QrDecoder {
    private val reader = MultiFormatReader().apply { setHints(QR_HINTS) }

    /**
     * The text of a QR code in the [width]×[height] luminance plane [luma],
     * whose rows start [rowStride] bytes apart, or null when there is none.
     */
    fun decode(luma: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        val source = PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false)
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: ReaderException) {
            null
        } finally {
            reader.reset()
        }
    }
}

/** QR only, try harder, also inverted: the camera and pictures read the same codes. */
internal val QR_HINTS: Map<DecodeHintType, Any> = mapOf(
    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    DecodeHintType.TRY_HARDER to true,
    DecodeHintType.ALSO_INVERTED to true,
)
