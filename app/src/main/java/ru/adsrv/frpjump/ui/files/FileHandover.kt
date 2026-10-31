package ru.adsrv.frpjump.ui.files

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.files.PhoneFiles
import ru.adsrv.frpjump.files.shareType
import java.io.File

/**
 * Gives a file downloaded to the cache to another app: "Open with" (a
 * chooser of the apps that view its type) or "Share"; read access only,
 * for that one file, through the app's FileProvider.
 */
fun handOver(context: Context, file: File, mime: String, name: String, share: Boolean) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
    val intent = if (share) {
        Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
    } else {
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
    }
    // The chooser passes the grant on only for what is in the clip.
    intent.clipData = ClipData.newRawUri(name, uri)
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val title = context.getString(if (share) R.string.files_share else R.string.files_open_with)
    try {
        context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.files_no_app, name), Toast.LENGTH_LONG).show()
    }
}

/** Shares several files downloaded to the cache in one go (`ACTION_SEND_MULTIPLE`), read access to those only. */
fun shareAll(context: Context, files: List<Pair<File, String>>) {
    val uris = files.map { (file, _) -> FileProvider.getUriForFile(context, "${context.packageName}.updates", file) }
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
        .setType(shareType(files.map { (_, name) -> PhoneFiles.mimeType(name) }))
        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    val clip = ClipData.newRawUri(files.first().second, uris.first())
    uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
    intent.clipData = clip
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.files_share)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.files_no_app_share), Toast.LENGTH_LONG).show()
    }
}
