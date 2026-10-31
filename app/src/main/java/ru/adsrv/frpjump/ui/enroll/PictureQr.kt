package ru.adsrv.frpjump.ui.enroll

import android.content.Context
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.enroll.QrPicture

/** What a picture from the gallery gave. */
sealed interface PictureQr {
    /** A QR code, whatever its text. */
    data class Code(val text: String) : PictureQr

    /** The picture opened, but no QR code was found in it. */
    data object NoCode : PictureQr

    /** The picture could not be opened or decoded. */
    data object Unreadable : PictureQr
}

/** "From gallery": [pick] opens the system photo picker; [busy] while the chosen picture is read. */
class PicturePicker internal constructor(private val launch: () -> Unit, private val busyOf: () -> Boolean) {
    val busy: Boolean get() = busyOf()

    fun pick() {
        if (!busy) launch()
    }
}

/**
 * The system photo picker, images only (no storage permission: Android's
 * picker, or the document picker where there is none). The chosen
 * picture is read off the main thread and [onRead] gets what was in it;
 * nothing when the owner backs out of the picker.
 */
@Composable
fun rememberPicturePicker(onRead: (PictureQr) -> Unit): PicturePicker {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val deliver by rememberUpdatedState(onRead)
    var busy by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.Default) { readPicture(context, uri) }
            busy = false
            deliver(result)
        }
    }
    return remember(launcher) {
        PicturePicker(
            launch = { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            busyOf = { busy },
        )
    }
}

/** Loads [uri] at most [QrPicture.MAX_SIDE] pixels a side, upright, and looks for a QR code in it. */
private fun readPicture(context: Context, uri: Uri): PictureQr {
    val pixels = try {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // Pixels are read back from it: a hardware bitmap would not give them.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val (width, height) = QrPicture.scaledSize(info.size.width, info.size.height)
            if (width != info.size.width || height != info.size.height) decoder.setTargetSize(width, height)
        }
        val width = bitmap.width
        val height = bitmap.height
        val argb = IntArray(width * height)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)
        bitmap.recycle()
        Triple(argb, width, height)
    } catch (e: Exception) {
        Log.w(TAG, "picture not read: ${e.javaClass.simpleName}")
        return PictureQr.Unreadable
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "picture too large to read")
        return PictureQr.Unreadable
    }
    val (argb, width, height) = pixels
    return QrPicture.decode(argb, width, height)?.let { PictureQr.Code(it) } ?: PictureQr.NoCode
}

private const val TAG = "PictureQr"
