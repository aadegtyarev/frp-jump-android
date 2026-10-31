package ru.adsrv.frpjump.files

import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPException
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.locale.Texts
import java.io.IOException

/**
 * Whether [e] is the device refusing one operation (no such file, no
 * permission, no space), which leaves the connection usable; anything
 * else means the connection is gone.
 */
fun isDeviceRefusal(e: Throwable): Boolean =
    e is SFTPException && e.statusCode != Response.StatusCode.NO_CONNECTION && e.statusCode != Response.StatusCode.CONNECITON_LOST

/** Why a file operation failed, in plain words; [phone] when it failed on the phone (its folder or cache). */
fun fileProblem(e: Throwable, phone: Boolean = false): String {
    if (e is SFTPException) {
        return when (e.statusCode) {
            Response.StatusCode.NO_SUCH_FILE, Response.StatusCode.NO_SUCH_PATH -> Texts.get(R.string.files_problem_no_such)
            Response.StatusCode.PERMISSION_DENIED, Response.StatusCode.WRITE_PROTECT -> Texts.get(R.string.files_problem_permission)
            Response.StatusCode.NO_SPACE_ON_FILESYSTEM, Response.StatusCode.QUOTA_EXCEEDED -> Texts.get(R.string.files_problem_space_device)
            Response.StatusCode.NO_CONNECTION, Response.StatusCode.CONNECITON_LOST -> Texts.get(R.string.files_problem_connection)
            else -> e.message?.takeIf { it.isNotBlank() } ?: e.statusCode.name.lowercase().replace('_', ' ')
        }
    }
    val text = e.message.orEmpty()
    val onPhone = phone || e is PhoneSideException
    return when {
        onPhone && (text.contains("ENOSPC") || text.contains("No space", ignoreCase = true)) -> Texts.get(R.string.files_problem_space_phone)
        e is SecurityException -> Texts.get(R.string.files_problem_folder_access)
        onPhone -> Texts.get(R.string.files_problem_phone, text.ifBlank { e.javaClass.simpleName })
        e is IOException -> Texts.get(R.string.files_problem_connection)
        else -> text.ifBlank { e.javaClass.simpleName }
    }
}
