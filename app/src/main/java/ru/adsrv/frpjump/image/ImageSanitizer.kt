package ru.adsrv.frpjump.image

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * Keeps only the picture of an encoded image. The platform's own encoders add things (an ICC profile and sBIT in PNG, an
 * ICC profile in JPEG, a gain map and XMP in an Ultra HDR JPEG), so nothing is left to them:
 *
 * - JPEG: SOI, the canonical JFIF header, quantization/Huffman tables, restart interval, the frame header, the scans with
 *   their entropy data, and EOI. APP1..APP15 and COM go, and so does everything after EOI (a second picture).
 * - PNG: IHDR, PLTE, IDAT, tRNS and IEND, with their CRCs checked. Every other chunk goes (colour profile, gamma, text,
 *   EXIF, time), an unknown critical chunk fails, and so does everything after IEND.
 *
 * What it assumes, because it is made for the platform encoder's output: a baseline or progressive JPEG in YCbCr or gray
 * with the standard markers. APP14 (Adobe) is dropped with the other APPn, which an Adobe CMYK/YCCK picture would need;
 * Skia writes none, and such a picture would not decode the same. DNL (0xDC), arithmetic-coding extensions other than DAC,
 * and any other marker not listed fail closed.
 *
 * Only structure is read, never a value. Throws [ImageFailureException] UNREADABLE for anything it cannot walk to the end.
 * [ImageContainerScan] is the independent check of the result.
 */
object ImageSanitizer {
    fun strip(bytes: ByteArray, format: ImageFormat): ByteArray = try {
        when (format) {
            ImageFormat.JPEG -> jpeg(bytes)
            ImageFormat.PNG -> png(bytes)
        }
    } catch (e: IndexOutOfBoundsException) {
        throw ImageFailureException(ImageFailure.UNREADABLE)
    }

    private fun bad(): Nothing = throw ImageFailureException(ImageFailure.UNREADABLE)

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    /** DQT, DHT, DAC, DRI and the frame headers (SOF0..SOF15 but DHT, JPG and DAC): the picture's own tables. */
    internal fun jpegKept(marker: Int): Boolean =
        marker == 0xDB || marker == 0xDD || marker == 0xDA || marker == 0xC4 || marker == 0xCC ||
            (marker in 0xC0..0xCF && marker != 0xC8)

    /** An APP0 that is exactly a JFIF header with no thumbnail: 16 bytes with the length field, `JFIF\0`, thumbnail 0 x 0. */
    internal fun canonicalJfif(b: ByteArray, bodyStart: Int, length: Int): Boolean =
        length == 16 && String(b, bodyStart, 5, Charsets.ISO_8859_1) == "JFIF\u0000" && u8(b, bodyStart + 12) == 0 && u8(b, bodyStart + 13) == 0

    /** The index of the next real marker in entropy-coded data from [from]: not a stuffed 0xFF00, a restart or a fill byte. */
    internal fun nextMarker(b: ByteArray, from: Int): Int {
        var i = from
        while (i + 1 < b.size) {
            if (u8(b, i) == 0xFF) {
                val n = u8(b, i + 1)
                if (n != 0 && n !in 0xD0..0xD7 && n != 0xFF) return i
            }
            i++
        }
        return -1
    }

    private fun jpeg(b: ByteArray): ByteArray {
        if (b.size < 4 || u8(b, 0) != 0xFF || u8(b, 1) != 0xD8) bad()
        val out = ByteArrayOutputStream(b.size)
        out.write(0xFF); out.write(0xD8)
        var i = 2
        while (i < b.size) {
            if (u8(b, i) != 0xFF) bad()
            while (i < b.size && u8(b, i) == 0xFF) i++
            if (i >= b.size) bad()
            val marker = u8(b, i++)
            if (marker == 0xD9) {
                out.write(0xFF); out.write(0xD9)
                return out.toByteArray()
            }
            // TEM, RSTn and a second SOI do not stand alone between segments.
            if (marker == 0x00 || marker == 0x01 || marker in 0xD0..0xD8) bad()
            if (i + 2 > b.size) bad()
            val length = (u8(b, i) shl 8) or u8(b, i + 1)
            if (length < 2 || i + length > b.size) bad()
            val keep = when {
                marker == 0xE0 -> canonicalJfif(b, i + 2, length)
                marker in 0xE1..0xEF || marker == 0xFE -> false
                jpegKept(marker) -> true
                else -> bad()
            }
            if (keep) {
                out.write(0xFF); out.write(marker)
                out.write(b, i, length)
            }
            i += length
            if (marker == 0xDA) {
                val end = nextMarker(b, i)
                if (end < 0) bad()
                out.write(b, i, end - i)
                i = end
            }
        }
        bad()
    }

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    internal val PNG_KEPT = setOf("IHDR", "PLTE", "IDAT", "IEND", "tRNS")

    internal fun u32(b: ByteArray, i: Int): Long =
        (u8(b, i).toLong() shl 24) or (u8(b, i + 1).toLong() shl 16) or (u8(b, i + 2).toLong() shl 8) or u8(b, i + 3).toLong()

    internal fun crcOk(b: ByteArray, typeAt: Int, length: Int): Boolean {
        val crc = CRC32().apply { update(b, typeAt, 4 + length) }.value
        return crc == u32(b, typeAt + 4 + length)
    }

    private fun png(b: ByteArray): ByteArray {
        if (b.size < 8 || !PNG_SIGNATURE.indices.all { b[it] == PNG_SIGNATURE[it] }) bad()
        val out = ByteArrayOutputStream(b.size)
        out.write(PNG_SIGNATURE)
        var i = 8
        var first = true
        while (i + 12 <= b.size) {
            val length = u32(b, i)
            if (length > b.size - i - 12) bad()
            val type = String(b, i + 4, 4, Charsets.ISO_8859_1)
            if (!type.all { it in 'A'..'Z' || it in 'a'..'z' }) bad()
            if (first && type != "IHDR") bad()
            first = false
            val total = 12 + length.toInt()
            when {
                type in PNG_KEPT -> {
                    if (!crcOk(b, i + 4, length.toInt())) bad()
                    out.write(b, i, total)
                    if (type == "IEND") return out.toByteArray()
                }
                // Ancillary (lower-case first letter): dropped whatever it is.
                type[0] in 'a'..'z' -> Unit
                // An unknown critical chunk: not something to guess about.
                else -> bad()
            }
            i += total
        }
        bad()
    }
}
