package ru.adsrv.frpjump.mqtt

import java.util.Locale

/** A node of the tree as a reader sees it at one moment; it does not change afterwards. */
data class TopicNode(
    val path: MqttPath,
    /** The current value; null for a level that is only a branch. */
    val value: TopicValue?,
    /** Seen topics below this node, itself not counted. */
    val topicsBelow: Int,
    /**
     * Milliseconds since the epoch: the newest change of this node or any
     * below it seen in this session; 0 when every value there came from
     * before it ([TopicValue.beforeSession]).
     */
    val newestChangeAt: Long,
    val hasChildren: Boolean,
) {
    val name: String get() = path.name
}

/** What [TopicTree.receive] did with a message. */
enum class Received {
    /** A topic seen for the first time. */
    NEW_TOPIC,

    /** A new value of a known topic. */
    CHANGED,

    /** A retained value re-sent equal to the known one: marks refreshed, not a change. */
    UNCHANGED,

    /** A new topic at the session's limit: not added. */
    OVER_LIMIT,
}

/**
 * The topics one session has seen, each with its last values (at most
 * [HISTORY] of them, newest first), and per branch the count of seen
 * topics below it and the time of the newest change there. A node can hold
 * a value and children at once. At most [limit] topics: at the limit the
 * known ones keep updating and new ones are dropped ([limitReached]).
 *
 * Thread safety: one lock around every call. Messages are written by the
 * network thread and the screen reads; a read copies what it needs into
 * immutable [TopicNode] / [TopicValue] objects under the lock and sorts
 * outside it. A lock was chosen over an immutable tree replaced on every
 * message because each message would then copy every level map on its
 * path, which for a wide level (thousands of controls) costs far more than
 * the short, rarely contended lock does with one writer.
 */
