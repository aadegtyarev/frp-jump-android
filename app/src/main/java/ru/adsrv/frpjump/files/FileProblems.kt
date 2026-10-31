package ru.adsrv.frpjump.files

import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.locale.Texts
import java.io.IOException

/**
 * Whether [e] is the device refusing one operation (no such file, no
 * permission, no space), which leaves the connection usable; anything
 * else from the device means the connection is gone.
 */
fun isDeviceRefusal(e: Throwable): Boolean = e is RemoteRefusal

/** Whether [e] is no access to the chosen download folder: the owner should pick it again. */
fun isFolderAccess(e: Throwable): Boolean = e is SecurityException || e.cause is SecurityException

/** Why a file operation failed, in plain words. */
fun fileProblem(e: Throwable): String {
    if (e is RemoteRefusal) {
        return when (e.reason) {
            Refusal.NO_SUCH -> Texts.get(R.string.files_problem_no_such)
            Refusal.PERMISSION -> Texts.get(R.string.files_problem_permission)
            Refusal.NO_SPACE -> Texts.get(R.string.files_problem_space_device)
            else -> e.message?.takeIf { it.isNotBlank() } ?: e.reason.name.lowercase()
        }
    }
    val text = e.message.orEmpty()
    return when {
        isFolderAccess(e) -> Texts.get(R.string.files_problem_folder_access)
        e is PhoneSideException && (text.contains("ENOSPC") || text.contains("No space", ignoreCase = true)) -> Texts.get(R.string.files_problem_space_phone)
        e is PhoneSideException -> Texts.get(R.string.files_problem_phone, text.ifBlank { e.javaClass.simpleName })
        e is IOException -> Texts.get(R.string.files_problem_connection)
        else -> text.ifBlank { e.javaClass.simpleName }
    }
}
