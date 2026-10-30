package ru.adsrv.frpjump.profiles

import ru.adsrv.frpjump.store.Credentials
import ru.adsrv.frpjump.store.KeyMissingException
import ru.adsrv.frpjump.store.SecretBox
import ru.adsrv.frpjump.store.TextStorage
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.crypto.AEADBadTagException

/** A [TextStorage] in a plain file; [crashOn] makes the next write to a file of that name fail, once. */
class FileStorages {
    var crashOn: String? = null

    fun storage(file: File): TextStorage = object : TextStorage {
        override fun read(): String? = if (file.exists()) file.readText() else null

        override fun write(text: String) {
            if (file.name == crashOn) {
                crashOn = null
                throw IOException("crash while writing ${file.name}")
            }
            file.parentFile?.mkdirs()
            file.writeText(text)
        }

        override fun delete() {
            file.delete()
        }
    }
}

/** Keystore keys by alias: what one alias sealed only that alias opens, and only while it exists. */
class FakeKeystore {
    val keys = mutableMapOf<String, String>()

    fun box(alias: String): SecretBox = object : SecretBox {
        override fun seal(plain: String): String = keys.getOrPut(alias) { UUID.randomUUID().toString() } + "|" + plain

        override fun open(sealed: String): String {
            val key = keys[alias] ?: throw KeyMissingException()
            if (!sealed.startsWith("$key|")) throw AEADBadTagException()
            return sealed.removePrefix("$key|")
        }

        override fun destroy() {
            keys.remove(alias)
        }
    }
}

fun credentials(server: String, name: String = "phone", token: String = "t-$name") =
    Credentials("https://$server", "id-$name", name, token, "cert", "key-$name", "ca", server, 7000)

/** A fresh directory under the module's build dir (not the system temp dir); delete it after the test. */
fun scratchDir(): File = File("build/test-scratch/${UUID.randomUUID()}").absoluteFile.apply { mkdirs() }
