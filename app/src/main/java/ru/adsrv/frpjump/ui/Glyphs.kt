package ru.adsrv.frpjump.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** A stroked 24×24 glyph as the mockups draw their icons; tinted where it is used. */
private fun glyph(name: String, width: Float = 2f, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = block,
    ).build()

/** As [glyph], drawn from SVG path data [d]. */
internal fun pathGlyph(name: String, d: String, width: Float = 1.8f): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
        pathData = addPathNodes(d),
        stroke = SolidColor(Color.Black),
        strokeLineWidth = width,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ).build()

/** A rounded rectangle from ([x], [y]) of [w]×[h] with corner [r]. */
private fun PathBuilder.roundRect(x: Float, y: Float, w: Float, h: Float, r: Float) {
    moveTo(x + r, y)
    horizontalLineTo(x + w - r)
    arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = x + w, y1 = y + r)
    verticalLineTo(y + h - r)
    arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = x + w - r, y1 = y + h)
    horizontalLineTo(x + r)
    arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = x, y1 = y + h - r)
    verticalLineTo(y + r)
    arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = x + r, y1 = y)
    close()
}

/** A screen on a stand, as the mockups draw a device. */
val DeviceGlyph: ImageVector = glyph("Device", 1.8f) {
    roundRect(4f, 5f, 16f, 11f, 2f)
    moveTo(8f, 20f)
    horizontalLineTo(16f)
    moveTo(12f, 16f)
    verticalLineTo(20f)
}

/** Two chain links: connections, and "connect". */
val LinkGlyph: ImageVector = glyph("Link") {
    moveTo(10f, 14f)
    arcTo(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 15.7f, y1 = 14f)
    lineTo(18.7f, 11f)
    arcTo(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 13f, y1 = 5.3f)
    lineTo(12f, 6.3f)
    moveTo(14f, 10f)
    arcTo(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 8.3f, y1 = 10f)
    lineTo(5.3f, 13f)
    arcTo(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 11f, y1 = 18.7f)
    lineTo(12f, 17.7f)
}

/** A terminal window with a prompt. */
val TerminalGlyph: ImageVector = glyph("Terminal") {
    roundRect(3f, 4f, 18f, 16f, 2f)
    moveTo(7f, 9f)
    lineTo(10f, 12f)
    lineTo(7f, 15f)
    moveTo(12f, 15f)
    horizontalLineTo(17f)
}

/** A power symbol: "disconnect". */
val PowerGlyph: ImageVector = glyph("Power") {
    moveTo(12f, 3f)
    verticalLineTo(12f)
    moveTo(6.9f, 6.9f)
    arcTo(8f, 8f, 0f, isMoreThanHalf = true, isPositiveArc = false, x1 = 17.1f, y1 = 6.9f)
}

/** Four corners pointing out: "Full screen". */
val FullscreenGlyph: ImageVector = glyph("Fullscreen") {
    moveTo(4f, 9f); verticalLineTo(4f); horizontalLineTo(9f)
    moveTo(15f, 4f); horizontalLineTo(20f); verticalLineTo(9f)
    moveTo(20f, 15f); verticalLineTo(20f); horizontalLineTo(15f)
    moveTo(9f, 20f); horizontalLineTo(4f); verticalLineTo(15f)
}

/** Four corners pointing in: "Exit full screen". */
val FullscreenExitGlyph: ImageVector = glyph("FullscreenExit") {
    moveTo(4f, 9f); horizontalLineTo(9f); verticalLineTo(4f)
    moveTo(15f, 4f); verticalLineTo(9f); horizontalLineTo(20f)
    moveTo(20f, 15f); horizontalLineTo(15f); verticalLineTo(20f)
    moveTo(9f, 20f); verticalLineTo(15f); horizontalLineTo(4f)
}

/** Two overlapping sheets: "Copy". */
val CopyGlyph: ImageVector = glyph("Copy") {
    roundRect(9f, 9f, 11f, 11f, 2f)
    moveTo(5f, 15f); verticalLineTo(6f)
    arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 7f, y1 = 4f)
    horizontalLineTo(15f)
}

