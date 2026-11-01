package ru.adsrv.frpjump.ui.files

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.files.PhoneFiles
import ru.adsrv.frpjump.files.text.TextFormat
import ru.adsrv.frpjump.files.text.decodeText
import ru.adsrv.frpjump.files.text.detectFormat
import ru.adsrv.frpjump.files.text.looksSecret
import ru.adsrv.frpjump.notices.NoticeKind
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.AppTopBar
import ru.adsrv.frpjump.ui.DarkPalette
import ru.adsrv.frpjump.ui.Palette
import ru.adsrv.frpjump.ui.SearchBar
import ru.adsrv.frpjump.ui.SearchGlyph
import ru.adsrv.frpjump.ui.notice
import ru.adsrv.frpjump.ui.ssh.MAX_FONT_SP
import ru.adsrv.frpjump.ui.ssh.MIN_FONT_SP
import java.io.File
import java.io.IOException

/** The most characters Copy all puts on the clipboard: a larger clip may not pass to the system. */
private const val MAX_COPY_CHARS = 128 * 1024

/** A file shown in the viewer: the device's [path], its copy in the cache at [local]. */
data class ViewedFile(val path: String, val local: String, val name: String)

/** Keeps the file open in the viewer across a recreated screen. */
val ViewedFileSaver = listSaver<ViewedFile?, String>(
    save = { it?.let { v -> listOf(v.path, v.local, v.name) }.orEmpty() },
    restore = { if (it.size == 3) ViewedFile(it[0], it[1], it[2]) else null },
)

private sealed interface Loaded {
    data object Reading : Loaded

    data class Failed(val message: String) : Loaded

    /**
     * The [text] read, as the editor's [content] (built off the main
     * thread), coloured as [format]; [wraps] unless a line is longer than
     * [MAX_WRAP_LINE].
     */
    class Ready(val text: String, val content: Content, val format: TextFormat, val wraps: Boolean) : Loaded
}

/**
 * A text file read only, over the file screen, in Sora's editor
 * ([ViewerEditor]): monospace, wrapped or scrolled sideways, coloured by
 * its format's TextMate grammar as it is worked out off the main thread;
 * search with next / previous through the editor's searcher; "⋮" for
 * wrapping, line numbers, text size ([fontSize], the terminal's), Copy
 * all, Share, Open with. Share and Open with go through [onHandOver]
 * (share or not), which hands on a copy of the file; [onClose] is the one
 * way out, and deletes it.
 */
