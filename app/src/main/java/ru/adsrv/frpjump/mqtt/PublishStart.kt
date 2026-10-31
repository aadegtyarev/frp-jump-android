package ru.adsrv.frpjump.mqtt

/** Bytes of a current value the publish sheet takes as its starting value at most; a larger one starts empty. */
const val PREFILL_MAX_BYTES = 2_048

/**
 * What the publish sheet starts with: the [topic] (cursor at its end), the
 * [payload] (selected whole, so typing replaces it), and which field has
 * the focus: the value ([focusPayload]) or the topic.
 */
data class PublishStart(val topic: String, val payload: String, val focusPayload: Boolean)

/**
 * "Publish…" of the card of [path] holding [value] (null: a branch with no
 * value of its own). A topic with a value starts on the value field with
 * that value as text, when it is UTF-8 and at most [PREFILL_MAX_BYTES];
 * else empty. A branch with no value starts on the topic, a `/name` away
 * from a subtopic.
 */
fun publishFromCard(path: MqttPath, value: TopicValue?): PublishStart =
    PublishStart(path.format(), prefillPayload(value), focusPayload = value != null)

/**
 * "Publish here…" at the level [level] (the explorer's "⋮", an empty
 * level): the level's path with a trailing `/`, so the new topic's name is
 * typed straight after it (`/` for the branch of topics starting with `/`);
 * empty at the root. The focus is on the topic.
 */
fun publishAtLevel(level: MqttPath): PublishStart =
    PublishStart(if (level.isRoot) "" else level.format() + "/", "", focusPayload = false)

/** [value] as text to start the value field with: empty when cleared, empty, not UTF-8 or too large. */
fun prefillPayload(value: TopicValue?): String {
    val payload = value?.takeIf { !it.cleared }?.payload ?: return ""
    if (payload.isEmpty() || payload.size > PREFILL_MAX_BYTES) return ""
    return decodeUtf8(payload) ?: ""
}