/** A clipboard with its clip: "Paste". */
val PasteGlyph: ImageVector = glyph("Paste") {
    roundRect(8f, 2f, 8f, 4f, 1f)
    moveTo(16f, 4f); horizontalLineTo(18f)
    arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 20f, y1 = 6f)
    verticalLineTo(20f)
    arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 18f, y1 = 22f)
    horizontalLineTo(6f)
    arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 4f, y1 = 20f)
    verticalLineTo(6f)
    arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 6f, y1 = 4f)
    horizontalLineTo(8f)
}

/** A plus, for "add" buttons. */
val PlusGlyph: ImageVector = glyph("Plus", 2.4f) {
    moveTo(12f, 5f)
    verticalLineTo(19f)
    moveTo(5f, 12f)
    horizontalLineTo(19f)
}

/** A viewfinder's corners with a scan line: "Scan QR code". */
val ScanGlyph: ImageVector = glyph("Scan") {
    moveTo(4f, 8f)
    verticalLineTo(5f)
    arcTo(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 5f, y1 = 4f)
    horizontalLineTo(8f)
    moveTo(16f, 4f)
    horizontalLineTo(19f)
    arcTo(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 20f, y1 = 5f)
    verticalLineTo(8f)
    moveTo(20f, 16f)
    verticalLineTo(19f)
    arcTo(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 19f, y1 = 20f)
    horizontalLineTo(16f)
    moveTo(8f, 20f)
    horizontalLineTo(5f)
    arcTo(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 4f, y1 = 19f)
    verticalLineTo(16f)
    moveTo(7f, 12f)
    horizontalLineTo(17f)
}

/** A flashlight: the scanner's torch. */
val TorchGlyph: ImageVector = glyph("Torch") {
    moveTo(7f, 3f)
    horizontalLineTo(17f)
    verticalLineTo(7f)
    lineTo(15f, 10f)
    verticalLineTo(20f)
    arcTo(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 14f, y1 = 21f)
    horizontalLineTo(10f)
    arcTo(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 9f, y1 = 20f)
    verticalLineTo(10f)
    lineTo(7f, 7f)
    close()
    moveTo(12f, 13f)
    verticalLineTo(15f)
}

/** A framed landscape with a sun: a picture "From gallery". */
val PictureGlyph: ImageVector = glyph("Picture") {
    roundRect(3f, 4f, 18f, 16f, 2f)
    moveTo(10f, 9f)
    arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 7f, y1 = 9f)
    arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 10f, y1 = 9f)
    moveTo(3f, 17f)
    lineTo(8.5f, 12f)
    lineTo(12f, 15f)
    lineTo(15f, 12.5f)
    lineTo(21f, 17f)
}

/** A house: the root of a tree (the MQTT explorer's path bar). */
val HomeGlyph: ImageVector = glyph("Home", 1.8f) {
    moveTo(4f, 11f)
    lineTo(12f, 4f)
    lineTo(20f, 11f)
    moveTo(6f, 9.5f)
    verticalLineTo(19f)
    arcTo(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 7f, y1 = 20f)
    horizontalLineTo(17f)
    arcTo(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 18f, y1 = 19f)
    verticalLineTo(9.5f)
}

/** A folder: the file screen, and folders in it. */
val FolderGlyph: ImageVector = glyph("Folder", 1.8f) {
    moveTo(3f, 7f)
    arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 5f, y1 = 5f)
    horizontalLineTo(9.5f)
    lineTo(11.5f, 7f)
    horizontalLineTo(19f)
    arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 21f, y1 = 9f)
    verticalLineTo(17f)
    arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 19f, y1 = 19f)
    horizontalLineTo(5f)
    arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 3f, y1 = 17f)
    close()
}

/** A sheet with a folded corner: a file. */
val FileGlyph: ImageVector = glyph("File", 1.8f) {
    moveTo(14f, 3f)
    horizontalLineTo(7f)
    arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 5f, y1 = 5f)
    verticalLineTo(19f)
    arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 7f, y1 = 21f)
    horizontalLineTo(17f)
    arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 19f, y1 = 19f)
    verticalLineTo(8f)
    close()
    moveTo(14f, 3f)
    verticalLineTo(8f)
    horizontalLineTo(19f)
}

