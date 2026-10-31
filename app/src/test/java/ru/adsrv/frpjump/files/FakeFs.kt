package ru.adsrv.frpjump.files

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/** A device's file system in memory, with links, refusals and failures on demand. */
class FakeFs(private val home: String = "/root") : RemoteFs {
    private sealed interface Node
    private object Dir : Node
    private class File(var bytes: ByteArray, val modified: Long = 0) : Node
    private class Link(val target: String) : Node

    private val nodes = sortedMapOf<String, Node>("/" to Dir)

    /** `stat` of these is refused: no permission to look. */
    val denied = mutableSetOf<String>()

    /** Called before each exclusive create, e.g. to have someone else make the file meanwhile. */
    var beforeCreate: (path: String) -> Unit = {}

    /** Called before each rename, e.g. to make the name taken meanwhile. */
    var beforeRename: (from: String, to: String) -> Unit = { _, _ -> }

    /** A write past this many bytes into a new file fails as a dropped connection. */
    var failWritesAfter: Long? = null

    /** Each read of a file stream waits this long, so uploads and downloads overlap. */
    var slowMs = 0L

    val statsAtOnce = AtomicInteger()
    val mostStatsAtOnce = AtomicInteger()

    fun dir(path: String) = apply { mkdirs(path) }

    /** A file modified at [modified] seconds (0: long ago). */
    fun file(path: String, text: String, modified: Long = 0) = apply {
        mkdirs(parentOf(path))
        nodes[path] = File(text.toByteArray(), modified)
    }

    fun link(path: String, target: String) = apply {
        mkdirs(parentOf(path))
        nodes[path] = Link(target)
    }

    fun text(path: String): String? = (nodes[path] as? File)?.bytes?.let(::String)

    fun has(path: String) = path in nodes

    fun names(dir: String): List<String> = nodes.keys.filter { it != dir && parentOf(it) == dir }.map { it.substringAfterLast('/') }

    private fun mkdirs(path: String) {
        var at = ""
        normalizePath(path).split('/').filter { it.isNotEmpty() }.forEach {
            at += "/$it"
            nodes.putIfAbsent(at, Dir)
        }
    }

    /** The physical path of [path]; the last part's link followed when [follow]. */
    private fun resolve(path: String, follow: Boolean, depth: Int = 0): String {
        if (depth > 20) throw RemoteRefusal(Refusal.LOOP)
        val parts = resolvePath(home, path).split('/').filter { it.isNotEmpty() }
        var at = "/"
        parts.forEachIndexed { i, part ->
            val next = childOf(at, part)
            val node = nodes[next]
            val last = i == parts.lastIndex
            at = when {
                node == null && last -> next
                node == null -> throw RemoteRefusal(Refusal.NO_SUCH)
                node is Link && (!last || follow) -> resolve(resolvePath(at, node.target), follow = true, depth + 1)
                else -> next
            }
        }
        return at
    }

    private fun attrs(node: Node) = when (node) {
        Dir -> RemoteAttrs(NodeKind.DIR, 0, 1)
        is File -> RemoteAttrs(NodeKind.FILE, node.bytes.size.toLong(), node.modified)
        is Link -> RemoteAttrs(NodeKind.LINK, 0, 3)
    }

    override fun canonicalize(path: String): String = resolve(path, follow = true).also {
        if (it !in nodes) throw RemoteRefusal(Refusal.NO_SUCH)
    }

    override fun list(path: String): List<Pair<String, RemoteAttrs>> {
        val dir = canonicalize(path)
        return names(dir).map { it to attrs(nodes.getValue(childOf(dir, it))) }
    }

    override fun stat(path: String): RemoteAttrs {
        val now = statsAtOnce.incrementAndGet()
        mostStatsAtOnce.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        try {
            Thread.sleep(5)
            if (path in denied) throw RemoteRefusal(Refusal.PERMISSION)
            return attrs(nodes[resolve(path, follow = true)] ?: throw RemoteRefusal(Refusal.NO_SUCH))
        } finally {
            statsAtOnce.decrementAndGet()
        }
    }

    override fun lstat(path: String): RemoteAttrs = attrs(nodes[resolve(path, follow = false)] ?: throw RemoteRefusal(Refusal.NO_SUCH))

    override fun readlink(path: String): String =
        (nodes[resolve(path, follow = false)] as? Link)?.target ?: throw RemoteRefusal(Refusal.OTHER)

    override fun openRead(path: String): RemoteRead {
        val file = nodes[resolve(path, follow = true)] as? File ?: throw RemoteRefusal(Refusal.NO_SUCH)
        val bytes = ByteArrayInputStream(file.bytes)
        return RemoteRead(
            object : InputStream() {
                override fun read(): Int = bytes.read()

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (slowMs > 0) Thread.sleep(slowMs)
                    return bytes.read(b, off, len)
                }
            },
            file.bytes.size.toLong(),
        )
    }

    override fun createExclusive(path: String): OutputStream {
        beforeCreate(path)
        val at = resolve(path, follow = false)
        if (at in nodes) throw RemoteRefusal(Refusal.OTHER, "exists")
        val file = File(ByteArray(0))
        nodes[at] = file
        val limit = failWritesAfter
        return object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (limit != null && file.bytes.size + len > limit) throw IOException("connection lost")
                file.bytes += b.copyOfRange(off, off + len)
            }
        }
    }

    override fun rename(from: String, to: String) {
        beforeRename(from, to)
        val node = nodes[from] ?: throw RemoteRefusal(Refusal.NO_SUCH)
        if (to in nodes) throw RemoteRefusal(Refusal.OTHER, "exists")
        nodes.remove(from)
        nodes[to] = node
    }

    override fun remove(path: String) {
        nodes.remove(path) ?: throw RemoteRefusal(Refusal.NO_SUCH)
    }
}

/** An upload's source whose reads wait [slowMs] each and may fail after [failAfter] bytes. */
class SlowInput(text: String, private val slowMs: Long = 0, private val failAfter: Int? = null) : InputStream() {
    private val bytes = ByteArrayInputStream(text.toByteArray())
    private var read = 0

    override fun read(): Int = bytes.read()

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (slowMs > 0) Thread.sleep(slowMs)
        if (failAfter != null && read >= failAfter) throw IOException("phone read failed")
        val n = bytes.read(b, off, minOf(len, 1024))
        if (n > 0) read += n
        return n
    }
}
