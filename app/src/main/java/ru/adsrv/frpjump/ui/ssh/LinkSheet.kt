package ru.adsrv.frpjump.ui.ssh

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
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
import androidx.compose.ui.text.AnnotatedString
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
    val shown = remember(info, escapeStyle) { linkText(info.address, escapeStyle) }
    val described = remember(info) { displayCommand(info.address) }
    val noApp = stringResource(R.string.link_no_app)
    val copiedText = stringResource(R.string.copied)
    val copyFailed = stringResource(R.string.viewer_copy_failed)
    val label = stringResource(R.string.link_title)
    // Quiet lines under the address; a link that must not open gets the warning colour only when it is hidden or unreadable.
    val notes = ArrayList<String>()
    when (info.verdict) {
        LinkVerdict.OPEN -> info.original?.let { notes += stringResource(R.string.link_punycode, displayCommand(it)) }
        LinkVerdict.REMOTE -> notes += stringResource(R.string.link_remote)
        LinkVerdict.OTHER_SCHEME -> notes += stringResource(R.string.link_other_scheme)
        LinkVerdict.HIDDEN -> notes += stringResource(R.string.link_hidden)
        LinkVerdict.INVALID -> notes += stringResource(R.string.link_invalid)
    }
    info.actualHost?.let { notes += stringResource(R.string.link_userinfo, displayCommand(it)) }
    val noteColor = if (info.verdict == LinkVerdict.HIDDEN || info.verdict == LinkVerdict.INVALID) colors.warning else colors.muted
    fun copy() {
        val clip = ClipData.newPlainText(label, info.address)
        // The system's clipboard preview would show the address on screen again; a link in a terminal can carry a token.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        try {
            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            context.notice(copiedText, NoticeKind.DONE)
        } catch (e: RuntimeException) {
            context.notice(copyFailed, NoticeKind.WARN)
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
        notes.forEachIndexed { index, note ->
            // The theme's body style, so the lines follow the font size like the rest of the app.
            Text(note, style = MaterialTheme.typography.bodyMedium, color = noteColor, modifier = Modifier.testTag(if (index == 0) "link_note" else "link_note_$index"))
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
    val colors = AppTheme.colors
    val escapeStyle = SpanStyle(background = colors.accentContainer, color = colors.onAccentContainer, fontWeight = FontWeight.Bold)
    AppSheet(title = stringResource(R.string.terminal_links_title), onDismiss = onDismiss) {
        Column(Modifier.testTag("links_list")) {
            for (link in links) {
                val described = displayCommand(link)
                val spans = remember(link, escapeStyle) { linkText(link, escapeStyle) }
                Text(
                    spans,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { onPick(link) }
                        .padding(vertical = 12.dp)
                        .semantics { contentDescription = described }
                        .testTag("links_item"),
                )
            }
        }
    }
}

/** [address] as the sheet shows it: every invisible or disguising character as a highlighted visible mark, laid out left to right. */
private fun linkText(address: String, escape: SpanStyle): AnnotatedString = buildAnnotatedString {
    for (span in displayCommandSpans(address, forRender = true)) if (span.escape) withStyle(escape) { append(span.text) } else append(span.text)
}

/**
 * The intent that hands [address] to whatever the system opens web links with: ACTION_VIEW and BROWSABLE, no component, package or
 * selector of ours, in another app, never inside this one. Null for anything but an http or https link that may open
 * ([linkOpenTarget]), whatever the caller thinks it has checked: the second gate before a launch.
 */
fun browserIntent(address: String): Intent? {
    if (linkOpenTarget(address) != address) return null
    return Intent(Intent.ACTION_VIEW, Uri.parse(address).normalizeScheme()).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** Starts [browserIntent] for [address]; false when it is refused or nothing can take it. */
fun openInBrowser(context: Context, address: String): Boolean {
    val intent = browserIntent(address) ?: return false
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
