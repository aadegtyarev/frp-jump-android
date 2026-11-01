package ru.adsrv.frpjump.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.adsrv.frpjump.R

/**
 * The bar of a screen opened from a card (web page): back, a title with a
 * muted line under it, actions. With [wrapTitle] the title is identifying
 * text that is never cut: it takes up to two lines, and a name longer than
 * that is drawn smaller until it fits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(title: String, subtitle: String?, onBack: () -> Unit, wrapTitle: Boolean = false, actions: @Composable RowScope.() -> Unit = {}) {
    val colors = AppTheme.colors
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colors.ground,
            titleContentColor = colors.ink,
            navigationIconContentColor = colors.ink,
            actionIconContentColor = colors.muted,
        ),
        title = {
            Column {
                if (wrapTitle) WrappedTitle(title) else Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, fontSize = 12.sp, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
        },
        actions = actions,
    )
}

/** Smallest size a [WrappedTitle] shrinks to. */
private const val MIN_TITLE_SP = 11

/** The title on up to two lines; when it would need more, a step smaller each layout until it fits (no ellipsis). */
@Composable
private fun WrappedTitle(title: String) {
    var size by remember(title) { mutableIntStateOf(17) }
    Text(
        title,
        fontSize = size.sp,
        lineHeight = (size + 3).sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
        overflow = TextOverflow.Clip,
        onTextLayout = { if (it.hasVisualOverflow && size > MIN_TITLE_SP) size-- },
    )
}

/**
 * A thin bar for screens where every pixel goes to the content (the web
 * page): 44 dp, back, one line of title, actions; under the status bar.
 * With [onClose], a long press on back closes at once (TalkBack: "Close"
 * among the arrow's actions), as back itself may step inside the screen.
 */
@Composable
fun CompactTopBar(title: String, onBack: () -> Unit, onClose: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.ground)
            // The status bar on top, and a cutout or the navigation bar at a side in landscape.
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onClose == null) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = colors.ink)
            }
        } else {
            BackOrClose(onBack, onClose)
        }
        Text(
            title,
            color = colors.ink,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        CompositionLocalProvider(LocalContentColor provides colors.muted) { actions() }
    }
}

/** The bar's back arrow that also closes: tap is [onBack], long press (and TalkBack's "Close" action) is [onClose]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BackOrClose(onBack: () -> Unit, onClose: () -> Unit) {
    val back = stringResource(R.string.back)
    val close = stringResource(R.string.close)
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .combinedClickable(role = Role.Button, onClick = onBack, onLongClickLabel = close, onLongClick = onClose)
            .semantics {
                contentDescription = back
                customActions = listOf(CustomAccessibilityAction(close) { onClose(); true })
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = AppTheme.colors.ink)
    }
}
