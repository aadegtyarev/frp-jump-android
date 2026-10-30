package ru.adsrv.frpjump.profiles

import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.store.CredentialStore
import ru.adsrv.frpjump.store.Credentials
import ru.adsrv.frpjump.store.SecretBox
import ru.adsrv.frpjump.store.TextStorage
import java.io.File
import java.util.UUID

/**
 * The profile index (`profiles.json`) and each profile's directory and
 * credentials. Every call reads or writes storage, and the credential
 * stores reach the Keystore: call off the main thread.
 */
class ProfileStore(
    private val filesDir: File,
    /** The secret box sealing one profile's credentials, by its Keystore alias. */
    private val boxFor: (alias: String) -> SecretBox,
    private val storageFor: (File) -> TextStorage,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val indexFile = storageFor(File(filesDir, ProfileFiles.INDEX))
    private var index: ProfileIndex? = null
    private val stores = mutableMapOf<String, CredentialStore>()

    /**
     * Upgrades a v1 layout first ([LegacyMigration]), then reads the index and
     * drops what an interrupted add or remove left behind ([sweepOrphans]).
     */
    @Synchronized
    fun load(): ProfileIndex {
        LegacyMigration(filesDir, storageFor, newId).run()
        val loaded = indexFile.read()?.let { WireJson.decodeFromString<ProfileIndex>(it) } ?: ProfileIndex()
        index = loaded
        sweepOrphans(loaded)
        return loaded
    }

    /**
     * Deletes directories under `profiles/` the index does not list, with
     * their Keystore keys: an add that crashed before listing the profile, a
     * remove that crashed before deleting the directory, or a late write
     * (an SSH login, a pinned host key) that recreated a forgotten
     * profile's directory. Never while a v1 upgrade is unfinished (its
     * directory is not listed yet), nor without an index file: then nothing
     * is known about what the directories are.
     */
    private fun sweepOrphans(index: ProfileIndex) {
        if (File(filesDir, ProfileFiles.MIGRATION).exists() || indexFile.read() == null) return
        val orphans = File(filesDir, ProfileFiles.DIR).listFiles()?.filter { index.find(it.name) == null }.orEmpty()
        for (dir in orphans) {
            // A Keystore hiccup must not stop the app from starting: the directory
            // stays, so the key is tried again on the next start.
            if (runCatching { boxFor(profileKeyAlias(dir.name)).destroy() }.isSuccess) dir.deleteRecursively()
        }
    }

    @Synchronized
    fun index(): ProfileIndex = index ?: load()

    /** The directory with [id]'s own files. */
    fun dir(id: String): File = File(File(filesDir, ProfileFiles.DIR), id)

    /** [profile]'s enrollment, sealed with its own key. */
    @Synchronized
    fun credentials(profile: Profile): CredentialStore = stores.getOrPut(profile.id) {
        CredentialStore(storageFor(File(dir(profile.id), ProfileFiles.ENROLLMENT)), boxFor(profile.keyAlias))
    }

    /**
     * A new profile for [credentials], with a key of its own, shown from now
     * on. The credentials are stored before the index lists the profile; if
     * listing it fails they are wiped again.
     */
    @Synchronized
    fun add(credentials: Credentials): Profile {
        val id = newId()
        val before = index()
        val profile = Profile(id, defaultProfileName(credentials.controlUrl), nextProfileColor(before.profiles), profileKeyAlias(id))
        dir(id).mkdirs()
        val store = credentials(profile)
        store.save(credentials)
        try {
            save(before.plus(profile))
        } catch (e: Exception) {
            store.wipe()
            stores.remove(id)
            dir(id).deleteRecursively()
            throw e
        }
        return profile
    }

    /**
     * Stores [credentials] in [profile], one without any (removed by its
     * server, or v1 data without an enrollment); a blank name becomes the
     * server's host.
     */
    @Synchronized
    fun fill(profile: Profile, credentials: Credentials): Profile {
        credentials(profile).save(credentials)
        val named = if (profile.name.isBlank()) profile.copy(name = defaultProfileName(credentials.controlUrl)) else profile
        if (named != profile) save(index().plus(named, makeCurrent = false))
        return named
    }

    @Synchronized
    fun setCurrent(id: String) = save(index().withCurrent(id))

    /**
     * Forgets [id] for good: its credentials and Keystore key first (nothing
     * secret is left if the rest is interrupted), then the index entry, then
     * the directory. The next profile becomes the current one.
     * [beforeUnlisting] (told whether it is the last profile) runs before the
     * index drops it, for the profile's entries in shared files: an
     * interruption leaves the profile listed, to be forgotten again.
     */
    @Synchronized
    fun remove(id: String, beforeUnlisting: (last: Boolean) -> Unit = {}): ProfileIndex {
        val profile = index().find(id) ?: return index()
        credentials(profile).wipe()
        stores.remove(id)
        val after = index().minus(id)
        beforeUnlisting(after.profiles.isEmpty())
        save(after)
        dir(id).deleteRecursively()
        return after
    }

    private fun save(value: ProfileIndex) {
        indexFile.write(WireJson.encodeToString(value))
        index = value
    }
}
