package ru.adsrv.frpjump.profiles

import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.ports.LocalPortStore
import ru.adsrv.frpjump.store.TextStorage
import ru.adsrv.frpjump.web.WebSiteMemory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The upgrade in progress: the id and name the v1 data gets. */
@Serializable
private data class MigrationPlan(@SerialName("profile_id") val profileId: String, val name: String)

/**
 * Makes v1's single enrollment and everything kept for it the first
 * profile, on the first start of a version with profiles:
 *
 * 1. Write [ProfileFiles.MIGRATION] with a new profile id (atomically).
 * 2. Move the per-profile files ([ProfileFiles.PER_PROFILE]) into
 *    `profiles/<id>/`, each by an atomic rename; nothing is copied or
 *    re-sealed: the profile keeps v1's Keystore key ([LEGACY_KEY_ALIAS]).
 * 3. Rewrite the keys of the shared local port map and web site memory to
 *    name the profile.
 * 4. Add the profile to the index, as the current one.
 * 5. Delete the marker.
 *
 * Every step can run again: a moved file is no longer there to move, a
 * rewritten key is left alone, a listed profile is not added twice. A crash
 * anywhere leaves the marker, and the next start finishes from it with the
 * same id. Until step 4 the enrollment is in one of its two places, never
 * in neither.
 *
 * v1 data without an enrollment (the phone was removed and not enrolled
 * again) still becomes a profile, one without credentials: enrolling fills
 * it in and keeps its ports, logins and host keys, as v1 did.
 */
internal class LegacyMigration(
    private val filesDir: File,
    private val storageFor: (File) -> TextStorage,
    private val newId: () -> String,
) {
    private val marker = storageFor(File(filesDir, ProfileFiles.MIGRATION))
    private val index = storageFor(File(filesDir, ProfileFiles.INDEX))

    /** True when it moved v1 data (now or finishing an interrupted run). */
    fun run(): Boolean {
        val plan = pending() ?: start() ?: return false
        val dir = File(File(filesDir, ProfileFiles.DIR), plan.profileId)
        dir.mkdirs()
        for (name in ProfileFiles.PER_PROFILE) moveAtomicFile(name, dir)
        rewrite(ProfileFiles.LOCAL_PORTS) { text ->
            val ports = WireJson.decodeFromString<Map<String, Int>>(text)
            val migrated = LocalPortStore.migrateLegacy(ports, plan.profileId)
            if (migrated == ports) null else WireJson.encodeToString(migrated)
        }
        rewrite(ProfileFiles.WEB_SITE) { WebSiteMemory.migrateLegacy(it, plan.profileId) }
        val current = index.read()?.let { WireJson.decodeFromString<ProfileIndex>(it) } ?: ProfileIndex()
        if (current.find(plan.profileId) == null) {
            val profile = Profile(plan.profileId, plan.name, nextProfileColor(current.profiles), LEGACY_KEY_ALIAS)
            index.write(WireJson.encodeToString(current.plus(profile, makeCurrent = current.current == null)))
        }
        marker.delete()
        Log.i(TAG, "v1 data is now profile ${plan.profileId}")
        return true
    }

    private fun pending(): MigrationPlan? = marker.read()?.let { WireJson.decodeFromString<MigrationPlan>(it) }

    /** A plan for v1 data, written down before anything moves; null when there is nothing to upgrade. */
    private fun start(): MigrationPlan? {
        if (index.read() != null) return null
        val legacy = ProfileFiles.PER_PROFILE + ProfileFiles.LOCAL_PORTS + ProfileFiles.WEB_SITE
        if (legacy.none { exists(File(filesDir, it)) }) return null
        val plan = MigrationPlan(newId(), legacyName())
        marker.write(WireJson.encodeToString(plan))
        return plan
    }

    /** The server's host from v1's enrollment (its public part, no Keystore needed); blank without one. */
    private fun legacyName(): String {
        val text = runCatching { storageFor(File(filesDir, ProfileFiles.ENROLLMENT)).read() }.getOrNull() ?: return ""
        val url = runCatching { (WireJson.parseToJsonElement(text) as JsonObject)["control_url"] as? JsonPrimitive }
            .getOrNull()?.content ?: return ""
        return defaultProfileName(url)
    }

    /** Moves [name] and whatever AtomicFile keeps next to it into [dir]; what is gone was moved before. */
    private fun moveAtomicFile(name: String, dir: File) {
        for (suffix in ProfileFiles.ATOMIC_SUFFIXES) {
            val from = File(filesDir, name + suffix)
            if (!from.exists()) continue
            Files.move(from.toPath(), File(dir, name + suffix).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /** Applies [change] to a shared file; null from it, or unreadable content, leaves the file as it is. */
    private fun rewrite(name: String, change: (String) -> String?) {
        val storage = storageFor(File(filesDir, name))
        val text = storage.read() ?: return
        val changed = runCatching { change(text) }.getOrNull() ?: return
        storage.write(changed)
    }

    private fun exists(file: File) = ProfileFiles.ATOMIC_SUFFIXES.any { File(file.path + it).exists() }

    private companion object {
        const val TAG = "LegacyMigration"
    }
}
