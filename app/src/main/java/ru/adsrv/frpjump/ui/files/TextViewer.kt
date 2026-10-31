package ru.adsrv.frpjump.ui.files

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.files.PhoneFiles
import ru.adsrv.frpjump.files.text.LineColours
import ru.adsrv.frpjump.files.text.MAX_MATCHES
import ru.adsrv.frpjump.files.text.TextDocument
import ru.adsrv.frpjump.files.text.TextFormat
import ru.adsrv.frpjump.files.text.detectFormat
import ru.adsrv.frpjump.files.text.firstMatchAfter
import ru.adsrv.frpjump.files.text.firstSpanAfter
import ru.adsrv.frpjump.files.text.highlighterFor
import ru.adsrv.frpjump.notices.NoticeKind
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.AppTopBar
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

    data class Ready(val doc: TextDocument, val format: TextFormat, val colours: LineColours?) : Loaded
}

/**
 * A text file read only, over the file screen: monospace, wrapped or
 * scrolled sideways, coloured by its format as the colours are worked out
 * off the main thread; search with next / previous; "⋮" for wrapping, line
 * numbers, text size ([fontSize], the terminal's), Copy all, Share, Open with.
 */
@Composable
fun TextViewer(viewed: ViewedFile, fontSize: MutableStateFlow<Int>, onClose: () -> Unit) {
    val colors = AppTheme.colors
    val context = LocalContext.current
    val file = remember(viewed) { File(viewed.local) }
    var attempt by remember { mutableIntStateOf(0) }
    var loaded by remember(viewed) { mutableStateOf<Loaded>(Loaded.Reading) }
    LaunchedEffect(viewed, attempt) {
        loaded = Loaded.Reading
        loaded = withContext(Dispatchers.IO) {
            try {
                val doc = TextDocument.decode(file.readBytes())
                val format = detectFormat(viewed.name, doc.text.take(512))
                Loaded.Ready(doc, format, highlighterFor(format)?.let { LineColours(doc, it) })
            } catch (e: IOException) {
                Loaded.Failed(e.message ?: e.javaClass.simpleName)
            } catch (_: OutOfMemoryError) {
                Loaded.Failed(context.getString(R.string.viewer_out_of_memory))
            }
        }
    }
    val ready = loaded as? Loaded.Ready
    // Lines coloured so far: the colours follow the text as they are worked out.
    var coloured by remember(ready) { mutableIntStateOf(0) }
    LaunchedEffect(ready) {
        val colours = ready?.colours ?: return@LaunchedEffect
        withContext(Dispatchers.Default) {
            var shown = System.nanoTime()
            while (colours.advance(COLOUR_BATCH)) {
                if (System.nanoTime() - shown > PUBLISH_EVERY_NS) {
                    shown = System.nanoTime()
                    withContext(Dispatchers.Main) { coloured = colours.done }
                }
                yield()
            }
        }
        coloured = colours.done
    }

    var wrap by rememberSaveable { mutableStateOf(true) }
    var numbers by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val fontSp by fontSize.collectAsStateWithLifecycle()

    var searching by rememberSaveable { mutableStateOf(false) }
    var focusSearch by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var matches by remember { mutableStateOf(IntArray(0)) }
    var matchedQuery by remember { mutableStateOf("") }
    var current by remember { mutableIntStateOf(-1) }
    val closeSearch = {
        searching = false
        focusSearch = false
        query = ""
    }
    LaunchedEffect(ready, query) {
        val doc = ready?.doc ?: return@LaunchedEffect
        val text = query
        if (text.isEmpty()) {
            matches = IntArray(0)
            matchedQuery = ""
            current = -1
            return@LaunchedEffect
        }
        // Typing: wait for a pause before looking through 2 MB.
        delay(SEARCH_DEBOUNCE_MS)
        val found = withContext(Dispatchers.Default) { doc.find(text) }
        matches = found
        matchedQuery = text
        current = if (found.isEmpty()) -1 else 0
    }

    BackHandler { onClose() }
    // Declared later, so it comes first: Back closes the search before the viewer.
    BackHandler(enabled = searching) { closeSearch() }

    val list = rememberLazyListState()
    val sideways = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSp.sp, lineHeight = (fontSp * 1.35f).sp, color = colors.ink)
    // Monospace: one character's advance gives the width of any row.
    val charPx = remember(fontSp, measurer) { measurer.measure("0".repeat(100), textStyle).size.width / 100f }
    val styles = remember(colors) { SyntaxStyles(colors) }

    // Stepping to a match brings its row into view, and sideways its column when lines are not wrapped.
    LaunchedEffect(current, matches) {
        val doc = ready?.doc ?: return@LaunchedEffect
        if (current !in matches.indices) return@LaunchedEffect
        val at = matches[current]
        val row = doc.rowAt(at)
        val visible = list.layoutInfo.visibleItemsInfo
        val shown = visible.size > 2 && row > visible.first().index && row < visible.last().index
        if (!shown) list.scrollToItem((row - 3).coerceAtLeast(0))
        if (!wrap) {
            val gutter = if (numbers) doc.lineCount.toString().length + 1 else 0
            val column = (at - doc.rowStart(row) + gutter + (if (doc.isContinuation(row)) 1 else 0)) * charPx
            val view = sideways.viewportSize.takeIf { it > 0 } ?: 0
            if (column < sideways.value || column > sideways.value + view - 4 * charPx) {
                sideways.scrollTo((column - view / 3f).toInt().coerceAtLeast(0))
            }
        }
    }

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
                        count = matches.size,
                        current = current,
                        onPrevious = { if (matches.isNotEmpty()) current = (current - 1 + matches.size) % matches.size },
                        onNext = { if (matches.isNotEmpty()) current = (current + 1) % matches.size },
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
                                wrap = wrap,
                                numbers = numbers,
                                fontSp = fontSp,
                                onWrap = { wrap = !wrap },
                                onNumbers = { numbers = !numbers },
                                onFont = { fontSize.value = it.coerceIn(MIN_FONT_SP, MAX_FONT_SP) },
                                onCopy = { ready?.let { copyAll(context, viewed.name, it.doc.text) } },
                                onShare = { handOver(context, file, viewerType(viewed.name), viewed.name, share = true) },
                                onOpenWith = { handOver(context, file, viewerType(viewed.name), viewed.name, share = false) },
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
                .padding(top = padding.calculateTopPadding()),
        ) {
            when (val l = loaded) {
                Loaded.Reading -> CircularProgressIndicator(color = colors.accent, modifier = Modifier.align(Alignment.Center))
                is Loaded.Failed -> ReadFailed(
                    message = l.message,
                    onRetry = { attempt++ },
                    onOpenWith = { handOver(context, file, viewerType(viewed.name), viewed.name, share = false) },
                )
                is Loaded.Ready -> {
                    val doc = l.doc
                    val gutterChars = if (numbers) doc.lineCount.toString().length else 0
                    val rowContent: @Composable (Int) -> Unit = { row ->
                        val line = doc.rowLine(row)
                        val spans = if (line < coloured) l.colours?.of(line) else null
                        val text = remember(doc, row, spans, matches, matchedQuery, current, styles) {
                            rowText(doc, row, spans, matches, matchedQuery.length, current, styles)
                        }
                        ViewerRow(
                            number = if (numbers && !doc.isContinuation(row)) line + 1 else null,
                            gutterChars = gutterChars,
                            charPx = charPx,
                            text = text,
                            style = textStyle,
                            wrap = wrap,
                        )
                    }
                    val bottom = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)
                    if (wrap) {
                        Rows(list, doc.rowCount, bottom, Modifier.fillMaxSize(), rowContent)
                    } else {
                        BoxWithConstraints(Modifier.fillMaxSize()) {
                            val extra = with(density) { (charPx * (doc.widestRow + gutterChars + 3)).toDp() } + ROW_PADDING * 2
                            val width: Dp = maxOf(maxWidth, extra)
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .horizontalScroll(sideways),
                            ) {
                                Rows(list, doc.rowCount, bottom, Modifier.width(width).fillMaxHeight(), rowContent)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Rows(list: LazyListState, count: Int, padding: PaddingValues, modifier: Modifier, row: @Composable (Int) -> Unit) {
    LazyColumn(state = list, modifier = modifier, contentPadding = padding) {
        items(count) { row(it) }
    }
}

/** One row: the line's number (or nothing) in a gutter, then its text. */
@Composable
private fun ViewerRow(number: Int?, gutterChars: Int, charPx: Float, text: AnnotatedString, style: TextStyle, wrap: Boolean) {
    val colors = AppTheme.colors
    val density = LocalDensity.current
    Row(Modifier.fillMaxWidth().padding(horizontal = ROW_PADDING)) {
        if (gutterChars > 0) {
            Text(
                number?.toString().orEmpty(),
                style = style.copy(color = colors.muted, textAlign = TextAlign.End),
                maxLines = 1,
                modifier = Modifier.width(with(density) { (charPx * gutterChars).toDp() }),
            )
            Box(Modifier.width(with(density) { charPx.toDp() }))
        }
        Text(text, style = style, softWrap = wrap, maxLines = if (wrap) Int.MAX_VALUE else 1)
    }
}

/**
 * [row]'s text with its colours ([spans] of its line, null while not
 * worked out) and the search's [matches] of [length] over them, the
 * [current] one stronger; a row that goes on a long line starts with `↪`.
 */
private fun rowText(doc: TextDocument, row: Int, spans: IntArray?, matches: IntArray, length: Int, current: Int, styles: SyntaxStyles): AnnotatedString {
    val start = doc.rowStart(row)
    val end = doc.rowEnd(row)
    val lineStart = doc.lineStart(doc.rowLine(row))
    return buildAnnotatedString {
        val shift = if (doc.isContinuation(row)) {
            pushStyle(styles.continuation)
            append('↪')
            pop()
            1
        } else {
            0
        }
        append(doc.text, start, end)
        if (spans != null) {
            var k = firstSpanAfter(spans, start - lineStart)
            while (k < spans.size && spans[k] + lineStart < end) {
                val s = maxOf(spans[k] + lineStart, start)
                val e = minOf(spans[k + 1] + lineStart, end)
                if (e > s) addStyle(styles.of(spans[k + 2]), s - start + shift, e - start + shift)
                k += 3
            }
        }
        if (length > 0 && matches.isNotEmpty()) {
            var m = firstMatchAfter(matches, length, start)
            while (m < matches.size && matches[m] < end) {
                val s = maxOf(matches[m], start)
                val e = minOf(matches[m] + length, end)
                addStyle(if (m == current) styles.currentMatch else styles.match, s - start + shift, e - start + shift)
                m++
            }
        }
    }
}

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
            count >= MAX_MATCHES -> stringResource(R.string.viewer_match_of_many, current + 1, MAX_MATCHES)
            else -> stringResource(R.string.viewer_match_of, current + 1, count)
        }
        Text(text, fontSize = 13.sp, color = colors.muted, modifier = Modifier.weight(1f))
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
                text = { Text(stringResource(R.string.viewer_wrap)) },
                trailingIcon = { Checkbox(checked = wrap, onCheckedChange = null) },
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
                IconButton(onClick = { onFont(fontSp - 1) }, enabled = fontSp > MIN_FONT_SP) {
                    Text("A−", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (fontSp > MIN_FONT_SP) colors.ink else colors.muted)
                }
                IconButton(onClick = { onFont(fontSp + 1) }, enabled = fontSp < MAX_FONT_SP) {
                    Text("A+", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (fontSp < MAX_FONT_SP) colors.ink else colors.muted)
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
    try {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(name, text))
    } catch (_: RuntimeException) {
        context.notice(context.getString(R.string.viewer_copy_failed), NoticeKind.WARN)
        return
    }
    context.notice(context.getString(R.string.copied), NoticeKind.DONE)
}

/** The type other apps get the file as: its own, or plain text when its name gives none (it was shown as text). */
private fun viewerType(name: String): String = PhoneFiles.mimeType(name).takeIf { it != "application/octet-stream" } ?: "text/plain"

private val ROW_PADDING = 12.dp
private const val COLOUR_BATCH = 256
private const val PUBLISH_EVERY_NS = 80_000_000L
private const val SEARCH_DEBOUNCE_MS = 200L
