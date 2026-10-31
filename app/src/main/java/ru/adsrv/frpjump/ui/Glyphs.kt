package ru.adsrv.frpjump.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
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