@Composable
fun TextViewer(viewed: ViewedFile, fontSize: MutableStateFlow<Int>, onClose: () -> Unit, onHandOver: (Boolean) -> Unit) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    val file = remember(viewed) { File(viewed.local) }
    var attempt by remember { mutableIntStateOf(0) }
    var loaded by remember(viewed) { mutableStateOf<Loaded>(Loaded.Reading) }
    LaunchedEffect(viewed, attempt) {
        loaded = Loaded.Reading
        // Gone from the cache (cleared, or swept after the process went): nothing to retry, back to the list.
        if (!withContext(Dispatchers.IO) { file.isFile }) {
            context.notice(context.getString(R.string.viewer_file_gone, viewed.name), NoticeKind.WARN, long = true)
            onClose()
            return@LaunchedEffect
        }
        loaded = withContext(Dispatchers.IO) {
            try {
                val text = decodeText(file.readBytes())
                Loaded.Ready(text, Content(text), detectFormat(viewed.name, text), longestLine(text) <= MAX_WRAP_LINE)
            } catch (e: IOException) {
                Loaded.Failed(e.message ?: e.javaClass.simpleName)
            } catch (_: OutOfMemoryError) {
                Loaded.Failed(context.getString(R.string.viewer_out_of_memory))
            }
        }
    }
    val ready = loaded as? Loaded.Ready
    var editor by remember { mutableStateOf<ViewerEditor?>(null) }

    var wrap by rememberSaveable { mutableStateOf(true) }
    var numbers by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val fontSp by fontSize.collectAsStateWithLifecycle()

    // Colours: the theme and the grammar are read off the main thread; the text shows plain until then.
    val palette = colors.palette
    LaunchedEffect(editor, ready, palette) {
        val e = editor ?: return@LaunchedEffect
        val scope = ready?.format?.scope
        try {
            if (scope != null) withContext(Dispatchers.IO) { TextMateSetup.loadGrammar(context.applicationContext, scope) }
            val model = TextMateSetup.useTheme(palette, dark = palette === DarkPalette)
            e.colorScheme = ViewerColorScheme(model, palette)
            if (scope != null) e.setEditorLanguage(TextMateLanguage.create(scope, false))
        } catch (c: CancellationException) {
            throw c
        } catch (x: Exception) {
            // A grammar the regex engine cannot take: the text stays plain.
            Log.w(TAG, "No colours for ${ready?.format}", x)
        }
    }

    var searching by rememberSaveable { mutableStateOf(false) }
    var focusSearch by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    // The query the editor searches for, the one its results are of, how many, and which one is selected.
    var searched by remember { mutableStateOf("") }
    var matchedQuery by remember { mutableStateOf("") }
    var count by remember { mutableIntStateOf(0) }
    var current by remember { mutableIntStateOf(-1) }
    val closeSearch = {
        searching = false
        focusSearch = false
        query = ""
    }
    LaunchedEffect(editor, searching) { editor?.searching = searching }
    LaunchedEffect(editor, query) {
        val e = editor ?: return@LaunchedEffect
        val searcher = e.searcher
        if (query.isEmpty()) {
            if (searcher.hasQuery()) searcher.stopSearch()
            e.collapseSelection()
            searched = ""
            matchedQuery = ""
            count = 0
            current = -1
            return@LaunchedEffect
        }
        // Typing: wait for a pause before looking through 2 MB.
        delay(SEARCH_DEBOUNCE_MS)
        searched = query
        searcher.search(query, EditorSearcher.SearchOptions(EditorSearcher.SearchOptions.TYPE_NORMAL, true))
    }
    // Results came (on the main thread): count them and step to the first, from the top.
    val onResults = {
        val e = editor
        if (e != null && e.searcher.hasQuery()) {
            val s = e.searcher
            matchedQuery = searched
            count = s.matchedPositionCount
            current = -1
            if (count > 0) {
                e.setSelection(0, 0, false, SelectionChangeEvent.CAUSE_SEARCH)
                s.gotoNext()
                current = s.currentMatchedPositionIndex
            }
        }
    }
    val step = { next: Boolean ->
        val e = editor
        if (e != null && e.searcher.hasQuery() && count > 0) {
            val s = e.searcher
            if (next) s.gotoNext() else s.gotoPrevious()
            s.currentMatchedPositionIndex.takeIf { it >= 0 }?.let { current = it }
        }
    }

    BackHandler { onClose() }
    // Declared later, so it comes first: Back closes the search before the viewer.
    BackHandler(enabled = searching) { closeSearch() }

    Scaffold(
        containerColor = colors.card,
        topBar = {
            if (searching) {
                Column(Modifier.background(colors.ground)) {
                    SearchBar(
                        query,
                        onQuery = { query = it },
                        onClose = closeSearch,
                        focus = focusSearch,
                        onFocused = { focusSearch = false },
                        label = stringResource(R.string.viewer_search),
                        hint = stringResource(R.string.viewer_search_hint),
                    )
                    MatchStrip(
                        query = query,
                        settled = matchedQuery == query,
                        count = count,
                        current = current,
                        onPrevious = { step(false) },
                        onNext = { step(true) },
                    )
                }
            } else {
                AppTopBar(
                    title = viewed.name,
                    subtitle = viewed.path,
                    onBack = onClose,
                    actions = {
                        if (ready != null) {
                            IconButton(onClick = {
                                searching = true
                                focusSearch = true
                            }) {
                                Icon(SearchGlyph, contentDescription = stringResource(R.string.viewer_search), tint = colors.ink, modifier = Modifier.size(22.dp))
                            }
                        }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more))
                            }
                            ViewerMenu(
                                expanded = menu,
                                onDismiss = { menu = false },
                                ready = ready != null,
                                wrap = wrap && ready?.wraps != false,
                                canWrap = ready?.wraps != false,
                                numbers = numbers,
                                fontSp = fontSp,
                                onWrap = { wrap = !wrap },
                                onNumbers = { numbers = !numbers },
                                onFont = { fontSize.value = it.coerceIn(MIN_FONT_SP, MAX_FONT_SP) },
                                onCopy = { ready?.let { copyAll(context, viewed.name, it.text) } },
                                onShare = { onHandOver(true) },
                                onOpenWith = { onHandOver(false) },
                            )
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                // The search's keyboard shrinks the text: a match stepped to is never under it.
                .imePadding(),
        ) {
            when (val l = loaded) {
                Loaded.Reading -> CircularProgressIndicator(color = colors.accent, modifier = Modifier.align(Alignment.Center))
                is Loaded.Failed -> ReadFailed(
                    message = l.message,
                    onRetry = { attempt++ },
                    onOpenWith = { onHandOver(false) },
                )
                is Loaded.Ready -> key(l) {
                    AndroidView(
                        factory = { ctx ->
                            ViewerEditor(ctx).also { e ->
                                e.colorScheme = plainScheme(palette)
                                e.setText(l.content)
                                e.subscribeAlways(PublishSearchResultEvent::class.java) { onResults() }
                                // Shrunk by the keyboard: the selected match comes back into view.
                                e.addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
                                    if (bottom - top < oldBottom - oldTop && e.cursor.isSelected) v.post { e.ensureSelectionVisible() }
                                }
                                editor = e
                            }
                        },
                        update = { e ->
                            // Sora wraps a line in time growing with its length squared: a huge one stays unwrapped.
                            val wraps = wrap && l.wraps
                            if (e.isWordwrap != wraps) e.setWordwrap(wraps)
                            if (e.isLineNumberEnabled != numbers) e.setLineNumberEnabled(numbers)
                            e.setFontSp(fontSp)
                        },
                        onRelease = { e ->
                            if (editor === e) editor = null
                            e.release()
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = padding.calculateBottomPadding()),
                    )
                }
            }
        }
    }
}

