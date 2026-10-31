package ru.adsrv.frpjump.mqtt

/**
 * A topic as its levels, taken as is: `/devices/x` is `""`, `devices`, `x`
 * and differs from `devices/x`; `/` is two empty levels. The root, the
 * whole tree, has no levels and formats as an empty string, which no MQTT
 * topic can be.
 */
data class MqttPath(val levels: List<String>) {
    val isRoot: Boolean get() = levels.isEmpty()

    /** The last level; empty for the root. */
    val name: String get() = levels.lastOrNull() ?: ""

    /** The level this one is in; the root for a first level and for the root itself. */
    val parent: MqttPath get() = if (isRoot) this else MqttPath(levels.dropLast(1))

    fun child(level: String): MqttPath = MqttPath(levels + level)

    /** [other] at or below this path, levels compared exactly. */
    fun contains(other: MqttPath): Boolean =
        other.levels.size >= levels.size && other.levels.subList(0, levels.size) == levels

    /** This path's levels below [base], which must contain it. */
    fun relativeTo(base: MqttPath): MqttPath {
        require(base.contains(this)) { "$this is not under $base" }
        return MqttPath(levels.drop(base.levels.size))
    }

    /** The topic text: levels joined by `/`. */
    fun format(): String = levels.joinToString("/")

    override fun toString(): String = format()

    companion object {
        val ROOT = MqttPath(emptyList())

        /** [topic] split at every `/`; an empty text is the root. */
        fun parse(topic: String): MqttPath = if (topic.isEmpty()) ROOT else MqttPath(topic.split('/'))
    }
}
