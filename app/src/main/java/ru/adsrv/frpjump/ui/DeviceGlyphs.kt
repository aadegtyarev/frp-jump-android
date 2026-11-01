package ru.adsrv.frpjump.ui

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.devices.DeviceIcon

/** Tux, the Linux penguin. */
private val LinuxGlyph = pathGlyph(
    "Linux",
    "M12 3a3.3 3.3 0 0 0-3.3 3.3v2.3C8.7 10.4 6 12.4 6 16c0 1.9.8 3.2 2 4h8c1.2-.8 2-2.1 2-4 0-3.6-2.7-5.6-2.7-7.4V6.3A3.3 3.3 0 0 0 12 3z" +
        "M10 14.3c0-1.2.9-2.1 2-2.1s2 .9 2 2.1V17c0 .9-.9 1.5-2 1.5s-2-.6-2-1.5zM10.9 8.2l1.1.8 1.1-.8M6 20.5h4.5M13.5 20.5H18",
)

/** A DIN-rail module, as a Wiren Board controller: terminals top and bottom, the rail through it. */
private val WirenBoardGlyph = pathGlyph(
    "WirenBoard",
    "M8 3h8a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM3 12h3M18 12h3" +
        "M9 6.5h.01M12 6.5h.01M15 6.5h.01M9 17.5h.01M12 17.5h.01M15 17.5h.01M9.5 10.5h5v3h-5z",
)

/** Two rack units with their lights. */
private val ServerGlyph = pathGlyph(
    "Server",
    "M5 4h14a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z" +
        "M5 14h14a1 1 0 0 1 1 1v4a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1v-4a1 1 0 0 1 1-1zM7.5 7h.01M7.5 17h.01M11 7h5.5M11 17h5.5",
)

/** A monitor beside a tower. */
private val ComputerGlyph = pathGlyph(
    "Computer",
    "M4 5h9a1.5 1.5 0 0 1 1.5 1.5v6A1.5 1.5 0 0 1 13 14H4a1.5 1.5 0 0 1-1.5-1.5v-6A1.5 1.5 0 0 1 4 5zM6 19h5M8.5 14v5" +
        "M17.5 4h3a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-3a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zM18.3 7.5h1.4",
)

/** An open laptop. */
private val LaptopGlyph = pathGlyph(
    "Laptop",
    "M6.5 5h11A1.5 1.5 0 0 1 19 6.5V15H5V6.5A1.5 1.5 0 0 1 6.5 5zM3 15h18l1 2.5a1 1 0 0 1-.9 1.5H2.9a1 1 0 0 1-.9-1.5z",
)

/** A box with two antennas and its lights. */
private val RouterGlyph = pathGlyph(
    "Router",
    "M5 13h14a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2zM7 13 5.5 5M17 13l1.5-8M7 16.5h.01M10 16.5h.01M14 16.5h3",
)

/** A board with a chip, mounting holes and a pin header. */
private val SbcGlyph = pathGlyph(
    "Sbc",
    "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2zM10 9.5h4.5V14H10z" +
        "M6 8h.01M6 16h.01M18 8h.01M18 16h.01M7 5V3M10 5V3M13 5V3M16 5V3",
)

/** A camera with its lens. */
private val CameraGlyph = pathGlyph(
    "Camera",
    "M4 7.5h3L8.5 5h7L17 7.5h3a1 1 0 0 1 1 1V18a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V8.5a1 1 0 0 1 1-1zM15.5 13a3.5 3.5 0 1 1-7 0 3.5 3.5 0 0 1 7 0z",
)

/** A phone with its home bar. */
private val PhoneGlyph = pathGlyph(
    "Phone",
    "M9 2.5h6A2 2 0 0 1 17 4.5v15a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2v-15a2 2 0 0 1 2-2zM11 18.5h2",
)

/** The glyph a device tile draws; null: the generic [DeviceGlyph]. */
val DeviceIcon?.glyph: ImageVector
    get() = when (this) {
        null -> DeviceGlyph
        DeviceIcon.LINUX -> LinuxGlyph
        DeviceIcon.WIRENBOARD -> WirenBoardGlyph
        DeviceIcon.SERVER -> ServerGlyph
        DeviceIcon.PC -> ComputerGlyph
        DeviceIcon.LAPTOP -> LaptopGlyph
        DeviceIcon.ROUTER -> RouterGlyph
        DeviceIcon.SBC -> SbcGlyph
        DeviceIcon.CAMERA -> CameraGlyph
        DeviceIcon.HOME -> HomeGlyph
        DeviceIcon.PHONE -> PhoneGlyph
    }

/** The icon's caption, also what TalkBack reads for it. */
@get:StringRes
val DeviceIcon.caption: Int
    get() = when (this) {
        DeviceIcon.LINUX -> R.string.device_icon_linux
        DeviceIcon.WIRENBOARD -> R.string.device_icon_wirenboard
        DeviceIcon.SERVER -> R.string.device_icon_server
        DeviceIcon.PC -> R.string.device_icon_pc
        DeviceIcon.LAPTOP -> R.string.device_icon_laptop
        DeviceIcon.ROUTER -> R.string.device_icon_router
        DeviceIcon.SBC -> R.string.device_icon_sbc
        DeviceIcon.CAMERA -> R.string.device_icon_camera
        DeviceIcon.HOME -> R.string.device_icon_home
        DeviceIcon.PHONE -> R.string.device_icon_phone
    }
