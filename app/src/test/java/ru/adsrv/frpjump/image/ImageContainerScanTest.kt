package ru.adsrv.frpjump.image

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageContainerScanTest {
    private fun jpeg(vararg segments: Pair<Int, ByteArray>, scan: ByteArray = byteArrayOf(0x12, 0xFF.toByte(), 0x00, 0x34, 0xFF.toByte(), 0xD0.toByte(), 0x56), tail: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        for ((marker, body) in segments) {
            out.write(0xFF); out.write(marker)
            out.write((body.size + 2) shr 8); out.write((body.size + 2) and 0xFF)
            out.write(body)
        }
        out.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 4, 1, 2))
        out.write(scan)
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        out.write(tail)
        return out.toByteArray()
    }

    private val jfif = 0xE0 to byteArrayOf('J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0, 1, 1, 0, 0, 1, 0, 1, 0, 0)
    private val tables = arrayOf(0xDB to ByteArray(10), 0xC0 to ByteArray(8), 0xC4 to ByteArray(6))

    @Test fun cleanJpegPasses() {
        assertNull(ImageContainerScan.find(jpeg(jfif, *tables), ImageFormat.JPEG))
    }

    @Test fun jpegWithExifXmpIccCommentOrAnyAppIsFound() {
        assertEquals("APP1", ImageContainerScan.find(jpeg(jfif, 0xE1 to "Exif\u0000\u0000GPS".toByteArray(), *tables), ImageFormat.JPEG))
        assertEquals("APP1", ImageContainerScan.find(jpeg(jfif, 0xE1 to "http://ns.adobe.com/xap/1.0/".toByteArray(), *tables), ImageFormat.JPEG))
        assertEquals("APP2", ImageContainerScan.find(jpeg(jfif, 0xE2 to "ICC_PROFILE".toByteArray(), *tables), ImageFormat.JPEG))
        assertEquals("APP2", ImageContainerScan.find(jpeg(jfif, 0xE2 to "MPF".toByteArray(), *tables), ImageFormat.JPEG))
        assertEquals("APP13", ImageContainerScan.find(jpeg(jfif, 0xED to "Photoshop 3.0".toByteArray(), *tables), ImageFormat.JPEG))
        assertEquals("APP14", ImageContainerScan.find(jpeg(jfif, 0xEE to "Adobe".toByteArray(), *tables), ImageFormat.JPEG))
        assertEquals("COM", ImageContainerScan.find(jpeg(jfif, 0xFE to "made by".toByteArray(), *tables), ImageFormat.JPEG))
    }

    @Test fun anApp0ThatIsNotACanonicalJfifIsFound() {
        // A JFXX extension (thumbnail) or a JFIF with a thumbnail is not the bare header.
        assertEquals("APP0", ImageContainerScan.find(jpeg(0xE0 to "JFXX\u0000\u0010abc".toByteArray(), *tables), ImageFormat.JPEG))
        assertEquals("APP0", ImageContainerScan.find(jpeg(0xE0 to ("JFIF\u0000" + "\u0001\u0001\u0000\u0000\u0001\u0000\u0001\u0002\u0002" + "xxxxxxxx").toByteArray(Charsets.ISO_8859_1), *tables), ImageFormat.JPEG))
    }

    @Test fun metadataAfterTheTablesIsFoundToo() {
        assertEquals("APP1", ImageContainerScan.find(jpeg(jfif, *tables, 0xE1 to ByteArray(4)), ImageFormat.JPEG))
    }

    @Test fun anythingAfterTheEndOfTheImageIsFound() {
        assertEquals("trailing", ImageContainerScan.find(jpeg(jfif, *tables, tail = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)), ImageFormat.JPEG))
        assertEquals("trailing", ImageContainerScan.find(jpeg(jfif, *tables, tail = byteArrayOf(0)), ImageFormat.JPEG))
    }

    @Test fun anUnknownMarkerIsNotTheirs() {
        assertEquals("marker f1", ImageContainerScan.find(jpeg(jfif, 0xF1 to ByteArray(3), *tables), ImageFormat.JPEG))
    }

    @Test fun stuffedBytesAndRestartsInTheScanAreNotMarkers() {
        val scan = byteArrayOf(0xFF.toByte(), 0x00, 0xFF.toByte(), 0xD3.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x01)
        assertNull(ImageContainerScan.find(jpeg(jfif, *tables, scan = scan), ImageFormat.JPEG))
    }

    @Test fun malformedJpegIsRefused() {
        assertEquals("malformed", ImageContainerScan.find(ByteArray(0), ImageFormat.JPEG))
        assertEquals("malformed", ImageContainerScan.find(byteArrayOf(1, 2, 3, 4), ImageFormat.JPEG))
        val good = jpeg(jfif, *tables)
        assertEquals("malformed", ImageContainerScan.find(good.copyOf(good.size - 2), ImageFormat.JPEG))
        assertEquals("malformed", ImageContainerScan.find(good.copyOf(12), ImageFormat.JPEG))
    }

    private fun chunk(type: String, data: ByteArray = ByteArray(0)): ByteArray {
        val crc = CRC32().apply { update(type.toByteArray(Charsets.ISO_8859_1)); update(data) }.value.toInt()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf((data.size shr 24).toByte(), (data.size shr 16).toByte(), (data.size shr 8).toByte(), data.size.toByte()))
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(data)
        out.write(byteArrayOf((crc shr 24).toByte(), (crc shr 16).toByte(), (crc shr 8).toByte(), crc.toByte()))
        return out.toByteArray()
    }

    private fun png(vararg chunks: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        for (c in chunks) out.write(c)
        return out.toByteArray()
    }

    @Test fun cleanPngPasses() {
        assertNull(ImageContainerScan.find(png(chunk("IHDR", ByteArray(13)), chunk("IDAT", ByteArray(20)), chunk("IEND")), ImageFormat.PNG))
        assertNull(ImageContainerScan.find(png(chunk("IHDR", ByteArray(13)), chunk("PLTE", ByteArray(6)), chunk("tRNS", ByteArray(2)), chunk("IDAT"), chunk("IEND")), ImageFormat.PNG))
    }

    @Test fun pngWithAnythingButThePictureIsFound() {
        for (type in listOf("tEXt", "iTXt", "zTXt", "eXIf", "iCCP", "tIME", "pHYs", "dSIG", "sBIT", "sRGB", "gAMA", "cHRM")) {
            assertEquals(type, ImageContainerScan.find(png(chunk("IHDR", ByteArray(13)), chunk(type, ByteArray(5)), chunk("IDAT"), chunk("IEND")), ImageFormat.PNG))
        }
        assertEquals("tEXt", ImageContainerScan.find(png(chunk("IHDR", ByteArray(13)), chunk("IDAT"), chunk("tEXt", ByteArray(3)), chunk("IEND")), ImageFormat.PNG))
    }

    @Test fun pngWithBytesAfterIendIsFound() {
        assertEquals("trailing", ImageContainerScan.find(png(chunk("IHDR", ByteArray(13)), chunk("IDAT"), chunk("IEND")) + byteArrayOf(1, 2), ImageFormat.PNG))
    }

    @Test fun malformedPngIsRefused() {
        assertEquals("malformed", ImageContainerScan.find(ByteArray(3), ImageFormat.PNG))
        assertEquals("malformed", ImageContainerScan.find(png(chunk("IHDR", ByteArray(13)), chunk("IDAT")), ImageFormat.PNG))
        val cut = png(chunk("IHDR", ByteArray(13)), chunk("IDAT", ByteArray(50)), chunk("IEND"))
        assertEquals("malformed", ImageContainerScan.find(cut.copyOf(30), ImageFormat.PNG))
        val hugeLength = png(chunk("IHDR", ByteArray(13))) + byteArrayOf(0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 'I'.code.toByte(), 'D'.code.toByte(), 'A'.code.toByte(), 'T'.code.toByte())
        assertEquals("malformed", ImageContainerScan.find(hugeLength, ImageFormat.PNG))
    }

    @Test fun aPngChunkWithABadCrcIsRefused() {
        val whole = png(chunk("IHDR", ByteArray(13)), chunk("IDAT", ByteArray(20)), chunk("IEND"))
        val broken = whole.copyOf()
        broken[8 + 12 + 13 + 4 - 1] = (broken[8 + 12 + 13 + 4 - 1] + 1).toByte() // last CRC byte of IHDR
        assertEquals("malformed", ImageContainerScan.find(broken, ImageFormat.PNG))
    }
}
