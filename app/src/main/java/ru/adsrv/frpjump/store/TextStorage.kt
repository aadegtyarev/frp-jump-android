package ru.adsrv.frpjump.store

import android.util.AtomicFile
import ru.adsrv.frpjump.ports.writeAtomically
import java.io.File

/** One small text document; lets stores be tested without Android's file classes. */
interface TextStorage {
    /** The stored text, or null when there is none. */
    fun read(): String?
    fun write(text: String)
    fun delete()
}

/** A [TextStorage] in an [AtomicFile] in the app's private storage. */
class AtomicTextFile(file: File) : TextStorage {
    private val file = AtomicFile(file)

    override fun read(): String? = if (file.baseFile.exists()) String(file.readFully(), Charsets.UTF_8) else null

    override fun write(text: String) = writeAtomically(file, text)

    override fun delete() = file.delete()
}
