package ru.adsrv.frpjump.ssh

import ru.adsrv.frpjump.store.TextStorage

/** A [TextStorage] in memory; [text] is what a file would hold. */
class MemoryStorage(var text: String? = null) : TextStorage {
    override fun read() = text

    override fun write(text: String) {
        this.text = text
    }

    override fun delete() {
        text = null
    }
}
