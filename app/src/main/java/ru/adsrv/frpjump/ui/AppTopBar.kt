package ru.adsrv.frpjump.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import ru.adsrv.frpjump.R

/** The bar of a screen opened from a card (web page): back, a title with a muted line under it, actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(title: String, subtitle: String?, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
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
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
