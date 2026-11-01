package ru.adsrv.frpjump.store

import android.util.AtomicFile
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

private fun writeAtomically(file: AtomicFile, text: String) {
    val out = file.startWrite()
    try {
        out.write(text.toByteArray(Charsets.UTF_8))
        file.finishWrite(out)
    } catch (e: Exception) {
        file.failWrite(out)
        throw e
    }
}
