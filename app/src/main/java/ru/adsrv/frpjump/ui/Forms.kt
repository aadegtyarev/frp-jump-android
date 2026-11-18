package ru.adsrv.frpjump.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.adsrv.frpjump.R

/** The vertical padding of a form's scrolling body. */
val FORM_BODY_PADDING_V = 8.dp

/**
 * A form as the mockups draw it: a title with an optional back arrow,
 * cards that scroll, and one main button at the bottom that shows progress
 * while [busy]. [error] is shown right above the button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormScreen(
    title: String,
    action: String,
    busy: Boolean,
    onAction: () -> Unit,
    onBack: (() -> Unit)?,
    error: String? = null,
    actionEnabled: Boolean = true,
    /** The scroll of the form's body ([FORM_BODY_PADDING_V] is the body's own top inset). */
    scroll: ScrollState = rememberScrollState(),
    /** A strip above the scrolling body, with its own space: the body scrolls below it and never under it. It is told the most height it should take (about 45 % of the form). */
    pinned: (@Composable (maxHeight: androidx.compose.ui.unit.Dp, width: androidx.compose.ui.unit.Dp) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = AppTheme.colors
    var formSize by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val stripMax = with(androidx.compose.ui.platform.LocalDensity.current) { if (formSize.height == 0) androidx.compose.ui.unit.Dp.Infinity else (formSize.height * 0.45f).toDp() }
    val stripWidth = with(androidx.compose.ui.platform.LocalDensity.current) { formSize.width.toDp() - 32.dp }
    Scaffold(
        topBar = {
            // The bar grows with the title (up to three lines, never an ellipsis at a large font); 64 dp at the least, as the stock bar is.
            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back)) }
                    else Spacer(Modifier.width(12.dp))
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp).semantics { heading() })
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .onSizeChanged { formSize = it },
        ) {
            pinned?.let { Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp)) { it(stripMax, stripWidth) } }
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(scroll)
                    .padding(horizontal = 16.dp, vertical = FORM_BODY_PADDING_V),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { Text(it, color = colors.error, fontSize = 14.sp) }
                Button(
                    onClick = onAction,
                    enabled = actionEnabled && !busy,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.strong,
                        contentColor = colors.onStrong,
                        // Busy keeps the button's look: the spinner on it is the feedback.
                        disabledContainerColor = if (busy) colors.strong else colors.tile,
                        disabledContentColor = if (busy) colors.onStrong else colors.muted,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = colors.onStrong, strokeWidth = 2.dp)
                    } else {
                        Text(action, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

/** A white card of a form with a step title. */
@Composable
fun FormCard(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = AppTheme.colors.card, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            title?.let { Text(it, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
            content()
        }
    }
}
