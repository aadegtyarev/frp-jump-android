package ru.adsrv.frpjump.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.licenses.Component
import ru.adsrv.frpjump.licenses.FRPC_COMPONENT
import ru.adsrv.frpjump.licenses.FRPC_MODULES_ASSET
import ru.adsrv.frpjump.licenses.FrpcModules
import ru.adsrv.frpjump.licenses.MPL
import ru.adsrv.frpjump.licenses.componentsFrom
import ru.adsrv.frpjump.licenses.frpcModulesFrom
import ru.adsrv.frpjump.licenses.reflow
import ru.adsrv.frpjump.notices.NoticeKind
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.AppTopBar
import ru.adsrv.frpjump.ui.Segmented
import ru.adsrv.frpjump.ui.notice

/**
 * Settings › Components and licenses: every component in the APK (from
 * the build's `res/raw/aboutlibraries.json`) with its version and
 * licenses; a tap shows the full license text, shipped in the assets, and
 * a link to the sources. frpc's own leads on to the Go modules built into
 * it (`assets/licenses/frpc/modules.json`), each opening the same way.
 * Back or the arrow closes it, one level at a time.
 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var components by remember { mutableStateOf<List<Component>?>(null) }
    var frpc by remember { mutableStateOf<FrpcModules?>(null) }
    LaunchedEffect(Unit) {
        frpc = withContext(Dispatchers.IO) {
            context.assets.open(FRPC_MODULES_ASSET).bufferedReader().use { frpcModulesFrom(it.readText()) }
        }
        components = withContext(Dispatchers.IO) {
            context.resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { componentsFrom(it.readText()) }
        }
    }
    var opened by rememberSaveable { mutableStateOf<String?>(null) }
    var modulesOpen by rememberSaveable { mutableStateOf(false) }
    var openedModule by rememberSaveable { mutableStateOf<String?>(null) }
    val modules = frpc?.modules
    val module = modules?.firstOrNull { it.id == openedModule }
    if (module != null) {
        ComponentScreen(module, onBack = { openedModule = null })
        return
    }
    if (modulesOpen && modules != null) {
        ComponentList(
            title = stringResource(R.string.licenses_frpc_modules),
            subtitle = stringResource(R.string.licenses_modules_count, modules.size),
            list = modules,
            onOpen = { openedModule = it },
            onBack = { modulesOpen = false },
        )
        return
    }
    val component = components?.firstOrNull { it.id == opened }
    if (component != null) {
        ComponentScreen(
            component,
            modules = modules?.takeIf { component.id == FRPC_COMPONENT },
            onOpenModules = { modulesOpen = true },
            onBack = { opened = null },
        )
        return
    }
    val list = components
    ComponentList(
        title = stringResource(R.string.licenses),
        subtitle = list?.let { stringResource(R.string.licenses_count, it.size) },
        list = list,
        onOpen = { opened = it },
        onBack = onBack,
    )
}

/** Components as one card of rows, a spinner while [list] is read. */
@Composable
private fun ComponentList(title: String, subtitle: String?, list: List<Component>?, onOpen: (String) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val colors = AppTheme.colors
    Scaffold(
        containerColor = colors.ground,
        topBar = { AppTopBar(title = title, subtitle = subtitle, onBack = onBack) },
    ) { padding ->
        if (list == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent)
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        ) {
            itemsIndexed(list, key = { _, c -> c.id }) { index, c ->
                val shape = when {
                    list.size == 1 -> RoundedCornerShape(18.dp)
                    index == 0 -> RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
                    index == list.lastIndex -> RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp)
                    else -> RoundedCornerShape(0.dp)
                }
                Column(Modifier.background(colors.card, shape)) {
                    ComponentRow(c, onClick = { onOpen(c.id) })
                    if (index < list.lastIndex) HorizontalDivider(color = colors.divider)
                }
            }
        }
    }
}

/** A component: name, then "version · licenses", and a chevron. */
@Composable
private fun ComponentRow(c: Component, onClick: () -> Unit) {
    NavRow(
        c.name,
        listOfNotNull(c.version, c.licenses.map { it.title }.distinct().joinToString(", ").ifEmpty { null }).joinToString(" · "),
        onClick,
    )
}

/** A row that leads on: a [title], a [detail] line under it, and a chevron. */
@Composable
private fun NavRow(title: String, detail: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = colors.ink)
            Text(
                detail,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = colors.muted,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.faint)
    }
}

/**
 * One component: its sources' address (opens in a browser), for frpc a
 * row on to its Go [modules], and its license's full text, a switch
 * between several. Under MPL-2.0 (yamux) it says the code is unmodified,
 * the address being the source of exactly the version built in.
 */
@Composable
private fun ComponentScreen(c: Component, onBack: () -> Unit, modules: List<Component>? = null, onOpenModules: () -> Unit = {}) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val colors = AppTheme.colors
    var shown by rememberSaveable(c.id) { mutableIntStateOf(0) }
    val license = c.licenses.getOrNull(shown)
    var text by remember(license) { mutableStateOf<String?>(null) }
    LaunchedEffect(license) {
        text = license?.let { l ->
            withContext(Dispatchers.IO) {
                reflow(context.assets.open(l.asset).bufferedReader().use { it.readText() }, markdown = l.asset.endsWith(".md"))
            }
        }
    }
    Scaffold(
        containerColor = colors.card,
        topBar = { AppTopBar(title = c.name, subtitle = c.version, onBack = onBack) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            c.source?.let { url ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.licenses_sources), fontSize = 13.sp, color = colors.muted)
                    // A link to tap: a button's height and role, its text the address.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(role = Role.Button) { openInBrowser(context, url) },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(url, fontSize = 14.sp, color = colors.accent, textDecoration = TextDecoration.Underline)
                    }
                    if (c.licenses.any { MPL in it.ids }) {
                        Text(stringResource(R.string.licenses_mpl_unmodified), fontSize = 13.sp, lineHeight = 18.sp, color = colors.muted)
                    }
                }
            }
            modules?.let {
                NavRow(
                    stringResource(R.string.licenses_frpc_modules),
                    stringResource(R.string.licenses_frpc_modules_hint, it.size),
                    onOpenModules,
                    Modifier.clip(RoundedCornerShape(18.dp)).background(colors.tile),
                )
            }
            if (c.licenses.size > 1) {
                Segmented(c.licenses.indices.toList(), shown, label = { c.licenses[it].short }, compact = true, onPick = { shown = it })
                license?.let { Text(it.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.ink) }
            } else if (license != null) {
                Text(license.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.ink)
            }
            val body = text
            if (body == null) {
                Text(stringResource(R.string.licenses_reading), fontSize = 13.sp, color = colors.muted)
            } else {
                SelectionContainer {
                    Text(body, fontSize = 13.sp, lineHeight = 19.sp, color = colors.ink)
                }
            }
        }
    }
}

private fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        context.notice(context.getString(R.string.no_browser), NoticeKind.WARN)
    }
}
