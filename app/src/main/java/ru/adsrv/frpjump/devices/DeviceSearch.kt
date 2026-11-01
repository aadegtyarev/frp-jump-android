package ru.adsrv.frpjump.devices

import java.text.Normalizer
import java.util.Locale

/**
 * [text] as search compares it: NFC, case folded the same whatever the
 * phone's language (Locale.ROOT), so «Щиток» typed decomposed or in
 * capitals still finds it and a Turkish phone folds "I" as everyone does.
 */
fun searchFold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase(Locale.ROOT)

/** Whether [text] contains [query] (already trimmed, not blank), compared as [searchFold] does. */
fun searchMatches(text: String?, query: String): Boolean = text != null && searchFold(text).contains(searchFold(query))

/**
 * The devices whose name or label contains [query], ignoring the spaces
 * around it and case ([searchFold]), in the list's order; all of them for
 * a blank query. Not connection names: "Web", "SSH" repeat across devices
 * and would match most of the list.
 */
fun filterDevices(devices: List<DeviceRow>, query: String): List<DeviceRow> {
    val wanted = query.trim()
    if (wanted.isEmpty()) return devices
    return devices.filter { searchMatches(it.name, wanted) || searchMatches(it.label, wanted) }
}
