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
import ru.adsrv.frpjump.settings.AssistantTools
import ru.adsrv.frpjump.settings.SettingsStore

internal data class AssistantToolsUiState(
    val committed: AssistantTools,
    val pending: Set<String> = emptySet(),
    val failed: Set<String> = emptySet(),
)

/** Tools-only autosave owner in the parent ViewModel's Main scope, not the row's lifetime.
 * Pending admission and all presentation publications run in that one scope/context. Store
 * notifications are wakeups: never replay their possibly older AppSettings payload as checked.
 */
internal class AssistantToolsBinding(
    private val store: SettingsStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutable = MutableStateFlow(AssistantToolsUiState(store.assistantAvailability.tools))
    val state = mutable.asStateFlow()

    init {
        scope.launch {
            store.settings.collect {
                mutable.value = mutable.value.copy(committed = store.assistantAvailability.tools)
            }
        }
    }

    fun change(id: String, enabled: Boolean) {
        require(id in setOf("web_search", "read_page", "inspect_image")) { "Unknown assistant tool" }
        scope.launch {
            ensureActive()
            if (id in mutable.value.pending) return@launch
            mutable.value = mutable.value.copy(pending = mutable.value.pending + id, failed = mutable.value.failed - id)
            val failed = try {
                withContext(io) { store.setAssistantTool(id, enabled) }
                false
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { true }
            ensureActive()
            // Read on Main AFTER IO: another legitimate writer may have won since our commit.
            // Actual committed checked and completion are acknowledged in a single snapshot.
            mutable.value = mutable.value.copy(
                committed = store.assistantAvailability.tools,
                pending = mutable.value.pending - id,
                failed = if (failed) mutable.value.failed + id else mutable.value.failed - id,
            )
        }
    }
}