/** A sheet with a question in it: a device, socket or a broken link's missing target. */
val OtherFileGlyph: ImageVector = glyph("OtherFile", 1.8f) {
    moveTo(7f, 3f)
    horizontalLineTo(17f)
    arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 19f, y1 = 5f)
    verticalLineTo(19f)
    arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 17f, y1 = 21f)
    horizontalLineTo(7f)
    arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 5f, y1 = 19f)
    verticalLineTo(5f)
    arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 7f, y1 = 3f)
    close()
    moveTo(10f, 10f)
    arcTo(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 12f, y1 = 12f)
    verticalLineTo(13.5f)
    moveTo(12f, 17f)
    verticalLineTo(17.01f)
}

/** A box with a lid and a handle: an archive. */
val ArchiveGlyph: ImageVector = glyph("Archive", 1.8f) {
    roundRect(3f, 4f, 18f, 5f, 1f)
    moveTo(5f, 9f)
    verticalLineTo(18f)
    arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 7f, y1 = 20f)
    horizontalLineTo(17f)
    arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 19f, y1 = 18f)
    verticalLineTo(9f)
    moveTo(10f, 13f)
    horizontalLineTo(14f)
}

/** A sheet with a folded corner and a prompt: an executable file. */
val ExecutableGlyph: ImageVector = glyph("Executable", 1.8f) {
    moveTo(14f, 3f)
    horizontalLineTo(7f)
    arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 5f, y1 = 5f)
    verticalLineTo(19f)
    arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 7f, y1 = 21f)
    horizontalLineTo(17f)
    arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 19f, y1 = 19f)
    verticalLineTo(8f)
    close()
    moveTo(14f, 3f)
    verticalLineTo(8f)
    horizontalLineTo(19f)
    moveTo(8.5f, 12f)
    lineTo(10.5f, 14f)
    lineTo(8.5f, 16f)
    moveTo(12.5f, 16f)
    horizontalLineTo(15.5f)
}

/** Two chain links pulled apart, with marks at the break: a link whose target is gone. */
val BrokenLinkGlyph: ImageVector = glyph("BrokenLink") {
    moveTo(13.5f, 6.5f)
    lineTo(14f, 6f)
    arcTo(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 19.7f, y1 = 11.7f)
    lineTo(17.5f, 13.9f)
    moveTo(10.5f, 17.5f)
    lineTo(10f, 18f)
    arcTo(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 4.3f, y1 = 12.3f)
    lineTo(6.5f, 10.1f)
    moveTo(8f, 3f)
    verticalLineTo(5f)
    moveTo(3f, 8f)
    horizontalLineTo(5f)
    moveTo(16f, 21f)
    verticalLineTo(19f)
    moveTo(21f, 16f)
    horizontalLineTo(19f)
}

/** A magnifier: search. */
val SearchGlyph: ImageVector = glyph("Search") {
    moveTo(4f, 10.5f)
    arcTo(6.5f, 6.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 17f, y1 = 10.5f)
    arcTo(6.5f, 6.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 4f, y1 = 10.5f)
    close()
    moveTo(15.2f, 15.2f)
    lineTo(20f, 20f)
}

/** An arrow down onto a tray: download to the phone. */
val DownloadGlyph: ImageVector = glyph("Download") {
    moveTo(12f, 4f)
    verticalLineTo(15f)
    moveTo(7f, 10f)
    lineTo(12f, 15f)
    lineTo(17f, 10f)
    moveTo(5f, 19.5f)
    horizontalLineTo(19f)
}

/** Three joined dots: share. */
val ShareGlyph: ImageVector = glyph("Share", 1.8f) {
    circle(18f, 5.5f, 2.5f)
    circle(6f, 12f, 2.5f)
    circle(18f, 18.5f, 2.5f)
    moveTo(8.2f, 10.8f)
    lineTo(15.8f, 6.7f)
    moveTo(8.2f, 13.2f)
    lineTo(15.8f, 17.3f)
}

