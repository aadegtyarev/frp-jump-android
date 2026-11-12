package ru.adsrv.frpjump.image

/**
 * The last check of encoded bytes, apart from the code that made them: nothing but the picture may be in them (no EXIF,
 * XMP, comments, text chunks, thumbnails, gain maps or colour profiles, and nothing after the end of the image). Returns
 * null when clean, otherwise a short name of the first thing found, or "malformed". Only structure is read, never a value.
 * It is the fail-closed check after [ImageSanitizer]. It reuses the sanitizer's allowed set and its small helpers
 * (the allowed JPEG markers, the canonical JFIF test, the entropy-data scan, the PNG chunk set, length and CRC), but walks
 * the bytes with its own loop and decides on its own; it never builds output.
 */
object ImageContainerScan {
    fun find(bytes: ByteArray, format: ImageFormat): String? = try {
        when (format) {
            ImageFormat.JPEG -> jpeg(bytes)
            ImageFormat.PNG -> png(bytes)
        }
    } catch (e: IndexOutOfBoundsException) {
        "malformed"
    }

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    private fun jpeg(b: ByteArray): String? {
        if (b.size < 4 || u8(b, 0) != 0xFF || u8(b, 1) != 0xD8) return "malformed"
        var i = 2
        while (i < b.size) {
            if (u8(b, i) != 0xFF) return "malformed"
            while (i < b.size && u8(b, i) == 0xFF) i++
            if (i >= b.size) return "malformed"
            val marker = u8(b, i++)
            if (marker == 0xD9) return if (i == b.size) null else "trailing"
            if (marker == 0xFE) return "COM"
            if (marker in 0xE1..0xEF) return "APP" + (marker - 0xE0)
            if (marker == 0x00 || marker == 0x01 || marker in 0xD0..0xD8) return "malformed"
            if (i + 2 > b.size) return "malformed"
            val length = (u8(b, i) shl 8) or u8(b, i + 1)
            if (length < 2 || i + length > b.size) return "malformed"
            if (marker == 0xE0) {
                if (!ImageSanitizer.canonicalJfif(b, i + 2, length)) return "APP0"
            } else if (!ImageSanitizer.jpegKept(marker)) {
                return "marker " + marker.toString(16)
            }
            i += length
            if (marker == 0xDA) {
                val end = ImageSanitizer.nextMarker(b, i)
                if (end < 0) return "malformed"
                i = end
            }
        }
        return "malformed"
    }

    private val PNG_SIGNATURE = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun png(b: ByteArray): String? {
        if (b.size < 8 || PNG_SIGNATURE.indices.any { u8(b, it) != PNG_SIGNATURE[it] }) return "malformed"
        var i = 8
        while (i + 12 <= b.size) {
            val length = ImageSanitizer.u32(b, i)
            val type = String(b, i + 4, 4, Charsets.ISO_8859_1)
            if (type !in ImageSanitizer.PNG_KEPT) return if (type.all { it.isLetter() }) type else "malformed"
            if (length > b.size - i - 12) return "malformed"
            if (!ImageSanitizer.crcOk(b, i + 4, length.toInt())) return "malformed"
            i += 12 + length.toInt()
            if (type == "IEND") return if (i == b.size) null else "trailing"
        }
        return "malformed"
    }
}
