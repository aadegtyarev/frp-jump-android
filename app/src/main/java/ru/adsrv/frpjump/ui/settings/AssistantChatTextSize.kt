package ru.adsrv.frpjump.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.settings.ChatTextSize
import ru.adsrv.frpjump.ui.AppTheme

/** The chat's own text multiplier (1.0 = the system size), provided by the chat screen from Settings and read by [ChatFeedScale]. */
internal val LocalChatTextScale = compositionLocalOf { 1f }

/** Scales the text inside by the chat setting on top of the system font scale; dp sizes, the top bar, the composer and dialogs are untouched. */
@Composable
internal fun ChatFeedScale(content: @Composable () -> Unit) = ChatTextScaled(LocalChatTextScale.current, content)

@Composable
private fun ChatTextScaled(scale: Float, content: @Composable () -> Unit) {
    val current = LocalDensity.current
    val scaled = remember(current.density, current.fontScale, scale) { Density(density = current.density, fontScale = current.fontScale * scale) }
    CompositionLocalProvider(LocalDensity provides scaled, content = content)
}

/**
 * Settings › Chat › Text size: a stepped slider with the value, a small and a large "A" at its ends, and a live preview below
 * (a muted user line and an answer line) at the chosen size. The slider announces the value ("130%"); every change is saved at once.
 */
@Composable
internal fun AssistantChatTextSizeSetting(percent: Int, onChange: (Int) -> Unit) {
    val steps = ChatTextSize.STEPS
    val index = steps.indexOf(ChatTextSize.snap(percent.toDouble())).coerceAtLeast(0)
    val label = stringResource(R.string.chat_text_size)
    val value = stringResource(R.string.chat_text_size_value, steps[index])
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = AppTheme.colors.ink)
            Text(value, color = AppTheme.colors.muted, modifier = Modifier.testTag("chat_text_size_value"))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.chat_text_size_small), fontSize = 12.sp, color = AppTheme.colors.muted, modifier = Modifier.clearAndSetSemantics {})
            // The Slider itself is 44 dp tall; the row around it makes the touch area a full 48 dp.
            Box(Modifier.weight(1f).heightIn(min = 48.dp).testTag("chat_text_size_track"), contentAlignment = Alignment.Center) {
                Slider(
                    value = index.toFloat(), onValueChange = { onChange(steps[it.toInt().coerceIn(0, steps.lastIndex)]) },
                    valueRange = 0f..steps.lastIndex.toFloat(), steps = steps.size - 2,
                    modifier = Modifier.fillMaxWidth().testTag("chat_text_size_slider").semantics { contentDescription = label; stateDescription = value },
                )
            }
            Text(stringResource(R.string.chat_text_size_large), fontSize = 22.sp, color = AppTheme.colors.muted, modifier = Modifier.clearAndSetSemantics {})
        }
        ChatTextScaled(ChatTextSize.factor(steps[index])) {
            Column(Modifier.testTag("chat_text_size_preview"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.chat_text_size_preview_user), color = AppTheme.colors.muted)
                Text(stringResource(R.string.chat_text_size_preview_answer), style = MaterialTheme.typography.bodyLarge, color = AppTheme.colors.ink)
            }
        }
    }
}
