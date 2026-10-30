package ru.adsrv.frpjump.tunnel

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/** Writes [text] to a file created 0600 from the start: no window where others could read it. */
fun writePrivateFile(file: File, text: String) {
    file.parentFile?.mkdirs()
    file.delete()
    val path = Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    Files.write(path, text.toByteArray(Charsets.UTF_8))
}