/** A ticked box with another behind it: select all. */
val SelectAllGlyph: ImageVector = glyph("SelectAll", 1.8f) {
    roundRect(7f, 7f, 13f, 13f, 2.5f)
    moveTo(4f, 15f)
    verticalLineTo(6.5f)
    arcTo(2.5f, 2.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 6.5f, y1 = 4f)
    horizontalLineTo(15f)
    moveTo(10.5f, 13.5f)
    lineTo(12.5f, 15.5f)
    lineTo(16.5f, 11f)
}

/** A circle at ([x], [y]) of radius [r], as two arcs. */
private fun PathBuilder.circle(x: Float, y: Float, r: Float) {
    moveTo(x - r, y)
    arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = x + r, y1 = y)
    arcTo(r, r, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = x - r, y1 = y)
    close()
}

/** A tick: done. */
val DoneGlyph: ImageVector = glyph("Done", 2.2f) {
    moveTo(5f, 12.5f)
    lineTo(10f, 17.5f)
    lineTo(19f, 7f)
}

/** A triangle with "!": mind this. */
val WarnGlyph: ImageVector = glyph("Warn", 1.8f) {
    moveTo(12f, 4f)
    lineTo(21f, 19.5f)
    horizontalLineTo(3f)
    close()
    moveTo(12f, 10f)
    verticalLineTo(13.5f)
    moveTo(12f, 16.5f)
    verticalLineTo(16.51f)
}

/** A circle with "i": for your information. */
val InfoGlyph: ImageVector = glyph("Info", 1.8f) {
    moveTo(3.5f, 12f)
    arcTo(8.5f, 8.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 20.5f, y1 = 12f)
    arcTo(8.5f, 8.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 3.5f, y1 = 12f)
    close()
    moveTo(12f, 11f)
    verticalLineTo(16f)
    moveTo(12f, 8f)
    verticalLineTo(8.01f)
}

/** A push pin: a local port fixed to its connection. */
val PinGlyph: ImageVector = glyph("Pin", 1.8f) {
    moveTo(9f, 4f)
    horizontalLineTo(15f)
    moveTo(10f, 4f)
    verticalLineTo(10f)
    lineTo(7f, 14f)
    horizontalLineTo(17f)
    lineTo(14f, 10f)
    verticalLineTo(4f)
    moveTo(12f, 14f)
    verticalLineTo(20f)
}

/** A framed picture with a sun and a hill: "Insert image". */
val ImageGlyph: ImageVector = glyph("Image") {
    roundRect(3f, 4f, 18f, 16f, 2f)
    moveTo(9f, 9f); horizontalLineTo(9.01f)
    moveTo(3f, 17f); lineTo(8.5f, 12f); lineTo(13f, 16f); lineTo(16f, 13.5f); lineTo(21f, 18f)
}

/** Sparkles: the assistant, the same mark wherever it appears (bottom bar, Settings, a tunnel's assistant mark). Neutral, no brand mark. */
val AssistantGlyph: ImageVector = pathGlyph(
    "Assistant",
    "M9.94 15.5A2 2 0 0 0 8.5 14.06l-6.14-1.58a.5.5 0 0 1 0-.96L8.5 9.94A2 2 0 0 0 9.94 8.5l1.58-6.14a.5.5 0 0 1 .96 0L14.06 8.5A2 2 0 0 0 15.5 9.94l6.14 1.58a.5.5 0 0 1 0 .96L15.5 14.06a2 2 0 0 0-1.44 1.44l-1.58 6.14a.5.5 0 0 1-.96 0Z" +
        "M20 3v4M22 5h-4M4 17v2M5 18H3",
)

/** Lucide `shield-check` 1.72.0 (ISC, see licenses/Lucide.txt): the chat's "confirmations are on" mark. */
val ShieldCheckGlyph: ImageVector = pathGlyph(
    "ShieldCheck",
    "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z" +
        "M9 12l2 2 4-4",
    width = 2f,
)

