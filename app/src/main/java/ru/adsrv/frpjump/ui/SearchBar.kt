package ru.adsrv.frpjump.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.adsrv.frpjump.R

/**
 * A screen's top bar while searching (Devices, the MQTT explorer): a
 * field in the profile switcher's shape, focused with the keyboard up, and
 * ✕. [label] names the field for TalkBack, [hint] shows while it is empty.
 */
@Composable
fun SearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit, focus: Boolean, onFocused: () -> Unit, label: String, hint: String) {
    val colors = AppTheme.colors
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // [focus]: just opened by the magnifier; once done, a return to the screen leaves the keyboard down.
    LaunchedEffect(focus) {
        if (!focus) return@LaunchedEffect
        runCatching { requester.requestFocus() }
        keyboard?.show()
        onFocused()
    }
    Row(
        Modifier
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = CircleShape,
            color = colors.card,
            border = BorderStroke(1.dp, colors.accent),
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 44.dp),
        ) {
            Row(
                Modifier.padding(start = 14.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(SearchGlyph, contentDescription = null, tint = colors.muted, modifier = Modifier.size(20.dp))
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 16.sp, color = colors.ink),
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(requester)
                        .semantics { contentDescription = label },
                    decorationBox = { field ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) Text(hint, fontSize = 16.sp, color = colors.muted, maxLines = 1)
                            field()
                        }
                    },
                )
                IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_close), tint = colors.muted, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
