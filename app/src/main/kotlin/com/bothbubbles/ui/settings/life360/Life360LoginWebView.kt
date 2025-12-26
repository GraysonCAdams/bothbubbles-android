package com.bothbubbles.ui.settings.life360

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import timber.log.Timber

private const val LIFE360_LOGIN_URL = "https://www.life360.com/login"

/**
 * WebView-based login screen for Life360.
 *
 * Intercepts the OAuth token response when the user completes login
 * and extracts the access_token automatically via JavaScript injection.
 */
@Composable
fun Life360LoginWebView(
    onTokenExtracted: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var isLoading by rememberSaveable { mutableStateOf(true) }
    var loadingProgress by rememberSaveable { mutableFloatStateOf(0f) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    // Cleanup WebView on dispose
    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                destroy()
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Life360WebViewContent(
            onWebViewCreated = { webView = it },
            onLoadingChanged = { isLoading = it },
            onProgressChanged = { loadingProgress = it },
            onTokenExtracted = onTokenExtracted
        )

        // Full-screen loading overlay while page loads
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Loading Life360...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { loadingProgress },
                        modifier = Modifier.fillMaxWidth(0.6f)
                    )
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun Life360WebViewContent(
    onWebViewCreated: (WebView) -> Unit,
    onLoadingChanged: (Boolean) -> Unit,
    onProgressChanged: (Float) -> Unit,
    onTokenExtracted: (String) -> Unit
) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                onWebViewCreated(this)

                // Enable JavaScript (required for Life360 login)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                @Suppress("DEPRECATION")
                settings.databaseEnabled = true

                // Clear any existing session data for clean login
                CookieManager.getInstance().removeAllCookies(null)
                clearCache(true)
                clearHistory()

                // Add JavaScript interface for token extraction
                addJavascriptInterface(
                    TokenExtractorInterface(onTokenExtracted),
                    "AndroidTokenExtractor"
                )

                webViewClient = Life360WebViewClient(onLoadingChanged)

                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        onProgressChanged(newProgress / 100f)
                    }
                }

                loadUrl(LIFE360_LOGIN_URL)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

/**
 * JavaScript interface to receive extracted tokens from injected JS.
 */
private class TokenExtractorInterface(
    private val onTokenExtracted: (String) -> Unit
) {
    @JavascriptInterface
    fun onTokenFound(token: String) {
        Timber.d("Token extracted via JavaScript interface")
        onTokenExtracted(token)
    }
}

/**
 * WebViewClient that injects token extraction JavaScript on page load.
 */
private class Life360WebViewClient(
    private val onLoadingChanged: (Boolean) -> Unit
) : WebViewClient() {

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onLoadingChanged(true)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onLoadingChanged(false)

        // Inject JavaScript to intercept fetch/XHR responses
        view?.evaluateJavascript(TOKEN_EXTRACTION_SCRIPT, null)
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)

        // Re-inject script on SPA navigation
        if (url?.contains("life360.com") == true) {
            view?.evaluateJavascript(TOKEN_EXTRACTION_SCRIPT, null)
        }
    }

    companion object {
        /**
         * JavaScript that hooks fetch API to intercept token responses.
         * When Life360 returns an access_token, it's passed to Android via the JS interface.
         */
        private val TOKEN_EXTRACTION_SCRIPT = """
            (function() {
                if (window.__life360TokenExtractorInjected) return;
                window.__life360TokenExtractorInjected = true;

                var originalFetch = window.fetch;
                window.fetch = function(url, options) {
                    return originalFetch.apply(this, arguments).then(function(response) {
                        response.clone().json().then(function(data) {
                            if (data && data.access_token) {
                                window.AndroidTokenExtractor.onTokenFound(data.access_token);
                            }
                        }).catch(function() {});
                        return response;
                    });
                };
            })();
        """.trimIndent()
    }
}