/** Lucide `shield-off` 1.72.0 (ISC, see licenses/Lucide.txt): the chat's "confirmations are off" mark, struck through. */
val ShieldOffGlyph: ImageVector = pathGlyph(
    "ShieldOff",
    "M2 2l20 20" +
        "M5 5a1 1 0 0 0-1 1v7c0 5 3.5 7.5 7.67 8.94a1 1 0 0 0 .67.01c2.35-.82 4.48-1.97 5.9-3.71" +
        "M9.309 3.652A12.252 12.252 0 0 0 11.24 2.28a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1v7a9.784 9.784 0 0 1-.08 1.264",
    width = 2f,
)

// --- Lucide 1.72.0 (ISC, see licenses/Lucide.txt): the chat composer's action icons ---

/** Lucide "arrow-up": Send. */
val ArrowUpGlyph: ImageVector = pathGlyph("ArrowUp", "m5 12 7-7 7 7M12 19V5", width = 2f)

/** Lucide "square", filled: Stop. */
val StopGlyph: ImageVector = ImageVector.Builder("Stop", 24.dp, 24.dp, 24f, 24f).addPath(
    pathData = addPathNodes("M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z"),
    fill = SolidColor(Color.Black),
    stroke = SolidColor(Color.Black),
    strokeLineWidth = 2f,
    strokeLineCap = StrokeCap.Round,
    strokeLineJoin = StrokeJoin.Round,
).build()

// --- Lucide 1.72.0 (ISC, see licenses/Lucide.txt): the icons of the settings and assistant-tools rows ---

/** Lucide "wrench": the assistant's tools. */
val WrenchGlyph: ImageVector = pathGlyph(
    "Wrench",
    "M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.106-3.105c.32-.322.863-.22.983.218a6 6 0 0 1-8.259 7.057l-7.91 7.91a1 1 0 0 1-2.999-3l7.91-7.91a6 6 0 0 1 7.057-8.259c.438.12.54.662.219.984z",
)

/** Lucide "lightbulb": "Offer to turn tools on". */
val LightbulbGlyph: ImageVector = pathGlyph(
    "Lightbulb",
    "M15 14c.2-1 .7-1.7 1.5-2.5 1-.9 1.5-2.2 1.5-3.5A6 6 0 0 0 6 8c0 1 .2 2.2 1.5 3.5.7.7 1.3 1.5 1.5 2.5" +
        "M9 18h6" +
        "M10 22h4",
)

/** Lucide "globe": the tool that reads a web page. */
val GlobeGlyph: ImageVector = pathGlyph(
    "Globe",
    "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20" +
        "M12 2a14.5 14.5 0 0 0 0 20a14.5 14.5 0 0 0 0-20" +
        "M2 12h20",
)

/** Lucide "rss" (ISC, see licenses/Lucide.txt): two arcs and a dot, the main action of an MQTT connection (its explorer). */
val RssGlyph: ImageVector = pathGlyph(
    "Rss",
    "M4 11a9 9 0 0 1 9 9M4 4a16 16 0 0 1 16 16M6 19a1 1 0 1 1-2 0a1 1 0 1 1 2 0",
)

/** Lucide "keyboard": typing into a terminal. */
val KeyboardGlyph: ImageVector = pathGlyph(
    "Keyboard",
    "M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z" +
        "M6 8h.01M10 8h.01M14 8h.01M18 8h.01M8 12h.01M12 12h.01M16 12h.01M7 16h10",
)

/** Lucide "chevrons-right": a command, apart from the framed [TerminalGlyph] (the SSH-command tool). */
val CommandGlyph: ImageVector = pathGlyph(
    "Command",
    "m6 17 5-5-5-5M13 17l5-5-5-5",
)

/** Lucide "clock": for the event-waits tool. */
val ClockGlyph: ImageVector = pathGlyph(
    "Clock",
    "M12 2a10 10 0 1 0 0 20a10 10 0 1 0 0-20" +
        "M12 6v6l4 2",
)

/** Lucide "ethernet-port": the local ports. */
val PortGlyph: ImageVector = pathGlyph(
    "Port",
    "m15 20 3-3h2a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2H4a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h2l3 3z" +
        "M6 8v1M10 8v1M14 8v1M18 8v1",
)

