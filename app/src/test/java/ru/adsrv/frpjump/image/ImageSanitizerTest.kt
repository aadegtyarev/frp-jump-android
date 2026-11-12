package ru.adsrv.frpjump.image

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ImageSanitizerTest {
    private val ff = 0xFF.toByte()

    private fun seg(marker: Int, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xFF); out.write(marker)
        out.write((body.size + 2) shr 8); out.write((body.size + 2) and 0xFF)
        out.write(body)
        return out.toByteArray()
    }

    private val jfif = seg(0xE0, byteArrayOf('J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0, 1, 1, 0, 0, 1, 0, 1, 0, 0))
    private val dqt = seg(0xDB, ByteArray(65) { 3 })
    private val sof = seg(0xC0, ByteArray(15) { 4 })
    private val dht = seg(0xC4, ByteArray(20) { 5 })
    private val dri = seg(0xDD, byteArrayOf(0, 4))
    private val sosHeader = seg(0xDA, byteArrayOf(1, 1, 0, 0, 63, 0))
    /** Entropy data with a stuffed FF00, restart markers and a fill FF FF: none is a real marker. */
    private val entropy = byteArrayOf(0x12, ff, 0x00, 0x34, ff, 0xD0.toByte(), 0x56, ff, ff, 0x00, 0x78, ff, 0xD7.toByte(), 0x01)
    private val soi = byteArrayOf(ff, 0xD8.toByte())
    private val eoi = byteArrayOf(ff, 0xD9.toByte())

    private fun jpeg(vararg parts: ByteArray) = parts.fold(ByteArray(0)) { a, b -> a + b }

    private val picture = jpeg(soi, jfif, dqt, sof, dht, dri, sosHeader, entropy, eoi)

    @Test fun aCleanJpegComesOutByteForByte() {
        assertArrayEquals(picture, ImageSanitizer.strip(picture, ImageFormat.JPEG))
    }

    @Test fun jpegExifXmpIccMpfCommentsAndEveryAppButJfifGo() {
        val noisy = jpeg(
            soi, jfif,
            seg(0xE1, "Exif\u0000\u0000GPS secret".toByteArray()),
            seg(0xE1, "http://ns.adobe.com/xap/1.0/ hdrgm".toByteArray()),
            seg(0xE2, "ICC_PROFILE\u0000".toByteArray()),
            seg(0xE2, "MPF\u0000".toByteArray()),
            seg(0xED, "Photoshop 3.0".toByteArray()),
            seg(0xEE, "Adobe".toByteArray()),
            seg(0xFE, "made by me".toByteArray()),
            dqt, sof, dht, dri, sosHeader, entropy, eoi,
        )
        val out = ImageSanitizer.strip(noisy, ImageFormat.JPEG)
        assertArrayEquals("only the picture's own bytes remain, in order", picture, out)
        assertNull(ImageContainerScan.find(out, ImageFormat.JPEG))
        assertFalse(String(out, Charsets.ISO_8859_1).contains("secret"))
    }

    @Test fun aJfifWithAThumbnailOrAnExtensionIsNotKept() {
        val thumb = seg(0xE0, ("JFIF\u0000\u0001\u0001\u0000\u0000\u0001\u0000\u0001\u0002\u0002" + "xxxxxxxx").toByteArray(Charsets.ISO_8859_1))
        val jfxx = seg(0xE0, "JFXX\u0000\u0010abc".toByteArray())
        val out = ImageSanitizer.strip(jpeg(soi, thumb, jfxx, dqt, sof, dht, sosHeader, entropy, eoi), ImageFormat.JPEG)
        assertArrayEquals(jpeg(soi, dqt, sof, dht, sosHeader, entropy, eoi), out)
    }

    @Test fun aSecondPictureAfterEoiAndTrailingBytesAreDropped() {
        val ultraHdr = jpeg(picture, jpeg(soi, seg(0xE1, "hdrgm gainmap".toByteArray()), dqt, sof, sosHeader, entropy, eoi), byteArrayOf(1, 2, 3))
        val out = ImageSanitizer.strip(ultraHdr, ImageFormat.JPEG)
        assertArrayEquals(picture, out)
    }

    @Test fun aProgressiveJpegWithSeveralScansKeepsThemAll() {
        val progressive = jpeg(soi, jfif, dqt, seg(0xC2, ByteArray(15)), dht, sosHeader, entropy, dht, sosHeader, entropy, eoi)
        assertArrayEquals(progressive, ImageSanitizer.strip(progressive, ImageFormat.JPEG))
    }

    @Test fun entropyDataIsCopiedExactlyIncludingStuffingAndRestarts() {
        val out = ImageSanitizer.strip(picture, ImageFormat.JPEG)
        val at = out.indices.first { i -> i + 1 < out.size && out[i] == 0x12.toByte() && out[i + 1] == ff }
        assertArrayEquals(entropy, out.copyOfRange(at, at + entropy.size))
    }

    @Test fun malformedOrUnknownJpegFailsClosed() {
        for (bad in listOf(
            ByteArray(0), byteArrayOf(1, 2, 3, 4), picture.copyOf(picture.size - 2), picture.copyOf(10),
            jpeg(soi, seg(0xF1, ByteArray(2)), dqt, sof, sosHeader, entropy, eoi),
            jpeg(soi, byteArrayOf(ff, 0xD3.toByte()), dqt, sof, sosHeader, entropy, eoi),
            jpeg(soi, dqt, sof, sosHeader, byteArrayOf(1, 2, 3)),
        )) {
            try {
                ImageSanitizer.strip(bad, ImageFormat.JPEG)
                fail("expected a refusal")
            } catch (e: ImageFailureException) {
                assertEquals(ImageFailure.UNREADABLE, e.reason)
            }
        }
    }

    private fun chunk(type: String, data: ByteArray = ByteArray(0), badCrc: Boolean = false): ByteArray {
        val crc = CRC32().apply { update(type.toByteArray(Charsets.ISO_8859_1)); update(data) }.value.toInt() + if (badCrc) 1 else 0
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf((data.size shr 24).toByte(), (data.size shr 16).toByte(), (data.size shr 8).toByte(), data.size.toByte()))
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(data)
        out.write(byteArrayOf((crc shr 24).toByte(), (crc shr 16).toByte(), (crc shr 8).toByte(), crc.toByte()))
        return out.toByteArray()
    }

    private val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private fun png(vararg chunks: ByteArray) = chunks.fold(signature) { a, b -> a + b }

    private val ihdr = chunk("IHDR", ByteArray(13) { 1 })
    private val idat = chunk("IDAT", ByteArray(30) { 9 })
    private val iend = chunk("IEND")

    @Test fun aCleanPngComesOutByteForByte() {
        val clean = png(ihdr, chunk("PLTE", ByteArray(6)), chunk("tRNS", ByteArray(2)), idat, idat, iend)
        assertArrayEquals(clean, ImageSanitizer.strip(clean, ImageFormat.PNG))
    }

    @Test fun everyAncillaryChunkGoesAndTheRestKeepsAValidCrc() {
        val noisy = png(
            ihdr, chunk("sBIT", ByteArray(4)), chunk("iCCP", "sRGB\u0000\u0000zzzz".toByteArray()), chunk("sRGB", ByteArray(1)),
            chunk("gAMA", ByteArray(4)), chunk("cHRM", ByteArray(32)), chunk("pHYs", ByteArray(9)), chunk("tEXt", "Comment\u0000secret".toByteArray()),
            chunk("eXIf", ByteArray(10)), chunk("tIME", ByteArray(7)), chunk("tRNS", ByteArray(2)), idat, chunk("zzZz", ByteArray(3)), iend,
        )
        val out = ImageSanitizer.strip(noisy, ImageFormat.PNG)
        assertArrayEquals(png(ihdr, chunk("tRNS", ByteArray(2)), idat, iend), out)
        assertNull(ImageContainerScan.find(out, ImageFormat.PNG))
        assertFalse(String(out, Charsets.ISO_8859_1).contains("secret"))
    }

    @Test fun bytesAfterIendAreDropped() {
        val out = ImageSanitizer.strip(png(ihdr, idat, iend) + byteArrayOf(1, 2, 3, 4), ImageFormat.PNG)
        assertArrayEquals(png(ihdr, idat, iend), out)
    }

    @Test fun anUnknownCriticalChunkABadCrcOrNoIendFailsClosed() {
        for (bad in listOf(
            png(ihdr, chunk("ZZZZ", ByteArray(2)), idat, iend),
            png(ihdr, chunk("IDAT", ByteArray(5), badCrc = true), iend),
            png(chunk("IHDR", ByteArray(13), badCrc = true), idat, iend),
            png(ihdr, idat),
            png(idat, iend),
            ByteArray(4),
            png(ihdr, idat, iend).copyOf(30),
            png(ihdr) + byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 'I'.code.toByte(), 'D'.code.toByte(), 'A'.code.toByte(), 'T'.code.toByte()),
        )) {
            try {
                ImageSanitizer.strip(bad, ImageFormat.PNG)
                fail("expected a refusal")
            } catch (e: ImageFailureException) {
                assertEquals(ImageFailure.UNREADABLE, e.reason)
            }
        }
    }

    @Test fun whatTheSanitizerKeepsTheScanAccepts() {
        assertNull(ImageContainerScan.find(ImageSanitizer.strip(picture, ImageFormat.JPEG), ImageFormat.JPEG))
        assertNull(ImageContainerScan.find(ImageSanitizer.strip(png(ihdr, idat, iend), ImageFormat.PNG), ImageFormat.PNG))
    }

    @Test fun adobeApp14AndDnlAreNotKept() {
        // APP14 goes with the other APPn; DNL is not in the allowed set and fails closed.
        val adobe = seg(0xEE, "Adobe".toByteArray())
        assertArrayEquals(picture, ImageSanitizer.strip(jpeg(soi, jfif, adobe, dqt, sof, dht, dri, sosHeader, entropy, eoi), ImageFormat.JPEG))
        try {
            ImageSanitizer.strip(jpeg(soi, jfif, dqt, sof, seg(0xDC, byteArrayOf(0, 1)), dht, sosHeader, entropy, eoi), ImageFormat.JPEG)
            fail("expected a refusal")
        } catch (e: ImageFailureException) {
            assertEquals(ImageFailure.UNREADABLE, e.reason)
        }
    }
}
