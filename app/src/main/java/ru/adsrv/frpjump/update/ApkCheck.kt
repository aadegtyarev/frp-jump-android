package ru.adsrv.frpjump.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import java.io.File
import java.security.MessageDigest

/** What an APK says about itself: its package, version and the SHA-256 of each signing certificate. */
data class ApkFacts(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val signers: Set<String>,
)

/** Why a downloaded APK is not handed to the installer. */
enum class ApkProblem {
    /** Android cannot read it as an APK. */
    UNREADABLE,

    /** It is another app. */
    OTHER_APP,

    /** Its versionCode is not above the installed one: Android would refuse it, or it would go back. */
    NOT_NEWER,

    /** It is not the version the release says it is. */
    OTHER_VERSION,

    /** It is not signed with the installed app's key. */
    OTHER_SIGNER,
}

/**
 * Null when [apk] (null: unreadable) may update [installed] as release
 * [version]: the same package, a higher versionCode, versionName that
 * version, and exactly the installed app's signing certificates. The
 * SHA-256 published with the release only shows the file arrived as
 * published; this shows it is this app, newer, from the same key.
 */
fun apkProblem(apk: ApkFacts?, installed: ApkFacts, version: String): ApkProblem? {
    if (apk == null || apk.signers.isEmpty()) return ApkProblem.UNREADABLE
    if (apk.packageName != installed.packageName) return ApkProblem.OTHER_APP
    if (apk.versionCode <= installed.versionCode) return ApkProblem.NOT_NEWER
    val named = apk.versionName?.let(AppVersion::parse)
    val released = AppVersion.parse(version)
    if (named == null || released == null || named.compareTo(released) != 0) return ApkProblem.OTHER_VERSION
    if (installed.signers.isEmpty() || apk.signers != installed.signers) return ApkProblem.OTHER_SIGNER
    return null
}

/** [ApkFacts] of the APK file [apk]; null when Android cannot read it. Disk: off the main thread. */
fun archiveFacts(context: Context, apk: File): ApkFacts? =
    context.packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)?.let(::facts)

/** [ApkFacts] of this app as installed. */
fun installedFacts(context: Context): ApkFacts =
    facts(context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES))

private fun facts(info: PackageInfo): ApkFacts {
    val signers = info.signingInfo?.apkContentsSigners.orEmpty()
        .map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex() }
        .toSet()
    return ApkFacts(info.packageName, info.longVersionCode, info.versionName, signers)
}
