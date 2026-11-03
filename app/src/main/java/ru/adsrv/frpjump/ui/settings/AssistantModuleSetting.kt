package ru.adsrv.frpjump.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ui.AppTheme

private class AssistantSaveOwner { var live = true; var pending: Any? = null }

/** Checked value comes only from committed settings; disposed/duplicate save callbacks own nothing. */
@Composable
internal fun AssistantModuleSetting(enabled: Boolean, onSave: (Boolean, (String?) -> Unit) -> Unit) {
    val owner = remember { AssistantSaveOwner() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(owner) { onDispose { owner.live = false; owner.pending = null } }
    Column {
        SwitchRow(
            tile = { GlyphTile("LLM") },
            label = stringResource(R.string.assistant_enabled),
            hint = stringResource(R.string.assistant_foundation_hint),
            checked = enabled,
            busy = busy,
            onChange = { next ->
                if (!busy) {
                    val request = Any()
                    owner.pending = request
                    busy = true
                    error = null
                    onSave(next) { failure ->
                        if (owner.live && owner.pending === request) {
                            owner.pending = null
                            busy = false
                            error = failure
                        }
                    }
                }
            },
        )
        error?.let { Text(it, color = AppTheme.colors.error) }
    }
}