/** Lucide "history": the terminal's history. */
val HistoryGlyph: ImageVector = pathGlyph(
    "History",
    "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8" +
        "M3 3v5h5" +
        "M12 7v5l4 2",
)

/** Lucide "server": the assistant's provider. */
val ServerGlyph: ImageVector = pathGlyph(
    "Server",
    "M4 2h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z" +
        "M4 14h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2z" +
        "M6 6h.01M6 18h.01",
)

/** Lucide "bot": the assistant module. */
val BotGlyph: ImageVector = pathGlyph(
    "Bot",
    "M12 8V4H8" +
        "M6 8h12a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z" +
        "M2 14h2M20 14h2M15 13v2M9 13v2",
)

/** Lucide "pencil": the owner's instructions. */
val PencilGlyph: ImageVector = pathGlyph(
    "Pencil",
    "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z" +
        "m15 5 4 4",
)

// --- Lucide 1.72.0 (ISC, see licenses/Lucide.txt): one icon per row of Settings and of its Assistant screen (R39) ---

/** Lucide "arrow-left-right": the direct connection. */
val ArrowLeftRightGlyph: ImageVector = pathGlyph(
    "ArrowLeftRight",
    "M8 3 4 7l4 4M4 7h16m-4 14 4-4-4-4M20 17H4",
    width = 2f,
)

/** Lucide "terminal": direct input into the terminal (the framed [TerminalGlyph] stays where it is used). */
val LucideTerminalGlyph: ImageVector = pathGlyph("LucideTerminal", "M12 19h8M4 17l6-6-6-6", width = 2f)

/** Lucide "inbox": incoming MQTT messages. */
val InboxGlyph: ImageVector = pathGlyph(
    "Inbox",
    "M22 12h-6l-2 3h-4l-2-3H2" +
        "M5.45 5.11 2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.45-6.89A2 2 0 0 0 16.76 4H7.24a2 2 0 0 0-1.79 1.11z",
    width = 2f,
)

/** Lucide "database": MQTT storage. */
val DatabaseGlyph: ImageVector = pathGlyph(
    "Database",
    "M3 5a9 3 0 1 0 18 0 9 3 0 1 0-18 0" +
        "M3 5v14a9 3 0 0 0 18 0V5" +
        "M3 12a9 3 0 0 0 18 0",
    width = 2f,
)

/** Lucide "puzzle": MQTT plugins. */
val PuzzleGlyph: ImageVector = pathGlyph(
    "Puzzle",
    "M15.39 4.39a1 1 0 0 0 1.68-.474 2.5 2.5 0 1 1 3.014 3.015 1 1 0 0 0-.474 1.68l1.683 1.682a2.414 2.414 0 0 1 0 3.414L19.61 15.39a1 1 0 0 1-1.68-.474 2.5 2.5 0 1 0-3.014 3.015 1 1 0 0 1 .474 1.68l-1.683 1.682a2.414 2.414 0 0 1-3.414 0L8.61 19.61a1 1 0 0 0-1.68.474 2.5 2.5 0 1 1-3.014-3.015 1 1 0 0 0 .474-1.68l-1.683-1.682a2.414 2.414 0 0 1 0-3.414L4.39 8.61a1 1 0 0 1 1.68.474 2.5 2.5 0 1 0 3.014-3.015 1 1 0 0 1-.474-1.68l1.683-1.682a2.414 2.414 0 0 1 3.414 0z",
    width = 2f,
)

/** Lucide "languages": the language. */
val LanguagesGlyph: ImageVector = pathGlyph(
    "Languages",
    "m5 8 6 6M4 14l6-6 2-3M2 5h12M7 2h1m14 20-5-10-5 10M14 18h6",
    width = 2f,
)

/** Lucide "sun-moon": the theme. */
val SunMoonGlyph: ImageVector = pathGlyph(
    "SunMoon",
    "M12 2v2" +
        "M14.837 16.385a6 6 0 1 1-7.223-7.222c.624-.147.97.66.715 1.248a4 4 0 0 0 5.26 5.259c.589-.255 1.396.09 1.248.715" +
        "M16 12a4 4 0 0 0-4-4M19 5l-1.256 1.256M20 12h2",
    width = 2f,
)

