package ru.adsrv.frpjump.licenses

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

/**
 * A component bundled in the APK, as Settings › Components and licenses
 * lists it: its [name] (not unique: "Lifecycle ViewModel" is two
 * artifacts), [version], [licenses] and where its [source] is; [id] is
 * its Maven coordinate, or the app's own id for one Gradle does not bring.
 */
data class Component(val id: String, val name: String, val version: String?, val licenses: List<ComponentLicense>, val source: String?)

/**
 * A license of a component: its [title], a [short] one for a switch
 * between several, and the asset with its full text (`licenses/MIT.txt`).
 */
data class ComponentLicense(val title: String, val short: String, val asset: String)

/**
 * The components of [json], the AboutLibraries plugin's
 * `aboutlibraries.json` (the libraries Gradle puts in the APK, and the
 * app's own entries in `app/aboutlibraries/`), by name. Each license is
 * one whose text the app ships ([licenseFor]); a license that names
 * nothing (Termux's POM: "Other", no address) is left out.
 */
fun componentsFrom(json: String): List<Component> {
    val file = JSON.decodeFromString<LibrariesFile>(json)
    return file.libraries
        .map { lib ->
            Component(
                id = lib.uniqueId,
                name = lib.name?.takeIf { it.isNotBlank() } ?: lib.uniqueId,
                version = lib.artifactVersion?.takeIf { it.isNotBlank() },
                licenses = lib.licenses.mapNotNull { key -> file.licenses[key]?.let { licenseFor(key, it) } }.distinct(),
                // The project's page first: app/aboutlibraries/ sets it to the sources of the version used.
                source = lib.website?.takeIf { it.startsWith("http") } ?: lib.scm?.url?.takeIf { it.startsWith("http") },
            )
        }
        .sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
}

/** The license under [key] in the plugin's list as one the app ships the text of; null for one it does not know. */
fun licenseFor(key: String, license: LicenseEntry): ComponentLicense? {
    val url = license.url.orEmpty().lowercase()
    val id = license.spdxId ?: key
    return when {
        id == "Apache-2.0" -> ComponentLicense("Apache License 2.0", "Apache 2.0", "licenses/Apache-2.0.txt")
        id == "MIT" -> ComponentLicense("MIT License", "MIT", "licenses/MIT.txt")
        id == "MIT-0" -> ComponentLicense("MIT No Attribution", "MIT-0", "licenses/MIT-0.txt")
        id == "TextMate-Bundle" -> ComponentLicense("TextMate Bundle License", "TextMate", "licenses/TextMate-Bundle.txt")
        // The grammars' sources and copyright holders, one by one.
        id == "TextMate-Grammars-Notice" -> ComponentLicense("Sources and copyrights", "Copyrights", "textmate/NOTICE.md")
        "lgpl-2.1" in url -> ComponentLicense("GNU LGPL 2.1", "LGPL 2.1", "licenses/LGPL-2.1.txt")
        "epl-2.0" in url -> ComponentLicense("Eclipse Public License 2.0", "EPL 2.0", "licenses/EPL-2.0.txt")
        "bouncycastle" in url -> ComponentLicense("Bouncy Castle Licence (MIT)", "Bouncy Castle", "licenses/BouncyCastle.txt")
        "libyuv" in url -> ComponentLicense("BSD 3-Clause (libyuv, in CameraX)", "BSD 3-Clause", "licenses/BSD-3-Clause-libyuv.txt")
        else -> null
    }
}

/**
 * [text] (a license, wrapped at about 70 columns) as paragraphs for a
 * phone's width: a line goes on the one before it when neither is blank,
 * it does not start a block of its own (a list item, a numbered section,
 * a heading in capitals), the one before is no heading and does not end
 * with ":", and it is indented as the one before (give or take two
 * spaces) or further, under a list item's text.
 */
fun reflow(text: String): String {
    val out = StringBuilder()
    var previous = ""
    var previousItem = false
    for (raw in text.lines()) {
        val line = raw.trimEnd()
        val body = line.trimStart()
        val indent = line.length - body.length
        val previousBody = previous.trimStart()
        val previousIndent = previous.length - previousBody.length
        val item = ITEM.containsMatchIn(body)
        val joins = body.isNotEmpty() && previousBody.isNotEmpty() && !item && !HEADING.matches(body) &&
            !HEADING.matches(previousBody) && !previousBody.endsWith(":") &&
            // A paragraph's first line may stand out by a space or two ("  The licenses for most software…").
            (abs(indent - previousIndent) <= 2 || (previousItem && indent > previousIndent))
        if (out.isNotEmpty()) out.append(if (joins) ' ' else '\n')
        out.append(if (joins) body else line)
        previous = line
        // A list item's text goes on in lines indented under it.
        previousItem = if (joins) previousItem else item
    }
    return out.toString()
}

/** A list item or a numbered section: "- ", "1. ", "(a) ", "b) ". */
private val ITEM = Regex("""^([-*•]\s|\(?[0-9a-z]{1,3}[.)]\s)""")

/** A heading in capitals: "TERMS AND CONDITIONS FOR USE". */
private val HEADING = Regex("""[A-Z0-9 .,:-]{6,}""")

/** A license the plugin's list names with neither an SPDX id nor an address: it says nothing to show. */
fun saysNothing(license: LicenseEntry): Boolean = license.spdxId == null && license.url.isNullOrBlank()

private val JSON = Json { ignoreUnknownKeys = true }

@Serializable
private class LibrariesFile(val libraries: List<LibraryEntry> = emptyList(), val licenses: Map<String, LicenseEntry> = emptyMap())

@Serializable
private class LibraryEntry(
    val uniqueId: String,
    val name: String? = null,
    val artifactVersion: String? = null,
    val website: String? = null,
    val scm: Scm? = null,
    val licenses: List<String> = emptyList(),
)

@Serializable
private class Scm(val url: String? = null)

/** A license in the plugin's list. */
@Serializable
class LicenseEntry(val name: String = "", val url: String? = null, val spdxId: String? = null)
