package com.tl2333.novelvoicereader.content.web

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WebViewFallbackExtractor(private val context: Context) {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun extract(url: String): ByteArray = withTimeout(30_000) {
        val validatedUri = UrlValidator.validate(url)
        NetworkDestinationPolicy.requirePublic(validatedUri)
        val validated = validatedUri.toString()
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val webView = WebView(context.applicationContext)
                webView.settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    domStorageEnabled = true
                }
                fun finish() { runCatching { webView.stopLoading() }; webView.destroy() }
                continuation.invokeOnCancellation { finish() }
                webView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (!request.isForMainFrame) return false
                        val destination = runCatching { UrlValidator.validate(request.url.toString()) }.getOrNull() ?: return true
                        return destination.scheme != validatedUri.scheme || destination.host != validatedUri.host || !NetworkDestinationPolicy.isPublic(destination)
                    }

                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val destination = runCatching { java.net.URI(request.url.toString()) }.getOrNull()
                        return if (destination == null || !NetworkDestinationPolicy.isPublic(destination)) {
                            WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                        } else null
                    }

                    override fun onPageFinished(view: WebView, loadedUrl: String) {
                        view.evaluateJavascript("(function(){return document.documentElement.outerHTML;})()") { encoded ->
                            try {
                                val html = JSONTokener(encoded).nextValue() as? String
                                    ?: throw WebImportException(WebImportErrorCode.EXTRACTION_FAILED, "Dynamic DOM extraction returned no HTML")
                                if (continuation.isActive) continuation.resume(html.toByteArray(Charsets.UTF_8))
                            } catch (error: Throwable) {
                                if (continuation.isActive) continuation.resumeWithException(error)
                            } finally {
                                finish()
                            }
                        }
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame && continuation.isActive) {
                            continuation.resumeWithException(WebImportException(WebImportErrorCode.EXTRACTION_FAILED, "Dynamic page load failed: ${error.description}"))
                            finish()
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= 26) webView.settings.safeBrowsingEnabled = true
                webView.loadUrl(validated)
            }
        }
    }
}
