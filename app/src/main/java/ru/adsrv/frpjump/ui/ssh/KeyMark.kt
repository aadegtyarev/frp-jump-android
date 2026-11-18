package ru.adsrv.frpjump.ui.ssh

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ssh.SshKeySlot
import ru.adsrv.frpjump.ssh.colorName
import ru.adsrv.frpjump.ui.AppTheme

/** The key mark's size at the normal font; it grows with the font to [KEY_MARK_MAX] (14 dp to 18 dp), like the text beside it. */
private val KEY_MARK_SIZE = 14.dp
private const val KEY_MARK_MAX_SCALE = 18f / 14f

/**
 * The mark of the key a login used, after a label: a key, blue for the phone's own and purple for the imported one. It is not the profile
 * dot: another shape, and the two keys differ by more than hue, the phone's key has a solid head and the imported key's is a ring.
 * [onTerm]: on the terminal's dark bars (the dark theme's shades in either theme), else in this theme's. With [describe] TalkBack names
 * the key ("phone key" / "imported key"); without, the mark is decorative because the text beside it already says it.
 */
@Composable
fun KeyMark(slot: SshKeySlot, onTerm: Boolean, modifier: Modifier = Modifier, describe: Boolean = true) {
    val colors = AppTheme.colors
    val tint = if (onTerm) colors.termProfile(slot.colorName) else colors.profile(slot.colorName)
    val name = stringResource(if (slot == SshKeySlot.IMPORTED) R.string.key_name_imported else R.string.key_name_phone)
    val size: Dp = KEY_MARK_SIZE * LocalDensity.current.fontScale.coerceIn(1f, KEY_MARK_MAX_SCALE)
    Canvas(
        modifier
            .size(size)
            .testTag(if (slot == SshKeySlot.IMPORTED) "key_mark_imported" else "key_mark_phone")
            .clearAndSetSemantics { if (describe) contentDescription = name },
    ) { drawKey(slot, tint) }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawKey(slot: SshKeySlot, tint: Color) {
    // A 24-unit grid: the head on the left, the shaft to the right, two teeth.
    val u = size.width / 24f
    val line = 2.2f * u
    val head = Offset(7f * u, 12f * u)
    if (slot == SshKeySlot.IMPORTED) drawCircle(tint, radius = 4.6f * u, center = head, style = Stroke(width = line))
    else drawCircle(tint, radius = 5.4f * u, center = head)
    val shaftFrom = if (slot == SshKeySlot.IMPORTED) 11.6f else 11f
    drawLine(tint, Offset(shaftFrom * u, 12f * u), Offset(22f * u, 12f * u), strokeWidth = line, cap = StrokeCap.Round)
    drawLine(tint, Offset(17.5f * u, 12f * u), Offset(17.5f * u, 17f * u), strokeWidth = line, cap = StrokeCap.Round)
    drawLine(tint, Offset(21.5f * u, 12f * u), Offset(21.5f * u, 16f * u), strokeWidth = line, cap = StrokeCap.Round)
}

/** The words of a session's key: "Logged in with the phone key". */
@Composable
fun keyUsedText(slot: SshKeySlot): String = stringResource(if (slot == SshKeySlot.IMPORTED) R.string.key_used_imported else R.string.key_used_phone)
