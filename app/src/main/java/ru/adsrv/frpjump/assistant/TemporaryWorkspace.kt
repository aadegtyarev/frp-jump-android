package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.Job
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import ru.adsrv.frpjump.settings.AssistantAvailability

/** Process-only bytes for the app's one chat; all methods ending in Locked require the existing chat gate. */
class TemporaryWorkspace internal constructor(private val availability: () -> AssistantAvailability, private val snapshot: () -> AssistantProviderSnapshot) {
    private data class File(val id: String, val name: String, val bytes: ByteArray)
    private val files = ArrayList<File>()
    private var total = 0
    private var generation = 0L
    private var revision = snapshot().revision
    private var disableVersion = availability().disableVersion
    private var problem = snapshot().problem

    /** Refreshes on every access as well as the owner's idle observers, so an observer delay never revives bytes. */
    internal fun stampLocked(): Long? {
        val now = availability(); val provider = snapshot()
        if (now.disableVersion != disableVersion || provider.revision != revision || provider.problem != problem) {
            clearLocked(); disableVersion = now.disableVersion; revision = provider.revision; problem = provider.problem
        }
        return generation.takeIf { now.enabled && provider.problem == null }
    }
    internal fun clearLocked() { files.clear(); total = 0; generation++ }

    /** Takes ownership of the fresh byte array; readers receive only bounded copies. */
    internal fun applyLocked(action: TempAction): TempResult = when (action) {
        is TempAction.Download -> if (action.file == null) TempResult.Checked else applyLocked(action.file)
        is TempAction.Create -> when {
            !validTemporaryName(action.name) || action.bytes.size > MAX_FILE_BYTES || files.size >= MAX_FILES || total.toLong() + action.bytes.size > MAX_TOTAL_BYTES -> TempResult.Full
            files.any { it.id == action.id } -> TempResult.Full
            else -> {
                val file = File(action.id, action.name, action.bytes)
                files.add(file); total += file.bytes.size
                TempResult.Created(TempInfo(file.id, file.name, file.bytes.size))
            }
        }
        TempAction.List -> TempResult.Listed(files.map { TempInfo(it.id, it.name, it.bytes.size) })
        is TempAction.Read -> files.singleOrNull { it.id == action.id }?.let { file ->
            TempResult.Preview(file.name, file.bytes.size, file.bytes.copyOfRange(0, minOf(file.bytes.size, PREVIEW_BYTES)))
        } ?: TempResult.Missing
    }
    companion object {
        const val MAX_FILES = 16
        const val MAX_FILE_BYTES = 2 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 8 * 1024 * 1024
        const val PREVIEW_BYTES = 8192
    }
}
internal data class TempInfo(val id: String, val name: String, val bytes: Int)
internal sealed interface TempAction {
    class Download(val file: Create?, val valid: () -> Boolean) : TempAction
    class Create(val id: String, val name: String, val bytes: ByteArray) : TempAction
    data object List : TempAction
    class Read(val id: String) : TempAction
}
internal sealed interface TempResult {
    class Created(val file: TempInfo) : TempResult
    class Listed(val files: List<TempInfo>) : TempResult
    class Preview(val name: String, val size: Int, val bytes: ByteArray) : TempResult
    data object Checked : TempResult
    data object Full : TempResult
    data object Missing : TempResult
}
/** Bound to the current Send and checked under its existing ownership gate. */
internal class TemporaryAccess(val apply: (TempAction, Job?) -> TempResult?) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TemporaryAccess>
}

internal fun validTemporaryName(name: String): Boolean = name.isNotBlank() && name.length <= 128 && name != "." && name != ".." &&
    name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }
