package ru.adsrv.frpjump.ui.web

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import ru.adsrv.frpjump.BuildConfig
import java.io.ByteArrayInputStream
import ru.adsrv.frpjump.web.WebNavigation
import ru.adsrv.frpjump.web.isLoopbackHttpUrl
import ru.adsrv.frpjump.web.isTunnelUrl
import ru.adsrv.frpjump.web.webNavigationFor

/**
 * A device's web UI through its tunnel on `http://127.0.0.1:<localPort>/`.
 * Only this tunnel's pages open here (see [webNavigationFor]): other loopback
 * ports are refused, links off the phone go to other apps. WebSockets to the same origin (homeui's MQTT over WS) work
 * as in any browser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebScreen(device: String, localPort: Int, onBack: () -> Unit) {
    val context = LocalContext.current
    val start = remember(localPort) { "http://127.0.0.1:$localPort/" }
    var title by remember { mutableStateOf(device) }
    var url by remember { mutableStateOf(start) }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val webView = remember {
        WebView(context).apply {
            // AndroidView's default WRAP_CONTENT puts WebView into its "size to content"
            // mode, where 100dvh is 0: homeui (body/#root at 100dvh) then renders nothing.
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    DisposableEffect(webView) {
        configure(webView, context, localPort,
            onPage = { pageUrl ->
                url = pageUrl
                canGoBack = webView.canGoBack()
            },
            onTitle = { title = it.ifBlank { device } },
            onProgress = { progress = it },
        )
        webView.loadUrl(start)
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }
    BackHandler(enabled = canGoBack) { webView.goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            url,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { webView.reload() }) { Icon(Icons.Filled.Refresh, contentDescription = "Reload") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Open in browser") },
                            onClick = {
                                menu = false
                                openExternally(context, webView.url ?: start)
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        }
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
) {
    if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
    with(webView.settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        builtInZoomControls = true
        displayZoomControls = false
        useWideViewPort = true
        loadWithOverviewMode = true
    }
    CookieManager.getInstance().setAcceptCookie(true)
    webView.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val target = request.url.toString()
            when (webNavigationFor(target, tunnelPort)) {
                WebNavigation.IN_APP -> return false
                WebNavigation.EXTERNAL -> openExternally(context, target)
                WebNavigation.BLOCKED -> Toast.makeText(context, "Blocked: $target is not this tunnel.", Toast.LENGTH_SHORT).show()
            }
            return true
        }

        /** Scripts, images and frames from other loopback ports (other tunnels, other apps) get a 403. */
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val target = request.url.toString()
            if (!isLoopbackHttpUrl(target) || isTunnelUrl(target, tunnelPort)) return null
            return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = onPage(url)

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = onPage(url)
    }
    webView.webChromeClient = object : WebChromeClient() {
        override fun onReceivedTitle(view: WebView, title: String?) = onTitle(title.orEmpty())

        override fun onProgressChanged(view: WebView, newProgress: Int) = onProgress(newProgress)

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
                Toast.makeText(context, "The page made this file itself; use \"Open in browser\" to save it.", Toast.LENGTH_LONG).show()
            isLoopbackHttpUrl(url) -> Toast.makeText(context, "Blocked: $url is not this tunnel.", Toast.LENGTH_SHORT).show()
            else -> openExternally(context, url)
        }
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
        Toast.makeText(context, "Downloading $name", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        openExternally(context, url)
    }
}

private fun openExternally(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open $url", Toast.LENGTH_SHORT).show()
    }
}
