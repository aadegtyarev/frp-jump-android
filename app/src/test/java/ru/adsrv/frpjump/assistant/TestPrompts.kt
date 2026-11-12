package ru.adsrv.frpjump.assistant

import java.io.File

/** The shipped prompt assets, read from `src/main/assets` (JVM tests run in the module directory), without Android. */
internal object TestPrompts {
    val real: AssistantPrompts by lazy { AssistantPrompts.load { File("src/main/assets/$it").inputStream() } }
    val lazy: Lazy<AssistantPrompts> = lazy { real }
}