class TopicTree(
    val limit: Int = DEFAULT_LIMIT,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Node {
        val children = HashMap<String, Node>()

        /** Newest first; the first one is the current value. */
        val history = ArrayDeque<TopicValue>()
        var topicsBelow = 0
        var newestChangeAt = 0L
    }

    private val lock = Any()
    private var root = Node()
    private var topics = 0
    private var dropped = false

    val topicCount: Int get() = synchronized(lock) { topics }

    /**
     * A new topic has been dropped at the limit since the last [clear]: the
     * tree is missing topics. Exactly [limit] topics with none dropped is
     * not "limit reached".
     */
    val limitReached: Boolean get() = synchronized(lock) { dropped }

    /**
     * Takes one message. A retained message equal to the known value is
     * [Received.UNCHANGED]: a re-delivery on subscribe cannot be told from
     * a retained re-publish of the same value (also under MQTT 5), so
     * neither is a change. Under MQTT 5 a copy through a second matching
     * subscription must be dropped by the caller first (see [CopyFilter]).
     *
     * [onSubscribe]: the message came while a subscription re-delivers the
     * broker's retained values (the snapshot, a branch or pin just
     * subscribed). A topic's first value delivered so with the retain flag
     * is [TopicValue.beforeSession] and is no change of its branches.
     */
    fun receive(topic: MqttPath, payload: ByteArray, retain: Boolean, qos: Int, version: MqttVersion, onSubscribe: Boolean = false): Received {
        require(!topic.isRoot) { "a message has a topic" }
        val at = now()
        synchronized(lock) {
            val known = find(topic)
            val current = known?.history?.firstOrNull()
            if (known == null || current == null) {
                if (topics >= limit) {
                    dropped = true
                    return Received.OVER_LIMIT
                }
                val node = known ?: create(topic)
                val value = TopicValue.of(payload, retain, qos, at, version, previous = null, beforeSession = onSubscribe && retain)
                node.history.addFirst(value)
                topics++
                forEachOnPath(topic) { it, isTopic ->
                    if (!isTopic) it.topicsBelow++
                    if (!value.beforeSession) it.newestChangeAt = maxOf(it.newestChangeAt, at)
                }
                return Received.NEW_TOPIC
            }
            if (retain && payload.contentEquals(current.payload)) {
                // Re-sent on a subscribe: keep the value's time (known or not), refresh its marks.
                known.history[0] = TopicValue.of(payload, retain, qos, current.receivedAt, version, previous = current, beforeSession = current.beforeSession)
                return Received.UNCHANGED
            }
            known.history.addFirst(TopicValue.of(payload, retain, qos, at, version, previous = current))
            while (known.history.size > HISTORY) known.history.removeLast()
            forEachOnPath(topic) { it, _ -> it.newestChangeAt = maxOf(it.newestChangeAt, at) }
            return Received.CHANGED
        }
    }

    /** The node at [path], or null when nothing has been seen at or below it. */
    fun node(path: MqttPath): TopicNode? = synchronized(lock) { find(path)?.let { view(path, it) } }

    /** The children of [path] in [NaturalOrder]; empty when nothing has been seen below it. */
    fun children(path: MqttPath): List<TopicNode> {
        val nodes = synchronized(lock) {
            find(path)?.children?.map { (level, node) -> view(path.child(level), node) } ?: emptyList()
        }
        return nodes.sortedWith(compareBy(NaturalOrder) { it.name })
    }

    /** The last values of [path], newest first, at most [HISTORY]. */
    fun history(path: MqttPath): List<TopicValue> = synchronized(lock) { find(path)?.history?.toList() ?: emptyList() }

    /**
     * The seen topics below [at] whose path relative to [at] contains
     * [query], ignoring case, as paths relative to [at] in [NaturalOrder];
     * empty for an empty query. Values are never looked at.
     */
    fun search(at: MqttPath, query: String): List<MqttPath> {
        if (query.isEmpty()) return emptyList()
        val needle = query.lowercase(Locale.ROOT)
        val found = mutableListOf<MqttPath>()
        synchronized(lock) {
            val start = find(at) ?: return emptyList()
            collect(start, ArrayList(), StringBuilder(), needle, found)
        }
        return found.sortedWith(NaturalOrder.paths)
    }

    /**
     * The seen topics below [at] that [pattern]'s levels match, taken
     * level by level from [at] (exactly, so case counts), as paths relative
     * to [at] in [NaturalOrder]; [at] itself is not listed. An exact level
     * is looked up, not scanned, so only the branches the pattern opens are
     * walked. At the root a level starting with a wildcard skips `$` levels.
     */
    fun match(at: MqttPath, pattern: List<LevelPattern>): List<MqttPath> {
        if (pattern.isEmpty()) return emptyList()
        val found = mutableListOf<MqttPath>()
        synchronized(lock) {
            val start = find(at) ?: return emptyList()
            walk(start, at.isRoot, pattern, 0, ArrayList(), found)
        }
        return found.sortedWith(NaturalOrder.paths)
    }

    /** Forgets every topic, for "Refresh tree". */
    fun clear() = synchronized(lock) {
        root = Node()
        topics = 0
        dropped = false
    }

    /**
     * Depth first with one level list and one lowercased path text, both
     * grown and cut back on the way; a path is copied only when it matches.
     */
    private fun collect(node: Node, levels: ArrayList<String>, text: StringBuilder, needle: String, found: MutableList<MqttPath>) {
        node.children.forEach { (level, child) ->
            val mark = text.length
            if (levels.isNotEmpty()) text.append('/')
            text.append(level.lowercase(Locale.ROOT))
            levels.add(level)
            if (child.history.isNotEmpty() && text.indexOf(needle) >= 0) found += MqttPath(levels.toList())
            collect(child, levels, text, needle, found)
            levels.removeAt(levels.lastIndex)
            text.setLength(mark)
        }
    }

    /** [node] is at [levels]; [k] is the pattern level its children are matched against. */
    private fun walk(node: Node, atRoot: Boolean, pattern: List<LevelPattern>, k: Int, levels: ArrayList<String>, found: MutableList<MqttPath>) {
        val part = pattern[k]
        if (part == LevelPattern.Rest) {
            // `a/#` matches `a` as well, unless `a` is where the search started.
            if (levels.isNotEmpty() && node.history.isNotEmpty()) found += MqttPath(levels.toList())
            collectAll(node, atRoot && levels.isEmpty(), levels, found)
            return
        }
        val last = k == pattern.lastIndex
        val step = { level: String, child: Node ->
            levels.add(level)
            if (last) {
                if (child.history.isNotEmpty()) found += MqttPath(levels.toList())
            } else {
                walk(child, atRoot, pattern, k + 1, levels, found)
            }
            levels.removeAt(levels.lastIndex)
        }
        if (part is LevelPattern.Exact) {
            node.children[part.text]?.let { step(part.text, it) }
            return
        }
        val noDollar = atRoot && levels.isEmpty() && skipsDollar(part)
        node.children.forEach { (level, child) ->
            if (noDollar && level.startsWith("$")) return@forEach
            if (levelMatches(part, level)) step(level, child)
        }
    }

    /** Every topic below [node]; [noDollar] skips its `$` children. */
    private fun collectAll(node: Node, noDollar: Boolean, levels: ArrayList<String>, found: MutableList<MqttPath>) {
        node.children.forEach { (level, child) ->
            if (noDollar && level.startsWith("$")) return@forEach
            levels.add(level)
            if (child.history.isNotEmpty()) found += MqttPath(levels.toList())
            collectAll(child, false, levels, found)
            levels.removeAt(levels.lastIndex)
        }
    }

    private fun view(path: MqttPath, node: Node) =
        TopicNode(path, node.history.firstOrNull(), node.topicsBelow, node.newestChangeAt, node.children.isNotEmpty())

    private fun find(path: MqttPath): Node? {
        var node = root
        path.levels.forEach { level -> node = node.children[level] ?: return null }
        return node
    }

    private fun create(path: MqttPath): Node {
        var node = root
        path.levels.forEach { level -> node = node.children.getOrPut(level) { Node() } }
        return node
    }

    /** The root, every level of [topic] down to it; `isTopic` for the topic's own node. */
    private fun forEachOnPath(topic: MqttPath, action: (Node, isTopic: Boolean) -> Unit) {
        var node = root
        action(node, false)
        topic.levels.forEachIndexed { index, level ->
            node = node.children.getValue(level)
            action(node, index == topic.levels.lastIndex)
        }
    }

    companion object {
        const val DEFAULT_LIMIT = 100_000
        const val HISTORY = 20
    }
}
