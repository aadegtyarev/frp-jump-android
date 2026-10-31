package ru.adsrv.frpjump.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.store.TextStorage

/**
 * What the last update check learnt: when GitHub last answered, its ETag
 * and the release it gave (the JSON as is: a "304 Not Modified" to the next
 * check, sent with that ETag, means the same release and does not count
 * against the rate limit), and the newer version the app already told
 * the owner about once.
 */
@Serializable
data class UpdateRecord(
    @SerialName("checked_at") val checkedAt: Long? = null,
    @SerialName("etag") val etag: String? = null,
    @SerialName("release") val release: String? = null,
    @SerialName("announced") val announced: String? = null,
)

/** [UpdateRecord] in one JSON file; an unreadable one is as none. Off the main thread. */
class UpdateRecordStore(private val storage: TextStorage) {
    fun read(): UpdateRecord = runCatching { storage.read()?.let { WireJson.decodeFromString<UpdateRecord>(it) } }.getOrNull() ?: UpdateRecord()

    @Synchronized
    fun write(record: UpdateRecord) = storage.write(WireJson.encodeToString(record))
}

/** How often the app looks for a release by itself; the owner may check any time from Settings. */
const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

/**
 * Whether an automatic check is due at [now]: never checked, a day gone
 * since [checkedAt], or the clock set back before it (else a clock once
 * ahead would stop checks until it is caught up).
 */
fun checkDue(now: Long, checkedAt: Long?): Boolean =
    checkedAt == null || now < checkedAt || now - checkedAt >= CHECK_INTERVAL_MS
