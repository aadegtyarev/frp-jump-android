package ru.adsrv.frpjump.enroll

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frames drawn the way the camera would see the laptop's screen, decoded as the scanner does. */
class QrDecoderTest {
    private val token = "--CdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde"

    /** What `add-token --qr` encodes: the enroll line without a name placeholder. */
    private val cliLine = "frp-jump-client enroll https://way.example.com $token"

    /** One frame: the luminance plane, its row stride and size. */
    private class Frame(val luma: ByteArray, val rowStride: Int, val width: Int, val height: Int)

    /** The code's modules, without a quiet zone (it is drawn by [frame]); level M as the CLI's segno. */
    private fun modules(text: String): BitMatrix =
        QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )

    /**
     * The code at [scale] pixels per module: dark modules [dark], light ones
     * and the 4-module quiet zone [light], all on a [surround] background
     * [margin] pixels wide (the terminal around the code). Rows are padded to
     * [padding] extra bytes, as camera planes often are.
     */
    private fun frame(
        text: String,
        dark: Int,
        light: Int,
        surround: Int,
        scale: Int = 4,
        margin: Int = 40,
        padding: Int = 0,
    ): Frame {
        val code = modules(text)
        val side = (code.width + 8) * scale
        val width = side + 2 * margin
        val height = width
        val stride = width + padding
        val luma = ByteArray(stride * height) { 0x5A }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val cx = x - margin
                val cy = y - margin
                val value = when {
                    cx !in 0 until side || cy !in 0 until side -> surround
                    else -> {
                        val mx = cx / scale - 4
                        val my = cy / scale - 4
                        val inCode = mx in 0 until code.width && my in 0 until code.height
                        if (inCode && code[mx, my]) dark else light
                    }
                }
                luma[y * stride + x] = value.toByte()
            }
        }
        return Frame(luma, stride, width, height)
    }

    private fun decode(frame: Frame) = QrDecoder().decode(frame.luma, frame.rowStride, frame.width, frame.height)

    @Test
    fun readsPaperCode() {
        assertEquals(cliLine, decode(frame(cliLine, dark = 0, light = 255, surround = 255)))
    }

    @Test
    fun readsCodeOnDarkTerminal() {
        // Light modules are glyphs in the terminal's light text colour, on its dark background.
        assertEquals(cliLine, decode(frame(cliLine, dark = 40, light = 210, surround = 40)))
    }

    @Test
    fun readsInvertedCodeOnLightTerminal() {
        // The same glyphs in dark text on a light terminal: every module and the quiet zone flip.
        assertEquals(cliLine, decode(frame(cliLine, dark = 235, light = 30, surround = 235)))
    }

    @Test
    fun readsPaddedRows() {
        assertEquals(cliLine, decode(frame(cliLine, dark = 235, light = 30, surround = 235, padding = 64)))
    }

    @Test
    fun noCodeIsNull() {
        val width = 320
        val luma = ByteArray(width * width) { i -> ((i * 31) % 256).toByte() }
        assertNull(QrDecoder().decode(luma, width, width, width))
    }

    @Test
    fun oneDecoderReadsFrameAfterFrame() {
        val decoder = QrDecoder()
        val blank = ByteArray(200 * 200) { 0x7F }
        assertNull(decoder.decode(blank, 200, 200, 200))
        val inverted = frame(cliLine, dark = 235, light = 30, surround = 235)
        assertEquals(cliLine, decoder.decode(inverted.luma, inverted.rowStride, inverted.width, inverted.height))
        val plain = frame(cliLine, dark = 0, light = 255, surround = 255)
        assertEquals(cliLine, decoder.decode(plain.luma, plain.rowStride, plain.width, plain.height))
    }

    @Test
    fun scannedCliLineIsAnEnrollLine() {
        val text = decode(frame(cliLine, dark = 235, light = 30, surround = 235))!!
        val result = EnrollLineParser.parse(text)
        assertEquals(EnrollLineResult.Ok(EnrollLine("https://way.example.com", token, null)), result)
    }

    @Test
    fun scannedOtherCodeIsNotAnEnrollLine() {
        val text = decode(frame("https://example.com/menu", dark = 0, light = 255, surround = 255))!!
        assertTrue(EnrollLineParser.parse(text) is EnrollLineResult.Error)
    }
}
