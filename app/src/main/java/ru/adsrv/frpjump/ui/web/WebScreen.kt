package ru.adsrv.frpjump.ui.web

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Environment
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.adsrv.frpjump.BuildConfig
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.ui.AppTheme
import ru.adsrv.frpjump.ui.CollapseButton
import ru.adsrv.frpjump.ui.CompactTopBar
import ru.adsrv.frpjump.ui.FullscreenGlyph
import ru.adsrv.frpjump.ui.FullscreenOf
import ru.adsrv.frpjump.ui.SystemBarsHidden
import ru.adsrv.frpjump.ui.rememberFullscreen
import ru.adsrv.frpjump.web.CertCheck
import ru.adsrv.frpjump.web.WebNavigation
import ru.adsrv.frpjump.web.certFingerprint
import ru.adsrv.frpjump.web.isLoopbackHttpUrl
import ru.adsrv.frpjump.web.isTunnelUrl
import ru.adsrv.frpjump.web.webNavigationFor

/** A certificate waiting for the owner's word, with every request that waits on it. */
private class CertQuestion(val fingerprint: String, val issuedTo: String?, val check: CertCheck) {
    val handlers = mutableListOf<SslErrorHandler>()
}

/**
 * A device's web UI through its tunnel on `http://127.0.0.1:<localPort>/`
 * (`https://` when [https]). A certificate is accepted only once the owner
 * confirmed its fingerprint for this profile, device and [targetPort]
 * ([ru.adsrv.frpjump.web.WebCertStore]); a different one asks again.
 * Only this tunnel's pages open here (see [webNavigationFor]): other
 * loopback ports are refused, links off the phone go to other apps.
 * WebSockets to the same origin (homeui's MQTT over WS) work as in any
 * browser. If the page's renderer dies, the screen says so and "Reload"
 * starts a fresh web view instead of the app crashing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebScreen(profile: String, device: String, localPort: Int, targetPort: Int, https: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val start = remember(localPort, https) { (if (https) "https" else "http") + "://127.0.0.1:$localPort/" }
    val certs = remember(profile) { (context.applicationContext as FrpJumpApp).profiles.runtime(profile)?.webCerts }
    var question by remember { mutableStateOf<CertQuestion?>(null) }
    var certRefused by remember { mutableStateOf(false) }
    /** An HTTPS connection whose device answers this port in plain HTTP (ERR_SSL_PROTOCOL_ERROR). */
    var notTls by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(device) }
    var url by remember { mutableStateOf(start) }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    /** Bumped to replace a web view whose renderer is gone. */
    var generation by remember { mutableIntStateOf(0) }
    var rendererGone by remember { mutableStateOf(false) }
    val files = remember { FileChooser() }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        files.deliver(it.resultCode, it.data)
    }
    val webView = remember(generation) {
        WebView(context).apply {
            // AndroidView's default WRAP_CONTENT puts WebView into its "size to content"
            // mode, where 100dvh is 0: homeui (body/#root at 100dvh) then renders nothing.
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    DisposableEffect(webView) {
        configure(
            webView, context, localPort,
            onPage = { pageUrl ->
                url = pageUrl
                canGoBack = webView.canGoBack()
            },
            onTitle = { title = it.ifBlank { device } },
            onProgress = { progress = it },
            onRendererGone = { rendererGone = true },
            onMainFrameError = { code, failed ->
                if (code == WebViewClient.ERROR_FAILED_SSL_HANDSHAKE && failed.startsWith("https://") && isTunnelUrl(failed, localPort)) notTls = true
            },
            onSslError = { handler, error ->
                val cert = error.certificate
                val fingerprint = runCatching { cert?.x509Certificate?.encoded?.let(::certFingerprint) }.getOrNull()
                val forTunnel = isTunnelUrl(error.url, localPort)
                when (val check = certs?.check(device, targetPort, fingerprint, forTunnel) ?: CertCheck.Refused) {
                    CertCheck.Trusted -> handler.proceed()
                    CertCheck.Refused -> {
                        handler.cancel()
                        if (forTunnel) certRefused = true
                    }
                    else -> {
                        val open = question
                        val asked = if (open != null && open.fingerprint == fingerprint) {
                            open
                        } else {
                            // Another certificate replaces the question: what waited on the old one is refused, not left hanging.
                            open?.handlers?.forEach { it.cancel() }
                            CertQuestion(checkNotNull(fingerprint), cert?.issuedTo?.cName?.takeIf { it.isNotBlank() }, check)
                        }
                        asked.handlers += handler
                        question = asked
                    }
                }
            },
            onChooseFiles = { callback, params ->
                files.start(callback, params)?.let { intent ->
                    try {
                        pickFiles.launch(intent)
                    } catch (_: ActivityNotFoundException) {
                        files.deliver(Activity.RESULT_CANCELED, null)
                        Toast.makeText(context, context.getString(R.string.web_no_file_picker), Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
        // After a renderer crash, carry on where the page was.
        val first = if (generation == 0) start else url
        // Every screen asks anew: WebView would otherwise remember a "proceed" for the process.
        webView.clearSslPreferences()
        val sites = (context.applicationContext as FrpJumpApp).webSites
        var alive = true
        if (sites.isOther(profile, device, localPort)) {
            // Another device (or port) than last time: its cookies must not reach this one.
            clearWebData(webView) {
                sites.remember(profile, device, localPort)
                if (alive) webView.loadUrl(first)
            }
        } else {
            webView.loadUrl(first)
        }
        onDispose {
            alive = false
            files.deliver(Activity.RESULT_CANCELED, null)
            // A web view must leave its parent before destroy(); one whose renderer is gone
            // may only be destroyed.
            (webView.parent as? ViewGroup)?.removeView(webView)
            if (!rendererGone) webView.stopLoading()
            webView.destroy()
        }
    }
    val reload: () -> Unit = {
        if (certRefused) {
            certRefused = false
            webView.clearSslPreferences()
            webView.loadUrl(url)
        } else if (rendererGone) {
            rendererGone = false
            generation++
        } else {
            webView.reload()
        }
    }
    val (fullscreen, setFullscreen) = rememberFullscreen(FullscreenOf.WEB)
    val clipboard = LocalClipboardManager.current
    BackHandler(enabled = canGoBack && !rendererGone) { webView.goBack() }
    // Declared after the page's: Back leaves full screen first, then walks the history.
    BackHandler(enabled = fullscreen) { setFullscreen(false) }
    SystemBarsHidden(fullscreen)

    Scaffold(
        containerColor = AppTheme.colors.ground,
        // Full screen: the bars are hidden and the page takes their place; a cutout still counts.
        contentWindowInsets = if (fullscreen) WindowInsets.displayCutout else ScaffoldDefaults.contentWindowInsets,
        topBar = {
            if (!fullscreen) CompactTopBar(
                title = title,
                onBack = onBack,
                actions = {
                    IconButton(onClick = { setFullscreen(true) }) { Icon(FullscreenGlyph, contentDescription = stringResource(R.string.fullscreen), modifier = Modifier.size(20.dp)) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            // The address, as the bar no longer shows it; tap copies it.
                            DropdownMenuItem(
                                text = { Text(url, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = AppTheme.colors.muted) },
                                onClick = {
                                    menu = false
                                    clipboard.setText(AnnotatedString(url))
                                    Toast.makeText(context, context.getString(R.string.address_copied, url), Toast.LENGTH_SHORT).show()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.web_reload)) },
                                onClick = {
                                    menu = false
                                    reload()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.open_in_browser)) },
                                onClick = {
                                    menu = false
                                    openExternally(context, url)
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        // The keyboard shrinks the page, so a field it would cover scrolls into view.
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (notTls) {
                    // The device speaks HTTP on this port: say so and offer to switch, instead of Chrome's error page.
                    Text(stringResource(R.string.web_not_tls), color = AppTheme.colors.error, fontSize = 14.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
                    TextButton(
                        onClick = {
                            notTls = false
                            scope.launch {
                                val saved = runCatching { withContext(Dispatchers.IO) { useHttp(context, profile, device, targetPort) } }.isSuccess
                                if (!saved) Toast.makeText(context, context.getString(R.string.web_not_tls_unsaved), Toast.LENGTH_LONG).show()
                                webView.loadUrl("http://127.0.0.1:$localPort/")
                            }
                        },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) { Text(stringResource(R.string.web_open_http)) }
                }
                if (certRefused) {
                    // Above the page, which stays in place for "Try again".
                    Text(stringResource(R.string.cert_refused), color = AppTheme.colors.error, fontSize = 14.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
                    TextButton(onClick = reload, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.cert_try_again)) }
                }
                if (rendererGone) {
                    Text(
                        stringResource(R.string.web_renderer_gone),
                        modifier = Modifier.padding(16.dp),
                    )
                    TextButton(onClick = reload, modifier = Modifier.padding(horizontal = 8.dp)) { Text(stringResource(R.string.web_reload)) }
                } else {
                    if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                    AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
                }
            }
            if (fullscreen) CollapseButton { setFullscreen(false) }
        }
    }
    question?.let { q ->
        CertDialog(
            device = device,
            port = targetPort,
            question = q,
            onTrust = {
                question = null
                scope.launch {
                    val pinned = runCatching { withContext(Dispatchers.IO) { certs?.pin(device, targetPort, q.fingerprint) } }.isSuccess
                    q.handlers.forEach { if (pinned) it.proceed() else it.cancel() }
                    if (!pinned) certRefused = true
                }
            },
            onRefuse = {
                question = null
                q.handlers.forEach { it.cancel() }
                certRefused = true
            },
        )
    }
}

/** The page's pending `<input type=file>`: at most one, answered exactly once. */
private class FileChooser {
    private var pending: ValueCallback<Array<Uri>>? = null

    /** Takes over [callback] (cancelling an older one) and returns the picker intent. */
    fun start(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Intent? {
        deliver(Activity.RESULT_CANCELED, null)
        pending = callback
        return params.createIntent().apply {
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
    }

    fun deliver(resultCode: Int, data: Intent?) {
        val callback = pending ?: return
        pending = null
        val clip = data?.clipData
        val picked = if (resultCode == Activity.RESULT_OK && clip != null && clip.itemCount > 0) {
            Array(clip.itemCount) { clip.getItemAt(it).uri }
        } else {
            WebChromeClient.FileChooserParams.parseResult(resultCode, data)
        }
        callback.onReceiveValue(picked)
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun configure(
    webView: WebView,
    context: Context,
    tunnelPort: Int,
    onPage: (String) -> Unit,
    onTitle: (String) -> Unit,
    onProgress: (Int) -> Unit,
    onRendererGone: () -> Unit,
    /** A main-frame load failed with WebViewClient's [Int] error code, for that URL. */
    onMainFrameError: (Int, String) -> Unit,
    onSslError: (SslErrorHandler, SslError) -> Unit,
    onChooseFiles: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Unit,
) {
    if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
    with(webView.settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        builtInZoomControls = true
        displayZoomControls = false
        useWideViewPort = true
        loadWithOverviewMode = true
        // Pages of a device have no business with the phone's files or content providers.
        allowFileAccess = false
        allowContentAccess = false
    }
    CookieManager.getInstance().setAcceptCookie(true)
    webView.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val target = request.url.toString()
            when (webNavigationFor(target, tunnelPort)) {
                WebNavigation.IN_APP -> return false
                WebNavigation.EXTERNAL -> openExternally(context, target)
                WebNavigation.BLOCKED -> Toast.makeText(context, context.getString(R.string.web_blocked, target), Toast.LENGTH_SHORT).show()
            }
            return true
        }

        /**
         * Requests (scripts, images, frames, fetch) to other loopback ports (other tunnels, other apps) get a 403.
         * WebSockets never come here, so they are not filtered (README › Web ports).
         */
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val target = request.url.toString()
            if (!isLoopbackHttpUrl(target) || isTunnelUrl(target, tunnelPort)) return null
            return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = onPage(url)

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = onPage(url)

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) onMainFrameError(error.errorCode, request.url.toString())
        }

        /** A self-signed (or any) certificate: only one the owner confirmed for this connection is accepted. */
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) = onSslError(handler, error)

        /** Handled: without this the whole app is killed with the renderer. */
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w("WebScreen", "renderer gone (crashed: ${detail.didCrash()})")
            onRendererGone()
            return true
        }
    }
    webView.webChromeClient = object : WebChromeClient() {
        override fun onReceivedTitle(view: WebView, title: String?) = onTitle(title.orEmpty())

        override fun onProgressChanged(view: WebView, newProgress: Int) = onProgress(newProgress)

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            onChooseFiles(filePathCallback, fileChooserParams)
            return true
        }

        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            if (BuildConfig.DEBUG) Log.d("WebConsole", "${message.messageLevel()} ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
            return false
        }
    }
    webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
        download(context, tunnelPort, url, userAgent, contentDisposition, mimeType)
    }
}

/**
 * Drops cookies, web storage and the HTTP cache of every tunnel. Cookies
 * ignore ports, so they cannot be scoped to one tunnel. Main thread.
 */
fun clearWebData(webView: WebView?, onDone: () -> Unit = {}) {
    webView?.clearCache(true)
    WebStorage.getInstance().deleteAllData()
    CookieManager.getInstance().removeAllCookies {
        CookieManager.getInstance().flush()
        onDone()
    }
}

/**
 * Files from the device go to the system DownloadManager, with the page's
 * cookies (a login session). Files the page makes itself (blob:, data:)
 * exist only inside the WebView and cannot be handed over; other hosts go
 * to other apps.
 */
private fun download(
    context: Context,
    tunnelPort: Int,
    url: String,
    userAgent: String?,
    contentDisposition: String?,
    mimeType: String?,
) {
    if (!isTunnelUrl(url, tunnelPort)) {
        when {
            url.startsWith("blob:") || url.startsWith("data:") ->
                Toast.makeText(context, context.getString(R.string.web_download_page_made), Toast.LENGTH_LONG).show()
            isLoopbackHttpUrl(url) -> Toast.makeText(context, context.getString(R.string.web_blocked, url), Toast.LENGTH_SHORT).show()
            else -> openExternally(context, url)
        }
        return
    }
    if (url.trim().startsWith("https:", ignoreCase = true)) {
        // DownloadManager checks certificates itself and knows nothing of the one pinned here.
        Toast.makeText(context, context.getString(R.string.download_https), Toast.LENGTH_LONG).show()
        return
    }
    val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
    try {
        val request = DownloadManager.Request(Uri.parse(url))
            .setMimeType(mimeType)
            .setTitle(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
        userAgent?.let { request.addRequestHeader("User-Agent", it) }
        context.getSystemService(DownloadManager::class.java).enqueue(request)
        Toast.makeText(context, context.getString(R.string.web_downloading, name), Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        openExternally(context, url)
    }
}

private fun openExternally(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.web_no_app, url), Toast.LENGTH_SHORT).show()
    }
}

/** Shows the certificate's fingerprint and asks whether to trust it: first visit, or a changed certificate. */
@Composable
private fun CertDialog(device: String, port: Int, question: CertQuestion, onTrust: () -> Unit, onRefuse: () -> Unit) {
    val changed = question.check as? CertCheck.Changed
    AlertDialog(
        onDismissRequest = onRefuse,
        title = { Text(stringResource(if (changed != null) R.string.cert_changed_title else R.string.cert_unknown_title)) },
        text = {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (changed != null) R.string.cert_changed_text else R.string.cert_unknown_text, device, port))
                    question.issuedTo?.let { Text(stringResource(R.string.cert_issued_to, it), style = MaterialTheme.typography.bodySmall) }
                    if (changed != null) Fingerprint(stringResource(R.string.cert_fingerprint_before), changed.pinned)
                    Fingerprint(stringResource(R.string.cert_fingerprint_now), question.fingerprint)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onTrust) {
                Text(stringResource(if (changed != null) R.string.cert_trust_new else R.string.cert_trust))
            }
        },
        dismissButton = { TextButton(onClick = onRefuse) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Fingerprint(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

/**
 * "Open over HTTP": the profile's web connections to [device]:[port] lose
 * their HTTPS flag, so the next Open uses http too. Storage: off the main thread.
 */
private fun useHttp(context: Context, profile: String, device: String, port: Int) {
    val store = (context.applicationContext as FrpJumpApp).profiles.runtime(profile)?.connections ?: return
    store.connections.value
        .filter { it.device == device && it.port == port && it.https }
        .forEach { store.save(it.copy(https = false)) }
}
