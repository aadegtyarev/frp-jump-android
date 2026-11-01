package ru.adsrv.frpjump.devices

/** The server's fixed icon set; [wire] is the value in `icon` and `kind`. */
enum class DeviceIcon(val wire: String) {
    LINUX("linux"),
    WIRENBOARD("wirenboard"),
    SERVER("server"),
    PC("pc"),
    LAPTOP("laptop"),
    ROUTER("router"),
    SBC("sbc"),
    CAMERA("camera"),
    HOME("home"),
    PHONE("phone"),
    ;

    companion object {
        /** Null for a value this app version does not know. */
        fun fromWire(value: String): DeviceIcon? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * The icon a device shows: the owner's choice [icon], else the [kind] the
 * device reports. Null means the generic device glyph: neither is set, or
 * the one that applies is a value this app does not know. An unknown
 * choice of the owner still wins over the detected kind.
 */
fun shownIcon(icon: String?, kind: String?): DeviceIcon? =
    if (icon != null) DeviceIcon.fromWire(icon) else kind?.let(DeviceIcon::fromWire)
