package ru.adsrv.frpjump.ui.ssh

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.assistant.chat.displayCommand
import ru.adsrv.frpjump.assistant.chat.displayCommandSpans
import ru.adsrv.frpjump.notices.NoticeKind
import ru.adsrv.frpjump.ui.AppSheet
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.notice

/**
 * What a tap on a link in the terminal opens (Р37): the address as it will be used, and "Copy" and "Open". Nothing opens before "Open",
 * and only an http or https link on a machine the phone can reach has it ([linkOpenTarget]); for any other the sheet says why and offers
 * only "Copy". [open] starts the browser and says whether one was there.
 */
@Composable
fun LinkSheet(text: String, onDismiss: () -> Unit, open: (Context, String) -> Boolean = ::openInBrowser) {
    val context = LocalContext.current
    val colors = AppTheme.colors
    val info = remember(text) { describeLink(text) }
    val target = remember(text) { linkOpenTarget(text) }
    val escapeStyle = SpanStyle(background = colors.accentContainer, color = colors.onAccentContainer, fontWeight = FontWeight.Bold)
    val shown = remember(info, escapeStyle) {
        buildAnnotatedString {
            for (span in displayCommandSpans(info.address, forRender = true)) if (span.escape) withStyle(escapeStyle) { append(span.text) } else append(span.text)
        }
    }
    val described = remember(info) { displayCommand(info.address) }
    val noApp = stringResource(R.string.link_no_app)
    val copiedText = stringResource(R.string.copied)
    val label = stringResource(R.string.link_title)
    val explanation = when (info.verdict) {
        LinkVerdict.OPEN -> info.original?.let { stringResource(R.string.link_punycode, displayCommand(it)) }
        LinkVerdict.REMOTE -> stringResource(R.string.link_remote)
        LinkVerdict.OTHER_SCHEME -> stringResource(R.string.link_other_scheme)
        LinkVerdict.HIDDEN -> stringResource(R.string.link_hidden)
        LinkVerdict.INVALID -> stringResource(R.string.link_invalid)
    }
    fun copy() {
        try {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, info.address))
            context.notice(copiedText, NoticeKind.DONE)
        } catch (e: RuntimeException) {
            context.notice(noApp, NoticeKind.WARN)
        }
        onDismiss()
    }
    fun openIt() {
        val address = target ?: return
        if (open(context, address)) onDismiss() else context.notice(noApp, NoticeKind.WARN)
    }
    AppSheet(title = label, onDismiss = onDismiss) {
        // The address is always left to right and in full: it wraps at any character, never cut.
        Box(
            Modifier.fillMaxWidth().background(colors.code, RoundedCornerShape(12.dp)).padding(12.dp),
        ) {
            Text(
                shown,
                color = colors.onCode,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = described }.testTag("link_address"),
            )
        }
        explanation?.let {
            Text(it, fontSize = 13.sp, lineHeight = 18.sp, color = if (info.verdict == LinkVerdict.OPEN) colors.muted else colors.warning, modifier = Modifier.testTag("link_note"))
        }
        // Side by side as in the mockup; stacked once the text is large enough that the words would be squeezed.
        val stacked = LocalDensity.current.fontScale >= 1.4f
        @Composable fun CopyButton(modifier: Modifier) = OutlinedButton(
            onClick = ::copy,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.accent),
            modifier = modifier.heightIn(min = 52.dp).testTag("link_copy"),
        ) { Text(stringResource(R.string.copy), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        @Composable fun OpenButton(modifier: Modifier) = Button(
            onClick = ::openIt,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent),
            modifier = modifier.heightIn(min = 52.dp).testTag("link_open"),
        ) { Text(stringResource(R.string.open), fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        val canOpen = target != null
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canOpen) OpenButton(Modifier.fillMaxWidth())
                CopyButton(Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CopyButton(Modifier.weight(1f))
                if (canOpen) OpenButton(Modifier.weight(1f))
            }
        }
    }
}

/**
 * The links on the screen, for TalkBack and for a small font: a tap on one opens its sheet ([onPick]). [links] are as found on the
 * screen, each once.
 */
@Composable
fun LinksListSheet(links: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AppSheet(title = stringResource(R.string.terminal_links_title), onDismiss = onDismiss) {
        Column(Modifier.testTag("links_list")) {
            for (link in links) {
                val described = displayCommand(link)
                Text(
                    described,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { onPick(link) }
                        .padding(vertical = 12.dp)
                        .testTag("links_item"),
                )
            }
        }
    }
}

/**
 * Hands [address] to the system's browser: ACTION_VIEW and BROWSABLE, in another app, never inside this one. Refuses anything but
 * http and https, whatever the caller thinks it has checked. False when nothing can take it.
 */
fun openInBrowser(context: Context, address: String): Boolean {
    if (linkOpenTarget(address) != address) return false
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(address).normalizeScheme()).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
