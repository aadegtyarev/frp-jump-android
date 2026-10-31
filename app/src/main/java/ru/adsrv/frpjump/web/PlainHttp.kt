package ru.adsrv.frpjump.web

import android.webkit.WebViewClient

/**
 * Whether a failed main-frame load means the device answers the tunnel's
 * port in plain HTTP although the page asked for HTTPS: Chromium's
 * ERR_SSL_PROTOCOL_ERROR, which WebView reports as a failed handshake. Not
 * when the load already had a certificate question or refusal ([sslSeen]),
 * whose cancel ends in the same code; nor another handshake error whose
 * description says otherwise (an old cipher, a client certificate).
 */
fun answersInPlainHttp(code: Int, description: String, url: String, tunnelPort: Int, sslSeen: Boolean): Boolean {
    if (sslSeen || code != WebViewClient.ERROR_FAILED_SSL_HANDSHAKE) return false
    if (!url.startsWith("https://") || !isTunnelUrl(url, tunnelPort)) return false
    return description.isBlank() || "ERR_SSL_PROTOCOL_ERROR" in description
}
