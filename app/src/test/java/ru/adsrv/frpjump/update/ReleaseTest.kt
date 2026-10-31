package ru.adsrv.frpjump.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseTest {
    private fun asset(name: String, size: Long = 100, url: String = "${DOWNLOAD_PREFIX}v0.2.1/$name") =
        """{"name":"$name","size":$size,"browser_download_url":"$url","content_type":"x"}"""

    private fun release(tag: String = "v0.2.1", draft: Boolean = false, body: String? = "\"Fixes\\n- one\"", vararg assets: String) =
        """{"tag_name":"$tag","name":"frp-jump-android","draft":$draft,"prerelease":false,"body":$body,
           "html_url":"https://github.com/o/r/releases/tag/$tag","assets":[${assets.joinToString(",")}],"author":{"login":"x"}}"""

    @Test
    fun `a release with its apk and checksum`() {
        val r = parseRelease(release(assets = arrayOf(asset("frp-jump-android-0.2.1.apk", 21_000_000), asset("frp-jump-android-0.2.1.apk.sha256", 90))))!!
        assertEquals("0.2.1", r.version)
        assertEquals("Fixes\n- one", r.notes)
        assertEquals("frp-jump-android-0.2.1.apk", r.apkName)
        assertEquals(21_000_000L, r.apkSize)
        assertEquals("https://github.com/aadegtyarev/frp-jump-android/releases/download/v0.2.1/frp-jump-android-0.2.1.apk", r.apkUrl)
        assertEquals("https://github.com/aadegtyarev/frp-jump-android/releases/download/v0.2.1/frp-jump-android-0.2.1.apk.sha256", r.checksumUrl)
        assertEquals("https://github.com/o/r/releases/tag/v0.2.1", r.pageUrl)
    }

    @Test
    fun `the apk is the one named as the script names it, its checksum the one next to it`() {
        val r = parseRelease(
            release(
                assets = arrayOf(
                    asset("notes.txt"),
                    asset("other.apk"),
                    asset("other.apk.sha256"),
                    asset("frp-jump-android-0.2.1.APK.zip"),
                    asset("frp-jump-android-0.2.1.apk"),
                ),
            ),
        )!!
        assertEquals("frp-jump-android-0.2.1.apk", r.apkName)
        // other.apk's checksum is not this APK's.
        assertNull(r.checksumUrl)
        assertEquals("other.apk", parseRelease(release(assets = arrayOf(asset("x.txt"), asset("other.apk"))))!!.apkName)
    }

    @Test
    fun `no apk, a draft, a tag that is not a version or not a release at all`() {
        assertNull(parseRelease(release(assets = arrayOf(asset("frp-jump-android-0.2.1.apk.sha256")))))
        assertNull(parseRelease(release(draft = true, assets = arrayOf(asset("frp-jump-android-0.2.1.apk")))))
        assertNull(parseRelease(release(tag = "nightly", assets = arrayOf(asset("frp-jump-android-0.2.1.apk")))))
        assertNull(parseRelease("""{"message":"Not Found","documentation_url":"https://docs.github.com"}"""))
        assertNull(parseRelease("not json"))
    }

    @Test
    fun `files from anywhere but this repository's releases are not taken`() {
        val elsewhere = "https://github.com/someone/else/releases/download/v0.2.1/frp-jump-android-0.2.1.apk"
        assertNull(parseRelease(release(assets = arrayOf(asset("frp-jump-android-0.2.1.apk", url = elsewhere)))))
        assertNull(parseRelease(release(assets = arrayOf(asset("frp-jump-android-0.2.1.apk", url = "http://github.com/aadegtyarev/frp-jump-android/releases/download/v0.2.1/a.apk")))))
        val foreignChecksum = parseRelease(
            release(
                assets = arrayOf(
                    asset("frp-jump-android-0.2.1.apk"),
                    asset("frp-jump-android-0.2.1.apk.sha256", url = "https://example.com/frp-jump-android-0.2.1.apk.sha256"),
                ),
            ),
        )!!
        assertNull(foreignChecksum.checksumUrl)
    }

    @Test
    fun `no release text is no notes`() {
        assertEquals("", parseRelease(release(body = "null", assets = arrayOf(asset("a.apk"))))!!.notes)
    }

    @Test
    fun `checksum files as sha256sum writes them, or the bare hex`() {
        val hex = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        assertEquals(hex, checksumFor("$hex  frp-jump-android-0.2.1.apk\n", "frp-jump-android-0.2.1.apk"))
        assertEquals(hex, checksumFor("$hex *frp-jump-android-0.2.1.apk", "frp-jump-android-0.2.1.apk"))
        assertEquals(hex, checksumFor("$hex  dist/frp-jump-android-0.2.1.apk", "frp-jump-android-0.2.1.apk"))
        assertEquals(hex, checksumFor(hex.uppercase() + "\n", "frp-jump-android-0.2.1.apk"))
        val other = "0".repeat(64)
        assertEquals(hex, checksumFor("$other  other.apk\n$hex  a.apk\n", "a.apk"))
    }

    @Test
    fun `no checksum for this apk`() {
        assertNull(checksumFor("${"0".repeat(64)}  other.apk", "a.apk"))
        assertNull(checksumFor("ba7816bf  a.apk", "a.apk"))
        assertNull(checksumFor("<html>Not Found</html>", "a.apk"))
        assertNull(checksumFor("", "a.apk"))
    }
}
