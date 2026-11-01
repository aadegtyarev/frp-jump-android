package ru.adsrv.frpjump.licenses

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where the list of frpc's Go modules is in the assets; scripts/build-frpc.sh writes it with the binary. */
const val FRPC_MODULES_ASSET = "licenses/frpc/modules.json"

/** The Go standard library's entry in the list. */
const val GO_STD = "std"

/** MPL-2.0: the one license among frpc's modules that asks for where the source is. */
const val MPL = "MPL-2.0"

/**
 * frpc's Go modules from [json] (`assets/licenses/frpc/modules.json`):
 * [frp], the frp version the list was made from, and the [modules] built
 * into the binary as components (the standard library first, then by
 * path), frp itself left out: it is the component they are listed under.
 */
class FrpcModules(val frp: String, val go: String, val modules: List<Component>)

fun frpcModulesFrom(json: String): FrpcModules {
    val file = JSON.decodeFromString<ModulesFile>(json)
    val modules = file.modules
        .filter { it.path != FRP_MODULE }
        .map { m ->
            val code = m.replacedBy
            Component(
                id = m.path,
                name = when {
                    m.path == GO_STD -> "Go standard library"
                    // frp's go.mod swaps the module for a fork: the fork's code is the one inside.
                    code != null -> "${m.path} → ${code.path}"
                    else -> m.path
                },
                version = code?.version ?: m.version,
                licenses = moduleLicenses(m.licenses),
                source = m.source.takeIf { it.startsWith("https://") },
            )
        }
        .sortedWith(compareBy({ it.id != GO_STD }, { it.id.lowercase() }))
    return FrpcModules(file.frp, file.go, modules)
}

/**
 * A module's license files as the screen switches between them: one per
 * distinct text (k8s.io/utils carries Go's BSD three times), a short label
 * numbered where two would read the same (pion: MIT in LICENSE and in
 * LICENSES/MIT.txt). A license id the app has no title for stays as it is.
 */
private fun moduleLicenses(files: List<ModuleLicense>): List<ComponentLicense> {
    val distinct = files.distinctBy { it.text }
    val shorts = mutableMapOf<String, Int>()
    return distinct.map { f ->
        val short = f.ids.joinToString(" + ") { shortTitle(it) }
        val n = (shorts[short] ?: 0) + 1
        shorts[short] = n
        ComponentLicense(
            title = f.ids.joinToString(" + ") { goLicenseTitle(it) ?: it } + if (f.file.contains('/')) " (${f.file})" else "",
            short = if (n == 1) short else "$short $n",
            asset = f.text,
            ids = f.ids,
        )
    }
}

/** The title of a license id in frpc's list; null for one the app does not know (ComponentsTest fails then). */
fun goLicenseTitle(id: String): String? = when (id) {
    "Apache-2.0" -> "Apache License 2.0"
    "MIT" -> "MIT License"
    "BSD-2-Clause" -> "BSD 2-Clause"
    "BSD-3-Clause" -> "BSD 3-Clause"
    "ISC" -> "ISC License"
    MPL -> "Mozilla Public License 2.0"
    "CC0-1.0" -> "CC0 1.0 Universal"
    "NOTICE" -> "NOTICE"
    else -> null
}

private fun shortTitle(id: String): String = when (id) {
    "Apache-2.0" -> "Apache 2.0"
    "BSD-2-Clause" -> "BSD-2"
    "BSD-3-Clause" -> "BSD-3"
    "MPL-2.0" -> "MPL 2.0"
    "CC0-1.0" -> "CC0"
    else -> id
}

private const val FRP_MODULE = "github.com/fatedier/frp"

private val JSON = Json { ignoreUnknownKeys = true }

@Serializable
private class ModulesFile(val frp: String, val go: String = "", val modules: List<ModuleEntry> = emptyList())

@Serializable
private class ModuleEntry(
    val path: String,
    val version: String,
    val replacedBy: ModuleRef? = null,
    val source: String = "",
    val licenses: List<ModuleLicense> = emptyList(),
)

@Serializable
private class ModuleRef(val path: String, val version: String)

@Serializable
private class ModuleLicense(val ids: List<String> = emptyList(), val file: String = "", val text: String)
