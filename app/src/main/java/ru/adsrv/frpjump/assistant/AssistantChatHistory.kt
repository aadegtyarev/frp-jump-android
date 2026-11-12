package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import ru.adsrv.frpjump.agent.*
import ru.adsrv.frpjump.assistant.tools.*
import ru.adsrv.frpjump.store.SecretBox
import ru.adsrv.frpjump.store.TextStorage
import ru.adsrv.frpjump.assistant.chat.*

enum class ChatHistoryProblem { READ, WRITE, CLEAR }

/** Existing atomic file + SecretBox; retries retain data only, never a live API provider. */
class AssistantChatHistory(private val file: TextStorage, private val box: SecretBox,
    private val scope: CoroutineScope, private val io: CoroutineDispatcher = Dispatchers.IO) {
    private val mutex = Mutex()
    private val queue = Any()
    private var saving: Job? = null
    private var last: SavedChat? = null
    private var sequence = 0L
    private val mutableProblem = MutableStateFlow<ChatHistoryProblem?>(null)
    val problem: StateFlow<ChatHistoryProblem?> = mutableProblem
    private val json = Json { encodeDefaults = true }

    internal suspend fun load(): SavedChat? = withContext(io) {
        while (true) {
            val pending = synchronized(queue) { saving }; pending?.join()
            if (synchronized(queue) { saving === pending }) break
        }
        synchronized(queue) { last }?.let { return@withContext it.restored() }
        mutex.withLock {
            val sealed = file.read() ?: return@withLock null
            require(sealed.length <= 16_000_000)
            val text = box.open(sealed); require(text.length <= 8_000_000)
            json.decodeFromString<SavedChat>(text).restored()
        }
    }
    internal fun save(value: SavedChat) {
        val old: Job?; val next: Job
        synchronized(queue) {
            last = value; val id = ++sequence; old = saving
            next = scope.launch(io, start = CoroutineStart.LAZY) {
                try {
                    mutex.withLock {
                        ensureActive(); val text = json.encodeToString(value); require(text.length <= 8_000_000)
                        val sealed = box.seal(text); ensureActive(); file.write(sealed)
                    }
                    synchronized(queue) { if (sequence == id) { last = null; mutableProblem.value = null } }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { synchronized(queue) { if (sequence == id) mutableProblem.value = ChatHistoryProblem.WRITE } }
            }; saving = next
        }
        old?.cancel(); next.start()
    }
    fun retry() { synchronized(queue) { last }?.let(::save) }
    suspend fun clear() = withContext(io) {
        val (id, old) = synchronized(queue) { ++sequence; sequence to saving.also { saving = null } }
        old?.cancelAndJoin()
        try {
            mutex.withLock { file.delete() }
            synchronized(queue) { if (sequence == id) { last = null; mutableProblem.value = null } }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { synchronized(queue) { if (sequence == id) mutableProblem.value = ChatHistoryProblem.CLEAR }; throw failure }
    }
}
