package ru.adsrv.frpjump.ui.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.assistant.AssistantToolId
import ru.adsrv.frpjump.settings.AssistantTools
import ru.adsrv.frpjump.settings.ConfirmationMode
import ru.adsrv.frpjump.settings.SettingsStore

/** [pending] and [failed] hold tool wire names (the Tools row test tags use them too) and [CONFIRMATIONS_KEY] for the confirmation switch. */
internal data class AssistantToolsUiState(
    val committed: AssistantTools,
    val pending: Set<String> = emptySet(),
    val failed: Set<String> = emptySet(),
    /** The committed confirmation switch: true when changing calls ask (the default). */
    val confirmations: Boolean = true,
)

internal const val CONFIRMATIONS_KEY = "confirmations"

/** Tools-only autosave owner in the parent ViewModel's Main scope, not the row's lifetime.
 * Pending admission and all presentation publications run in that one scope/context. Store
 * notifications are wakeups: never replay their possibly older AppSettings payload as checked.
 */
internal class AssistantToolsBinding(
    private val store: SettingsStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutable = MutableStateFlow(AssistantToolsUiState(store.assistantAvailability.tools, confirmations = store.assistantConfirmations.global == ConfirmationMode.ASK))
    val state = mutable.asStateFlow()

    init {
        scope.launch {
            store.settings.collect {
                mutable.value = mutable.value.copy(committed = store.assistantAvailability.tools,
                    confirmations = store.assistantConfirmations.global == ConfirmationMode.ASK)
            }
        }
    }

    /** The app-wide confirmation switch: same autosave, pending and inline failure as a tool switch. */
    fun changeConfirmations(on: Boolean) = save(CONFIRMATIONS_KEY) { store.setAssistantConfirmations(if (on) ConfirmationMode.ASK else ConfirmationMode.OFF) }

    fun change(id: AssistantToolId, enabled: Boolean) = save(id.wire) { store.setAssistantTool(id, enabled) }

    private fun save(key: String, write: () -> Unit) {
        scope.launch {
            ensureActive()
            if (key in mutable.value.pending) return@launch
            mutable.value = mutable.value.copy(pending = mutable.value.pending + key, failed = mutable.value.failed - key)
            val failed = try {
                withContext(io) { write() }
                false
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { true }
            ensureActive()
            // Read on Main AFTER IO: another legitimate writer may have won since our commit.
            // Actual committed checked and completion are acknowledged in a single snapshot.
            mutable.value = mutable.value.copy(
                committed = store.assistantAvailability.tools,
                confirmations = store.assistantConfirmations.global == ConfirmationMode.ASK,
                pending = mutable.value.pending - key,
                failed = if (failed) mutable.value.failed + key else mutable.value.failed - key,
            )
        }
    }
}