/** The editor's colours before its theme is read: the card's ground and the app's ink, without syntax colours. */
private fun plainScheme(p: Palette): EditorColorScheme = EditorColorScheme().also { s ->
    s.setColor(EditorColorScheme.WHOLE_BACKGROUND, p.card.toInt())
    s.setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, p.card.toInt())
    s.setColor(EditorColorScheme.TEXT_NORMAL, p.ink.toInt())
    s.setColor(EditorColorScheme.LINE_NUMBER, p.muted.toInt())
}

private const val TAG = "TextViewer"

/** Under the search field: "3 of 17" (or why there is none), previous and next. */
@Composable
private fun MatchStrip(query: String, settled: Boolean, count: Int, current: Int, onPrevious: () -> Unit, onNext: () -> Unit) {
    val colors = AppTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 32.dp, end = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val text = when {
            query.isEmpty() -> ""
            !settled -> stringResource(R.string.viewer_searching)
            count == 0 -> stringResource(R.string.viewer_no_matches)
            else -> stringResource(R.string.viewer_match_of, current + 1, count)
        }
        Text(
            text,
            fontSize = 13.sp,
            color = colors.muted,
            modifier = Modifier
                .weight(1f)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        IconButton(onClick = onPrevious, enabled = settled && count > 0) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.viewer_previous))
        }
        IconButton(onClick = onNext, enabled = settled && count > 0) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.viewer_next))
        }
    }
}

