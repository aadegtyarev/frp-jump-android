package ru.adsrv.frpjump.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a downloaded APK to the system installer, which shows what it
 * installs and asks the owner; it also refuses an APK not signed with the
 * installed app's key. ACTION_VIEW on a content:// URI works the same on
 * Android 10–15 and needs no session or status receiver.
 */
object ApkInstaller {
    /** Whether the owner allowed this app to install apps ("Install unknown apps"). */
    fun allowed(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** False when no installer took it. */
    fun install(context: Context, apk: File): Boolean {
        val uri = FileProvider.getUriForFile(context, authority(context), apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_TYPE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        // Straight to the system's installer: a file manager taking APKs too would otherwise ask "Open with".
        context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_SYSTEM_ONLY)
            .firstOrNull()?.activityInfo?.packageName?.let { intent.setPackage(it) }
        return start(context, intent)
    }

    /** This app's "Install unknown apps" switch; the list of all apps as a fallback. */
    fun openPermission(context: Context): Boolean =
        start(context, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) ||
            start(context, Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))

    /** As the manifest's provider names it: per application id, so a side-by-side build has its own. */
    private fun authority(context: Context) = "${context.packageName}.updates"

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    private const val APK_TYPE = "application/vnd.android.package-archive"
}

/** The directory downloads go to; shared with the FileProvider through res/xml/update_files.xml. */
fun updateDownloads(context: Context) = File(context.cacheDir, "updates")
