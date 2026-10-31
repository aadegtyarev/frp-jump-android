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

/** Pictures of the enroll QR, as a screenshot or an image sent to the phone, decoded as "From gallery" does. */
class QrPictureTest {
    private val token = "--CdEfGhIjKlMnOpQrStUvWxYz0123456789_-abcde"
    private val cliLine = "frp-jump-client enroll https://way.example.com $token"

    /** A picture: ARGB pixels row by row, and its size. */
    private class Picture(val argb: IntArray, val width: Int, val height: Int)

    private fun modules(text: String): BitMatrix =
        QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )

    /**
     * A [width]×[height] picture of [surround] with the code at [scale]
     * pixels a module at ([left], [top]): [dark] modules, [light] ones and a
     * 4-module quiet zone.
     */
    private fun picture(
        text: String,
        dark: Int = BLACK,
        light: Int = WHITE,
        surround: Int = WHITE,
        scale: Int = 6,
        width: Int = 600,
        height: Int = 600,
        left: Int = 60,
        top: Int = 60,
    ): Picture {
        val code = modules(text)
        val side = (code.width + 8) * scale
        val argb = IntArray(width * height) { i ->
            val cx = i % width - left
            val cy = i / width - top
            if (cx !in 0 until side || cy !in 0 until side) {
                surround
            } else {
                val mx = cx / scale - 4
                val my = cy / scale - 4
                if (mx in 0 until code.width && my in 0 until code.height && code[mx, my]) dark else light
            }
        }
        return Picture(argb, width, height)
    }

    /** [p] turned a quarter clockwise, as a photo saved sideways. */
    private fun turned(p: Picture): Picture {
        val w = p.height
        val h = p.width
        val argb = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            p.argb[(p.height - 1 - x) * p.width + y]
        }
        return Picture(argb, w, h)
    }

    private fun decode(p: Picture) = QrPicture.decode(p.argb, p.width, p.height)

    @Test
    fun readsPlainCode() {
        assertEquals(cliLine, decode(picture(cliLine)))
    }

    @Test
    fun readsInvertedCode() {
        // A screenshot of the code on a light terminal: light modules dark, the quiet zone a dark frame.
        assertEquals(cliLine, decode(picture(cliLine, dark = WHITE, light = BLACK, surround = WHITE)))
    }

    @Test
    fun readsTurnedCode() {
        assertEquals(cliLine, decode(turned(picture(cliLine, width = 700, height = 500))))
    }

    @Test
    fun readsCodeInTallScreenshot() {
        // A chat screenshot: a small code in a bubble on a grey background, phone-sized and already scaled down.
        val (width, height) = QrPicture.scaledSize(1080, 2400)
        val p = picture(cliLine, surround = GREY, scale = 4, width = width, height = height, left = 150, top = 700)
        assertEquals(cliLine, decode(p))
    }

    @Test
    fun readsColouredCode() {
        assertEquals(cliLine, decode(picture(cliLine, dark = 0xFF1A2A80.toInt(), light = 0xFFF0E68C.toInt(), surround = 0xFFF0E68C.toInt())))
    }

    @Test
    fun transparentBackgroundCountsAsWhite() {
        // A PNG with only the dark modules drawn: transparent black around them would read as all dark.
        assertEquals(cliLine, decode(picture(cliLine, light = TRANSPARENT, surround = TRANSPARENT)))
    }

    @Test
    fun pictureWithoutCodeIsNull() {
        val width = 400
        val argb = IntArray(width * width) { i -> 0xFF000000.toInt() or ((i * 2654435761L).toInt() and 0xFFFFFF) }
        assertNull(QrPicture.decode(argb, width, width))
        assertNull(QrPicture.decode(IntArray(width * width) { GREY }, width, width))
    }

    @Test
    fun readEnrollLineParses() {
        val text = decode(picture(cliLine, dark = WHITE, light = BLACK))!!
        assertEquals(EnrollLineResult.Ok(EnrollLine("https://way.example.com", token, null)), EnrollLineParser.parse(text))
    }

    @Test
    fun readOtherCodeIsNotAnEnrollLine() {
        val text = decode(picture("https://example.com/menu"))!!
        assertTrue(EnrollLineParser.parse(text) is EnrollLineResult.Error)
    }

    @Test
    fun scaledSizeKeepsSmallPictures() {
        assertEquals(1080 to 2000, QrPicture.scaledSize(1080, 2000))
        assertEquals(300 to 200, QrPicture.scaledSize(300, 200))
    }

    @Test
    fun scaledSizeShrinksTheLongestSide() {
        assertEquals(900 to 2000, QrPicture.scaledSize(1080, 2400))
        assertEquals(2000 to 1500, QrPicture.scaledSize(4000, 3000))
        assertEquals(2000 to 1, QrPicture.scaledSize(10000, 2))
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val GREY = 0xFF8A8A8A.toInt()
        const val TRANSPARENT = 0x00000000
    }
}