/** Lucide "user": the profile's name and colour. */
val UserGlyph: ImageVector = pathGlyph(
    "User",
    "M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2" +
        "M16 7a4 4 0 1 0-8 0 4 4 0 1 0 8 0",
    width = 2f,
)

/** Lucide "log-out": forget the profile. */
val LogOutGlyph: ImageVector = pathGlyph("LogOut", "m16 17 5-5-5-5M21 12H9M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4", width = 2f)

/** Lucide "info": the version row (the own [InfoGlyph] stays where it is used). */
val LucideInfoGlyph: ImageVector = pathGlyph(
    "LucideInfo",
    "M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0M12 16v-4M12 8h.01",
    width = 2f,
)

/** Lucide "file-text": components and licenses. */
val FileTextGlyph: ImageVector = pathGlyph(
    "FileText",
    "M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z" +
        "M14 2v5a1 1 0 0 0 1 1h5M10 9H8M16 13H8M16 17H8",
    width = 2f,
)

/** Lucide "refresh-cw": the server check. */
val RefreshCwGlyph: ImageVector = pathGlyph(
    "RefreshCw",
    "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8M21 3v5h-5M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16M8 16H3v5",
    width = 2f,
)

/** Lucide "triangle-alert": the battery warning. */
val TriangleAlertGlyph: ImageVector = pathGlyph(
    "TriangleAlert",
    "m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3M12 9v4M12 17h.01",
    width = 2f,
)

/** Lucide "fingerprint-pattern": unlock with fingerprint. */
val FingerprintPatternGlyph: ImageVector = pathGlyph(
    "FingerprintPattern",
    "M12 10a2 2 0 0 0-2 2c0 1.02-.1 2.51-.26 4M14 13.12c0 2.38 0 6.38-1 8.88M17.29 21.02c.12-.6.43-2.3.5-3.02" +
        "M2 12a10 10 0 0 1 18-6M2 16h.01M21.8 16c.2-2 .131-5.354 0-6M5 19.5C5.5 18 6 15 6 12a6 6 0 0 1 .34-2" +
        "M8.65 22c.21-.66.45-1.32.57-2M9 6.8a6 6 0 0 1 9 5.2v2",
    width = 2f,
)

/** Lucide "timer": lock after. */
val TimerGlyph: ImageVector = pathGlyph(
    "Timer",
    "M10 2h4M12 14l3-3M20 14a8 8 0 1 1-16 0 8 8 0 0 1 16 0",
    width = 2f,
)

/** Lucide "eye-off": hide in recents. */
val EyeOffGlyph: ImageVector = pathGlyph(
    "EyeOff",
    "M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49" +
        "M14.084 14.158a3 3 0 0 1-4.242-4.242" +
        "M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143" +
        "m2 2 20 20",
    width = 2f,
)

/** Lucide "key-round": the SSH keys. */
val KeyRoundGlyph: ImageVector = pathGlyph(
    "KeyRound",
    "M2.586 17.414A2 2 0 0 0 2 18.828V21a1 1 0 0 0 1 1h3a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h1a1 1 0 0 0 1-1v-1a1 1 0 0 1 1-1h.172a2 2 0 0 0 1.414-.586l.814-.814a6.5 6.5 0 1 0-4-4z" +
        "M16.5 7.5h.01",
    width = 2f,
)

/** Lucide "a-large-small": the chat's text size. */
val ALargeSmallGlyph: ImageVector = pathGlyph(
    "ALargeSmall",
    "m15 16 2.536-7.328a1.02 1.02 1 0 1 1.928 0L22 16M15.697 14h5.606M2 16l4.039-9.69a.5.5 0 0 1 .923 0L11 16M3.304 13h6.392",
    width = 2f,
)

/** Lucide "message-square": open the chat. */
val MessageSquareGlyph: ImageVector = pathGlyph(
    "MessageSquare",
    "M22 17a2 2 0 0 1-2 2H6.828a2 2 0 0 0-1.414.586l-2.202 2.202A.71.71 0 0 1 2 21.286V5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2z",
    width = 2f,
)