@Composable
private fun ViewerMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    ready: Boolean,
    wrap: Boolean,
    canWrap: Boolean,
    numbers: Boolean,
    fontSp: Int,
    onWrap: () -> Unit,
    onNumbers: () -> Unit,
    onFont: (Int) -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onOpenWith: () -> Unit,
) {
    val colors = AppTheme.colors
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (ready) {
            DropdownMenuItem(
                text = {
                    Column {
                        Text(stringResource(R.string.viewer_wrap))
                        if (!canWrap) Text(stringResource(R.string.viewer_wrap_too_long), fontSize = 12.sp, color = colors.muted)
                    }
                },
                trailingIcon = { Checkbox(checked = wrap, onCheckedChange = null, enabled = canWrap) },
                enabled = canWrap,
                onClick = onWrap,
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.viewer_line_numbers)) },
                trailingIcon = { Checkbox(checked = numbers, onCheckedChange = null) },
                onClick = onNumbers,
            )
            // Text size as in the terminal's menu, and the same size; the menu stays open while sizing.
            Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.terminal_text_size), Modifier.weight(1f, fill = false).padding(end = 12.dp))
                val smaller = stringResource(R.string.terminal_text_smaller)
                val larger = stringResource(R.string.terminal_text_larger)
                IconButton(onClick = { onFont(fontSp - 1) }, enabled = fontSp > MIN_FONT_SP, modifier = Modifier.semantics { contentDescription = smaller }) {
                    Text("A−", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (fontSp > MIN_FONT_SP) colors.ink else colors.muted, modifier = Modifier.clearAndSetSemantics {})
                }
                IconButton(onClick = { onFont(fontSp + 1) }, enabled = fontSp < MAX_FONT_SP, modifier = Modifier.semantics { contentDescription = larger }) {
                    Text("A+", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (fontSp < MAX_FONT_SP) colors.ink else colors.muted, modifier = Modifier.clearAndSetSemantics {})
                }
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.viewer_copy_all)) }, onClick = {
                onDismiss()
                onCopy()
            })
        }
        DropdownMenuItem(text = { Text(stringResource(R.string.files_share)) }, onClick = {
            onDismiss()
            onShare()
        })
        DropdownMenuItem(text = { Text(stringResource(R.string.files_open_with)) }, onClick = {
            onDismiss()
            onOpenWith()
        })
    }
}

/** The file could not be read from the cache: why, Retry, and another app as a way out. */
@Composable
private fun ReadFailed(message: String, onRetry: () -> Unit, onOpenWith: () -> Unit) {
    val colors = AppTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.viewer_read_failed, message), fontSize = 14.sp, color = colors.error)
        PrimaryButton(stringResource(R.string.retry), enabled = true, onClick = onRetry)
        SecondaryButton(stringResource(R.string.files_open_with), null, enabled = true, Modifier.fillMaxWidth(), onClick = onOpenWith)
    }
}

/** The whole text to the clipboard, when it is small enough to pass; a notice says what happened. */
private fun copyAll(context: Context, name: String, text: String) {
    if (text.length > MAX_COPY_CHARS) {
        context.notice(context.getString(R.string.viewer_copy_too_large), NoticeKind.WARN, long = true)
        return
    }
    val clip = ClipData.newPlainText(name, text)
    // Secrets (`.env`, keys): the system does not show them in its clipboard preview.
    if (looksSecret(name) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    try {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    } catch (_: RuntimeException) {
        context.notice(context.getString(R.string.viewer_copy_failed), NoticeKind.WARN)
        return
    }
    context.notice(context.getString(R.string.copied), NoticeKind.DONE)
}

/** The type other apps get the file as: its own, or plain text when its name gives none (it was shown as text). */
internal fun viewerType(name: String): String = PhoneFiles.mimeType(name).takeIf { it != "application/octet-stream" } ?: "text/plain"

private const val SEARCH_DEBOUNCE_MS = 200L

/** The longest line the viewer wraps: Sora wraps a line in time growing with its length squared (52 K characters: 6 s on the emulator). */
internal const val MAX_WRAP_LINE = 16 * 1024

/** The length of [text]'s longest line, in characters. */
internal fun longestLine(text: String): Int {
    var longest = 0
    var start = 0
    while (start <= text.length) {
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        longest = maxOf(longest, end - start)
        start = end + 1
    }
    return longest
}
