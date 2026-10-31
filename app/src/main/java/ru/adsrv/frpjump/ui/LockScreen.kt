package ru.adsrv.frpjump.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.adsrv.frpjump.R

/**
 * Over everything while the app is locked: it hides the screens and takes
 * every touch. "Unlock" asks again after the prompt was closed; [message]
 * says why the last attempt did not unlock. Back leaves the app.
 */
@Composable
fun LockScreen(busy: Boolean, message: String?, onUnlock: () -> Unit, onLeave: () -> Unit) {
    val colors = AppTheme.colors
    BackHandler(onBack = onLeave)
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.ground)
            // Nothing below may be tapped through.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .systemBarsPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier
                    .size(64.dp)
                    .background(colors.accentContainer, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Lock, contentDescription = null, tint = colors.onAccentContainer, modifier = Modifier.size(30.dp))
            }
            Text(stringResource(R.string.lock_title), fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(stringResource(R.string.lock_text), fontSize = 15.sp, lineHeight = 21.sp, color = colors.muted, textAlign = TextAlign.Center)
            message?.let { Text(it, fontSize = 14.sp, lineHeight = 20.sp, color = colors.error, textAlign = TextAlign.Center) }
            Button(
                onClick = onUnlock,
                enabled = !busy,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.strong,
                    contentColor = colors.onStrong,
                    disabledContainerColor = colors.strong,
                    disabledContentColor = colors.onStrong,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = colors.onStrong, strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.lock_unlock), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
