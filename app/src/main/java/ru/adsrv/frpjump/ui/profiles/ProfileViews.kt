package ru.adsrv.frpjump.ui.profiles

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.profiles.PROFILE_COLORS
import ru.adsrv.frpjump.ui.AppTheme

/** A profile's letter on a tile of its colour. */
@Composable
fun ProfileTile(letter: String, color: String, size: Dp = 40.dp) {
    val colors = AppTheme.colors
    Box(
        Modifier
            .size(size)
            .background(colors.profile(color), RoundedCornerShape(size * 0.3f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, color = colors.onProfile, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.4f).sp)
    }
}

/** The header of the device list: the current profile's colour, name and server; tap for the profile list. */
@Composable
fun ProfileSwitcher(item: ProfileItem?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    val label = stringResource(R.string.switch_profile)
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = colors.card,
        border = BorderStroke(1.dp, colors.outline),
        modifier = modifier
            .heightIn(min = 44.dp)
            .semantics { contentDescription = label },
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(12.dp)
                    .background(item?.let { colors.profile(it.color) } ?: colors.faint, CircleShape),
            )
            Text(
                item?.name.orEmpty(),
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            // A profile still named after its host needs no second copy of it.
            item?.server?.takeIf { it.host != item.name }?.let {
                Text(
                    it.host,
                    fontSize = 13.sp,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = colors.muted, modifier = Modifier.size(20.dp))
        }
    }
}

/** The palette as round swatches, the chosen one ringed and ticked. */
@Composable
fun ProfileColorPicker(selected: String, onSelect: (String) -> Unit, enabled: Boolean = true) {
    val colors = AppTheme.colors
    val groupLabel = stringResource(R.string.profile_color)
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .selectableGroup()
            .semantics { contentDescription = groupLabel },
    ) {
        PROFILE_COLORS.forEach { name ->
            val chosen = name == selected
            val label = stringResource(colorLabel(name))
            // The chosen one: an ink ring with a gap, so it reads against any swatch.
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .then(if (chosen) Modifier.border(2.5.dp, colors.ink, CircleShape).padding(4.5.dp) else Modifier)
                    .background(colors.profile(name), CircleShape)
                    .selectable(selected = chosen, enabled = enabled, role = Role.RadioButton) { onSelect(name) }
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                if (chosen) Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onProfile, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@StringRes
private fun colorLabel(name: String): Int = when (name) {
    "green" -> R.string.color_green
    "orange" -> R.string.color_orange
    "purple" -> R.string.color_purple
    "blue" -> R.string.color_blue
    "crimson" -> R.string.color_crimson
    else -> R.string.color_grey
}

/** A small rounded label: a count, a state. */
@Composable
fun Chip(text: String, container: Color, content: Color) {
    Text(
        text,
        color = content,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .background(container, CircleShape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/** A dashed outline, as the mockups draw "add" rows. */
fun Modifier.dashedBorder(color: Color, radius: Dp, width: Dp = 1.5.dp): Modifier = drawWithContent {
    drawContent()
    val stroke = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
    )
}
