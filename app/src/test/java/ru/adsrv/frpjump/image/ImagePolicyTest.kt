package ru.adsrv.frpjump.image

import java.time.LocalDateTime
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePolicyTest {
    @Test fun nameHasStampSuffixAndExtension() {
        val at = LocalDateTime.of(2026, 11, 12, 1, 30, 59)
        assertEquals("img-20261112-0130-ab12.jpg", imageName(at, "ab12", ImageFormat.JPEG))
        assertEquals("img-20261112-0130-ab12.png", imageName(at, "ab12", ImageFormat.PNG))
        assertEquals("img-20261231-2359-zz09.jpg", imageName(LocalDateTime.of(2026, 12, 31, 23, 59), "zz09", ImageFormat.JPEG))
    }

    @Test fun suffixIsFourOfLowercaseAndDigits() {
        val random = Random(1)
        repeat(200) { assertTrue(Regex("[a-z0-9]{4}").matches(randomSuffix(random))) }
    }

    @Test fun everyNameTheAppMakesIsItsOwn() {
        val at = LocalDateTime.of(2026, 1, 2, 3, 4)
        val random = Random(7)
        for (format in ImageFormat.entries) repeat(50) { assertTrue(OWN_IMAGE_NAME.matches(imageName(at, randomSuffix(random), format))) }
    }

    @Test fun otherNamesAreNotOwn() {
        for (name in listOf(
            "img-20261112-0130-ab12.gif", "img-20261112-0130-ab12.jpeg", "img-20261112-0130-AB12.jpg", "img-20261112-0130-ab1.jpg",
            "img-20261112-0130-ab123.jpg", "img-2026112-0130-ab12.jpg", "x-img-20261112-0130-ab12.jpg", "img-20261112-0130-ab12.jpg.part",
            "img-20261112-0130-ab12.jpg\n", ".img-20261112-0130-ab12.jpg", "img-20261112-0130-ab12.JPG", "notes.txt", "..",
        )) assertFalse(name, OWN_IMAGE_NAME.matches(name))
    }

    @Test fun safePathsNeedNoQuoting() {
        assertTrue(isSafeRemotePath("/home/user/.cache/frp-jump/images"))
        assertTrue(isSafeRemotePath("/root/.cache/frp-jump/images"))
        for (bad in listOf("/home/my user/x", "/home/o'neil/x", "/home/a\"b", "~/x", "home/x", "/home/ж/x", "/home/a\$b", "/home/a;b", "/home/../x", "/home/./x", "/home/a\\b", "/home/a b", "", "/home/a\nb"))
            assertFalse(bad, isSafeRemotePath(bad))
    }

    @Test fun anOwnFolderIsARealFolderOfTheLoginUser() {
        assertTrue(isOwnFolder(directory = true, uid = 1000, owner = 1000))
        assertFalse("a link or file", isOwnFolder(directory = false, uid = 1000, owner = 1000))
        assertFalse("another user's folder", isOwnFolder(directory = true, uid = 0, owner = 1000))
        assertFalse("an unknown owner proves nothing", isOwnFolder(directory = true, uid = null, owner = 1000))
        assertTrue(isOwnFolder(directory = true, uid = 4294967294L, owner = 4294967294L))
    }

    @Test fun targetSizeBoundsTheLongSide() {
        assertEquals(2000 to 1500, targetSize(4000, 3000))
        assertEquals(1500 to 2000, targetSize(3000, 4000))
        assertEquals(2000 to 2000, targetSize(2000, 2000))
        assertEquals(1999 to 10, targetSize(1999, 10))
        assertEquals(1 to 1, targetSize(1, 1))
        assertEquals(2000 to 1, targetSize(40000, 20))
        assertEquals(1000 to 2000, targetSize(1000, 2000))
    }

    @Test fun pixelsAreLimitedToTwoHundredFiftyMegapixels() {
        // 108 and 200 MP phone photos pass: decoding is downsampled.
        assertTrue(pixelsAllowed(12000, 9000))
        assertTrue(pixelsAllowed(16320, 12240))
        assertTrue(pixelsAllowed(15000, 16666))
        assertFalse(pixelsAllowed(15000, 16667))
        assertFalse(pixelsAllowed(0, 5))
        assertTrue(pixelsAllowed(100000, 1000))
        // Does not overflow Int.
        assertFalse(pixelsAllowed(60000, 60000))
    }

    @Test fun formatsFollowTheSource() {
        assertEquals(ImageFormat.PNG, chooseFormat("image/png", false))
        assertEquals(ImageFormat.PNG, chooseFormat("image/png", true))
        assertEquals(ImageFormat.PNG, chooseFormat("image/gif", false))
        assertEquals(ImageFormat.JPEG, chooseFormat("image/jpeg", false))
        assertEquals(ImageFormat.JPEG, chooseFormat("image/heic", false))
        assertEquals(ImageFormat.JPEG, chooseFormat("image/webp", false))
        assertEquals(ImageFormat.PNG, chooseFormat("image/webp", true))
        assertEquals(ImageFormat.JPEG, chooseFormat(null, false))
    }

    @Test fun transparencyIsAnyNonOpaquePixel() {
        assertFalse(hasTransparentPixel(intArrayOf(0xFF112233.toInt(), 0xFFFFFFFF.toInt())))
        assertTrue(hasTransparentPixel(intArrayOf(0xFF112233.toInt(), 0xFE112233.toInt())))
        assertTrue(hasTransparentPixel(intArrayOf(0x00000000)))
        assertFalse(hasTransparentPixel(intArrayOf(0x00000000), count = 0))
    }

    private fun entry(name: String, modified: Long, file: Boolean = true) = ImageEntry(name, file, modified)

    private val keep = "img-20261112-0130-new0.jpg"

    @Test fun cleanupAgeBoundaryIsMoreThanSixtyMinutes() {
        val ref = 100_000L
        val entries = listOf(
            entry("img-20261112-0000-a001.jpg", ref - 3599),
            entry("img-20261112-0000-a002.jpg", ref - 3600),
            entry("img-20261112-0000-a003.jpg", ref - 3601),
            entry("img-20261112-0000-a004.png", ref - 86_400),
        )
        assertEquals(listOf("img-20261112-0000-a003.jpg", "img-20261112-0000-a004.png"), expiredImages(entries, ref, keep))
    }

    @Test fun cleanupNeverTouchesLinksFoldersForeignNamesOrTheNewFile() {
        val ref = 100_000L
        val old = ref - 10_000
        val entries = listOf(
            entry("img-20261112-0000-a001.jpg", old, file = false),
            entry("notes.txt", old),
            entry("img-20261112-0000-a002.gif", old),
            entry("img-20261112-0000-a003.jpg.part", old),
            entry(keep, old),
            entry("img-20261112-0000-a004.jpg", old),
        )
        assertEquals(listOf("img-20261112-0000-a004.jpg"), expiredImages(entries, ref, keep))
    }

    @Test fun aFileWithNoKnownTimeIsKeptAndNoReferenceMeansNothingExpires() {
        val entries = listOf(entry("img-20260101-0000-none.jpg", 0), entry("img-20260101-0000-old1.jpg", 10))
        assertEquals(listOf("img-20260101-0000-old1.jpg"), expiredImages(entries, 100_000, keep))
        assertEquals("a file without a time is never expired", emptyList<String>(), expiredImages(listOf(entry("img-20260101-0000-none.jpg", 0)), 100_000, keep))
        assertEquals("the new file has no time: nothing is judged against it", emptyList<String>(), expiredImages(entries, 0, keep))
    }

    @Test fun cleanupLooksAtMostAtTheEntryLimit() {
        val ref = 100_000L
        val own = (0 until 600).map { ImageEntry("img-20261112-0000-" + it.toString(36).padStart(4, '0') + ".jpg", true, ref - 10_000) }
        assertEquals(ImageLimits.CLEANUP_ENTRIES, expiredImages(own, ref, keep).size)
    }
}
