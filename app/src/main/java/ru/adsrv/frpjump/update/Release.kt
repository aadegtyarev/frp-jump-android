package ru.adsrv.frpjump.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.adsrv.frpjump.BuildConfig
import ru.adsrv.frpjump.api.WireJson

/** `GET /repos/{owner}/{repo}/releases/latest`, the fields the app reads. */
@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("body") val body: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("draft") val draft: Boolean = false,
    @SerialName("assets") val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
private data class GitHubAsset(
    @SerialName("name") val name: String,
    @SerialName("browser_download_url") val url: String,
    @SerialName("size") val size: Long = 0,
)

/**
 * A published release the app could update to: its version (the tag without
 * the "v"), notes (the release text, Markdown as written), the APK and the
 * `<apk>.sha256` file published next to it; [checksumUrl] null when the
 * release has none, and then it is not installed.
 */
data class Release(
    val version: String,
    val notes: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    val checksumUrl: String?,
    val pageUrl: String?,
)

/**
 * The release in [json] (a GitHub release object); null when it is not
 * one, is a draft, has no version tag or carries no APK. Of several APKs,
 * the one named like the release script names it is taken.
 */
fun parseRelease(json: String, downloads: String = DOWNLOAD_PREFIX): Release? {
    val release = runCatching { WireJson.decodeFromString<GitHubRelease>(json) }.getOrNull() ?: return null
    if (release.draft) return null
    val version = AppVersion.parse(release.tagName) ?: return null
    // Only files of this repository's releases, whatever else the answer names.
    val files = release.assets.filter { it.url.startsWith(downloads) }
    val apks = files.filter { it.name.endsWith(".apk", ignoreCase = true) }
    val apk = apks.firstOrNull { it.name.startsWith(APK_PREFIX) } ?: apks.firstOrNull() ?: return null
    val checksum = files.firstOrNull { it.name == apk.name + CHECKSUM_SUFFIX }
    return Release(
        version = version.toString(),
        notes = release.body.orEmpty().trim(),
        apkName = apk.name,
        apkUrl = apk.url,
        apkSize = apk.size,
        checksumUrl = checksum?.url,
        pageUrl = release.htmlUrl,
    )
}

/**
 * The SHA-256 (lower-case hex) a `.sha256` file gives for [apkName]: as
 * `sha256sum` writes it ("<hex>  <name>", "<hex> *<name>") or the bare hex.
 * Null when there is none for that name.
 */
fun checksumFor(text: String, apkName: String): String? {
    for (line in text.lineSequence()) {
        val parts = line.trim().split(Regex("""\s+"""), limit = 2)
        val hex = parts[0].lowercase()
        if (!SHA256_HEX.matches(hex)) continue
        val name = parts.getOrNull(1)?.trim()?.removePrefix("*")
        if (name == null || name.isEmpty() || name == apkName || name.substringAfterLast('/') == apkName) return hex
    }
    return null
}

/** Where this repository's release files are; [parseRelease] takes no other. */
const val DOWNLOAD_PREFIX = "https://github.com/${BuildConfig.UPDATE_REPOSITORY}/releases/download/"
const val APK_PREFIX = "frp-jump-android-"
const val CHECKSUM_SUFFIX = ".sha256"
private val SHA256_HEX = Regex("""[0-9a-f]{64}""")
