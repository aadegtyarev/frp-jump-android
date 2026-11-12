package ru.adsrv.frpjump.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Gainmap
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real ImageDecoder and Bitmap encoders on generated pictures (no photos, no network): rotation applied, nothing but
 * pixels written, size and format rules. Plain AndroidJUnitRunner; needs no device service. The EXIF fixtures are checked
 * to really carry metadata first, so a pass cannot be vacuous.
 */
@RunWith(AndroidJUnit4::class)
class ImageEncoderTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** Left half red, right half blue. */
    private fun halves(width: Int, height: Int, alpha: Int = 255): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.color = Color.argb(alpha, 255, 0, 0)
        canvas.drawRect(0f, 0f, width / 2f, height.toFloat(), paint)
        paint.color = Color.argb(alpha, 0, 0, 255)
        canvas.drawRect(width / 2f, 0f, width.toFloat(), height.toFloat(), paint)
        return bitmap
    }

    private fun compress(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int = 95): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(format, quality, it) }.toByteArray()

    private fun jpegWithExif(orientation: Int, width: Int = 400, height: Int = 200): ByteArray {
        val file = File.createTempFile("exif", ".jpg", context.cacheDir)
        try {
            file.writeBytes(compress(halves(width, height), Bitmap.CompressFormat.JPEG))
            ExifInterface(file.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                setAttribute(ExifInterface.TAG_MAKE, "SyntheticMaker")
                setAttribute(ExifInterface.TAG_MODEL, "SyntheticModel")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "51/1,30/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "0/1,7/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "W")
                setAttribute(ExifInterface.TAG_DATETIME, "2026:11:12 01:30:00")
                saveAttributes()
            }
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    private fun decode(bytes: ByteArray): Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!

    private fun nearly(expected: Int, actual: Int, tolerance: Int = 60) =
        Color.red(actual) in Color.red(expected) - tolerance..Color.red(expected) + tolerance &&
            Color.blue(actual) in Color.blue(expected) - tolerance..Color.blue(expected) + tolerance

    private fun assertNoMetadata(out: EncodedImage) {
        assertNull("container check", ImageContainerScan.find(out.bytes, out.format))
        val text = String(out.bytes, Charsets.ISO_8859_1)
        // Structure is checked by the scan above; these are strings only this test's fixtures contain.
        for (needle in listOf("SyntheticMaker", "SyntheticModel", "secret-note"))
            assertFalse("found \"$needle\" in the output", text.contains(needle))
        if (out.format == ImageFormat.JPEG) {
            val file = File.createTempFile("out", ".jpg", context.cacheDir)
            try {
                file.writeBytes(out.bytes)
                val exif = ExifInterface(file.absolutePath)
                assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
                assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
                assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME))
                // ExifInterface reports a JPEG without EXIF as ORIENTATION_UNDEFINED (it fills in a default), so 0 means no tag.
                assertEquals("no orientation tag left to apply twice", ExifInterface.ORIENTATION_UNDEFINED,
                    exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED))
            } finally {
                file.delete()
            }
        }
    }

    @Test fun theFixtureReallyCarriesExif() {
        assertEquals("APP1", ImageContainerScan.find(jpegWithExif(6), ImageFormat.JPEG))
        assertTrue(String(jpegWithExif(6), Charsets.ISO_8859_1).contains("SyntheticMaker"))
    }

    @Test fun orientation6IsUprightAndMetadataIsGone() {
        val out = ImageEncoder.encode(jpegWithExif(6))
        assertEquals(ImageFormat.JPEG, out.format)
        assertNoMetadata(out)
        val bitmap = decode(out.bytes)
        // 400 x 200 turned a quarter: 200 x 400, the left (red) half now on top.
        assertEquals(200, bitmap.width)
        assertEquals(400, bitmap.height)
        assertTrue("top is red", nearly(Color.RED, bitmap.getPixel(100, 50)))
        assertTrue("bottom is blue", nearly(Color.BLUE, bitmap.getPixel(100, 350)))
    }

    @Test fun orientation8IsUprightTheOtherWay() {
        val bitmap = decode(ImageEncoder.encode(jpegWithExif(8)).bytes)
        assertEquals(200, bitmap.width)
        assertEquals(400, bitmap.height)
        assertTrue("top is blue", nearly(Color.BLUE, bitmap.getPixel(100, 50)))
        assertTrue("bottom is red", nearly(Color.RED, bitmap.getPixel(100, 350)))
    }

    @Test fun orientation1IsLeftAlone() {
        val bitmap = decode(ImageEncoder.encode(jpegWithExif(1)).bytes)
        assertEquals(400, bitmap.width)
        assertEquals(200, bitmap.height)
        assertTrue(nearly(Color.RED, bitmap.getPixel(50, 100)))
    }

    @Test fun theLongSideIsBoundedAfterTheRotation() {
        val out = ImageEncoder.encode(jpegWithExif(6, width = 4000, height = 1000))
        val bitmap = decode(out.bytes)
        assertEquals(500, bitmap.width)
        assertEquals(2000, bitmap.height)
    }

    @Test fun aSmallPictureIsNotEnlarged() {
        val bitmap = decode(ImageEncoder.encode(compress(halves(120, 80), Bitmap.CompressFormat.JPEG)).bytes)
        assertEquals(120, bitmap.width)
        assertEquals(80, bitmap.height)
    }

    @Test fun aPngStaysPngWithItsTransparencyAndLosesItsTextChunks() {
        val png = withTextChunk(compress(halves(300, 100, alpha = 128), Bitmap.CompressFormat.PNG, 100), "Comment", "secret-note")
        assertTrue("the fixture has a text chunk (wherever Skia puts its own chunks)", "tEXt" in chunkTypes(png))
        val out = ImageEncoder.encode(png)
        assertEquals(ImageFormat.PNG, out.format)
        assertNoMetadata(out)
        val bitmap = decode(out.bytes)
        assertEquals(300, bitmap.width)
        assertTrue("alpha kept", Color.alpha(bitmap.getPixel(10, 10)) < 250)
    }

    @Test fun aBigPngIsDownscaledAndStaysPng() {
        val out = ImageEncoder.encode(compress(halves(3000, 3000), Bitmap.CompressFormat.PNG, 100))
        assertEquals(ImageFormat.PNG, out.format)
        val bitmap = decode(out.bytes)
        assertEquals(2000, bitmap.width)
        assertEquals(2000, bitmap.height)
    }

    @Test fun anOpaqueWebpBecomesJpeg() {
        @Suppress("DEPRECATION")
        val webp = compress(halves(300, 100), if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP, 90)
        val out = ImageEncoder.encode(webp)
        assertEquals(ImageFormat.JPEG, out.format)
        assertNoMetadata(out)
    }

    @Test fun aWebpWithRealTransparencyBecomesPng() {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        val out = ImageEncoder.encode(compress(halves(300, 100, alpha = 100), Bitmap.CompressFormat.WEBP_LOSSLESS, 100))
        assertEquals(ImageFormat.PNG, out.format)
        assertNoMetadata(out)
    }

    @Test fun aGifBecomesItsFirstFrameAsPng() {
        val gif = Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", Base64.DEFAULT)
        val out = ImageEncoder.encode(gif)
        assertEquals(ImageFormat.PNG, out.format)
        assertNoMetadata(out)
        assertNotNull(decode(out.bytes))
    }

    @Test fun garbageIsRefusedWithAReasonAndNoDetail() {
        for (bytes in listOf(ByteArray(0), ByteArray(100) { 7 }, "not an image at all".toByteArray())) {
            try {
                ImageEncoder.encode(bytes)
                fail("expected a refusal")
            } catch (e: ImageFailureException) {
                assertEquals(ImageFailure.UNREADABLE, e.reason)
                assertEquals(e.reason.name, e.message)
            }
        }
    }

    @Test fun aTruncatedJpegIsRefused() {
        val whole = compress(halves(400, 400), Bitmap.CompressFormat.JPEG)
        try {
            ImageEncoder.encode(whole.copyOf(whole.size / 3))
            fail("expected a refusal")
        } catch (e: ImageFailureException) {
            assertEquals(ImageFailure.UNREADABLE, e.reason)
        }
    }

    @Test fun aPictureOfMoreThanTwoHundredFiftyMegapixelsIsTooLargeBeforeItIsDecoded() {
        // A valid-enough PNG whose header says 20000 x 20000 (400 MP): IHDR, a small IDAT, IEND, correct CRCs, so the
        // decoder gets as far as its header listener.
        val ihdr = ByteArrayOutputStream().apply { write(int(20000)); write(int(20000)); write(byteArrayOf(8, 6, 0, 0, 0)) }.toByteArray()
        val deflater = java.util.zip.Deflater()
        deflater.setInput(ByteArray(64))
        deflater.finish()
        val z = ByteArray(128)
        val zn = deflater.deflate(z)
        val png = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            write(chunk("IHDR", ihdr)); write(chunk("IDAT", z.copyOf(zn))); write(chunk("IEND", ByteArray(0)))
        }.toByteArray()
        try {
            ImageEncoder.encode(png)
            fail("expected a refusal")
        } catch (e: ImageFailureException) {
            assertEquals(ImageFailure.TOO_LARGE, e.reason)
        }
    }

    private fun chunkTypes(png: ByteArray): List<String> {
        val types = mutableListOf<String>()
        var i = 8
        while (i + 8 <= png.size) {
            val len = ((png[i].toInt() and 0xFF) shl 24) or ((png[i + 1].toInt() and 0xFF) shl 16) or ((png[i + 2].toInt() and 0xFF) shl 8) or (png[i + 3].toInt() and 0xFF)
            types += String(png, i + 4, 4, Charsets.ISO_8859_1)
            i += 12 + len
        }
        return types
    }

    private fun assertDecodesTo(out: EncodedImage, width: Int, height: Int) {
        val bitmap = decode(out.bytes)
        assertEquals(width, bitmap.width)
        assertEquals(height, bitmap.height)
    }

    // ---- fix pass ----

    @Test fun whatSkiaWritesIsStrippedAndTheResultStillDecodesToTheSameSize() {
        val jpeg = compress(halves(300, 200), Bitmap.CompressFormat.JPEG)
        val png = compress(halves(300, 200), Bitmap.CompressFormat.PNG, 100)
        // Whatever extra Skia writes (an ICC profile, sBIT, ...) is gone from what the app sends.
        for ((source, format) in listOf(jpeg to ImageFormat.JPEG, png to ImageFormat.PNG)) {
            val out = ImageEncoder.encode(source)
            assertEquals(format, out.format)
            assertNull(ImageContainerScan.find(out.bytes, out.format))
            assertDecodesTo(out, 300, 200)
        }
    }

    @Test fun aNonSrgbSourceBecomesCleanSrgbOutput() {
        val p3 = Bitmap.createBitmap(300, 100, Bitmap.Config.ARGB_8888, true, ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
        Canvas(p3).drawColor(Color.RED)
        val source = compress(p3, Bitmap.CompressFormat.PNG, 100)
        assertTrue("the fixture carries its colour profile", "iCCP" in chunkTypes(source))
        val out = ImageEncoder.encode(source)
        assertEquals(ImageFormat.PNG, out.format)
        assertNull(ImageContainerScan.find(out.bytes, ImageFormat.PNG))
        assertFalse("no iCCP in the output", "iCCP" in chunkTypes(out.bytes))
        val bitmap = decode(out.bytes)
        assertEquals(ColorSpace.get(ColorSpace.Named.SRGB), bitmap.colorSpace)
        assertTrue(nearly(Color.RED, bitmap.getPixel(10, 10)))
    }

    @Test fun aPngOverTheOutputLimitIsSentOnceAsAJpeg() {
        val random = java.util.Random(7)
        val noise = Bitmap.createBitmap(2000, 2000, Bitmap.Config.ARGB_8888)
        noise.setPixels(IntArray(2000 * 2000) { random.nextInt() or (0xFF shl 24) }, 0, 2000, 0, 0, 2000, 2000)
        val source = compress(noise, Bitmap.CompressFormat.PNG, 100)
        assertTrue("the fixture really is over 8 MiB as PNG: ${source.size}", source.size > ImageLimits.OUTPUT_BYTES)
        val out = ImageEncoder.encode(source)
        assertEquals(ImageFormat.JPEG, out.format)
        assertTrue(out.bytes.size <= ImageLimits.OUTPUT_BYTES)
        assertNull(ImageContainerScan.find(out.bytes, ImageFormat.JPEG))
        assertDecodesTo(out, 2000, 2000)
    }

    @Test fun theInputCapIsEnforcedThroughTheUriPath() {
        val file = File.createTempFile("big", ".bin", context.cacheDir)
        try {
            java.io.RandomAccessFile(file, "rw").use { it.setLength(ImageLimits.INPUT_BYTES + 1) }
            try {
                ImageEncoder.encode(context.contentResolver, Uri.fromFile(file))
                fail("expected a refusal")
            } catch (e: ImageFailureException) {
                assertEquals(ImageFailure.TOO_LARGE, e.reason)
            }
            java.io.RandomAccessFile(file, "rw").use { it.setLength(ImageLimits.INPUT_BYTES) }
            try {
                ImageEncoder.encode(context.contentResolver, Uri.fromFile(file))
                fail("zeros are not a picture")
            } catch (e: ImageFailureException) {
                assertEquals("exactly at the cap is read, and is not a picture", ImageFailure.UNREADABLE, e.reason)
            }
        } finally {
            file.delete()
        }
    }

    @Test fun aPictureReadFromAUriComesOutClean() {
        val file = File.createTempFile("pic", ".jpg", context.cacheDir)
        try {
            file.writeBytes(jpegWithExif(6))
            val out = ImageEncoder.encode(context.contentResolver, Uri.fromFile(file))
            assertNoMetadata(out)
            assertDecodesTo(out, 200, 400)
        } finally {
            file.delete()
        }
    }

    @Test fun aMissingUriIsUnreadable() {
        try {
            ImageEncoder.encode(context.contentResolver, Uri.parse("content://synthetic.invalid/none"))
            fail("expected a refusal")
        } catch (e: ImageFailureException) {
            assertEquals(ImageFailure.UNREADABLE, e.reason)
        }
    }

    /** Two frames of 1 x 1: red, then blue; looping; no transparency. */
    private fun twoFrameGif(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray())
        out.write(byteArrayOf(1, 0, 1, 0, 0x80.toByte(), 0, 0))
        out.write(byteArrayOf(0xFF.toByte(), 0, 0, 0, 0, 0xFF.toByte()))
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B)); out.write("NETSCAPE2.0".toByteArray()); out.write(byteArrayOf(3, 1, 0, 0, 0))
        for (code in intArrayOf(0x44, 0x4C)) {
            out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, 0, 10, 0, 0, 0))
            out.write(byteArrayOf(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0))
            out.write(byteArrayOf(2, 2, code.toByte(), 1, 0))
        }
        out.write(0x3B)
        return out.toByteArray()
    }

    @Test fun anAnimatedGifBecomesItsFirstFrameAsPng() {
        val source = twoFrameGif()
        val out = ImageEncoder.encode(source)
        assertEquals(ImageFormat.PNG, out.format)
        assertNoMetadata(out)
        val bitmap = decode(out.bytes)
        assertEquals(1, bitmap.width)
        assertTrue("the first frame is red", nearly(Color.RED, bitmap.getPixel(0, 0)))
    }

    /** The markers of a real gain map only: "hdrgm" XMP, an MPF segment, or a second SOI after the first EOI. Skia's ICC APP2 is not one. */
    private fun looksLikeUltraHdr(jpeg: ByteArray): Boolean {
        val text = String(jpeg, Charsets.ISO_8859_1)
        if (text.contains("hdrgm") || text.contains("MPF\u0000")) return true
        val eoi = text.indexOf("\u00FF\u00D9")
        return eoi >= 0 && text.indexOf("\u00FF\u00D8", eoi) >= 0
    }

    @RequiresApi(34)
    private fun ultraHdrJpeg(): ByteArray {
        val base = halves(400, 200)
        val map = Bitmap.createBitmap(100, 50, Bitmap.Config.ALPHA_8)
        map.eraseColor(Color.argb(128, 0, 0, 0))
        base.gainmap = Gainmap(map).apply { setRatioMax(4f, 4f, 4f); setDisplayRatioForFullHdr(4f) }
        return compress(base, Bitmap.CompressFormat.JPEG, 95)
    }

    @Test fun anUltraHdrJpegBecomesACleanSingleJpegWithNoGainMap() {
        assumeTrue(Build.VERSION.SDK_INT >= 34)
        val source = ultraHdrJpeg()
        // Skip, visibly, on a device whose encoder does not write Ultra HDR: a pass would say nothing.
        assumeTrue("this encoder writes Ultra HDR (a gain map's XMP, MPF, or a second picture)", looksLikeUltraHdr(source))
        val out = ImageEncoder.encode(source)
        assertEquals(ImageFormat.JPEG, out.format)
        assertNull(ImageContainerScan.find(out.bytes, ImageFormat.JPEG))
        assertFalse(String(out.bytes, Charsets.ISO_8859_1).contains("hdrgm"))
        val bitmap = decode(out.bytes)
        assertFalse("no gain map is decoded from the result", Build.VERSION.SDK_INT >= 34 && bitmap.hasGainmap())
        assertEquals(400, bitmap.width)
    }

    private fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val crc = CRC32().apply { update(type.toByteArray(Charsets.ISO_8859_1)); update(data) }.value.toInt()
        return ByteArrayOutputStream().apply { write(int(data.size)); write(type.toByteArray(Charsets.ISO_8859_1)); write(data); write(int(crc)) }.toByteArray()
    }

    /** [png] with a tEXt chunk before its IEND. */
    private fun withTextChunk(png: ByteArray, key: String, value: String): ByteArray {
        val iend = png.size - 12
        val text = chunk("tEXt", (key + "\u0000" + value).toByteArray(Charsets.ISO_8859_1))
        return png.copyOf(iend) + text + png.copyOfRange(iend, png.size)
    }
}
