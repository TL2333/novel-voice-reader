package com.tl2333.novelvoicereader.content.web

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLException

enum class WebImportErrorCode { DNS_FAILED, CONNECT_TIMEOUT, HTTP_ERROR, TOO_LARGE, UNSUPPORTED_CONTENT, REDIRECT_LOOP, TLS_ERROR, EXTRACTION_FAILED }

class WebImportException(val code: WebImportErrorCode, message: String, cause: Throwable? = null) : IOException(message, cause)

data class WebFetchResult(
    val requestedUrl: String,
    val finalUrl: String,
    val contentType: String?,
    val bytes: ByteArray,
)

class WebFetcher(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val callTimeoutMs: Long = 60_000,
    private val maxBytes: Int = 20 * 1024 * 1024,
) {
    fun fetch(uri: URI): WebFetchResult {
        val requested = UrlValidator.validate(uri.toString())
        var current = requested
        val visited = linkedSetOf<String>()
        val startedAt = System.nanoTime()
        repeat(MAX_REDIRECTS + 1) {
            val remainingMs = callTimeoutMs - elapsedMs(startedAt)
            if (remainingMs <= 0L) throw WebImportException(WebImportErrorCode.CONNECT_TIMEOUT, "Web call timed out")
            if (!visited.add(current.toString())) throw WebImportException(WebImportErrorCode.REDIRECT_LOOP, "Redirect loop")
            NetworkDestinationPolicy.requirePublic(current)
            val connection = try {
                (current.toURL().openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = minOf(connectTimeoutMs.toLong(), remainingMs).coerceAtLeast(1L).toInt()
                    readTimeout = minOf(readTimeoutMs.toLong(), remainingMs).coerceAtLeast(1L).toInt()
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "NovelVoiceReader/0.1 (+offline-snapshot)")
                    setRequestProperty("Accept", "text/html,text/plain,application/pdf,application/epub+zip,application/vnd.openxmlformats-officedocument.wordprocessingml.document;q=0.9,*/*;q=0.1")
                }
            } catch (error: Exception) {
                throw mapNetworkError(error)
            }
            try {
                val code = connection.responseCode
                if (elapsedMs(startedAt) > callTimeoutMs) {
                    throw WebImportException(WebImportErrorCode.CONNECT_TIMEOUT, "Web call timed out")
                }
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw WebImportException(WebImportErrorCode.HTTP_ERROR, "Redirect has no Location")
                    current = UrlValidator.validate(current.resolve(location).toString())
                    return@repeat
                }
                if (code !in 200..299) throw WebImportException(WebImportErrorCode.HTTP_ERROR, "HTTP $code")
                val declared = connection.contentLengthLong
                if (declared > maxBytes) throw WebImportException(WebImportErrorCode.TOO_LARGE, "Content exceeds $maxBytes bytes")
                val output = ByteArrayOutputStream(minOf(maxBytes, declared.takeIf { it > 0 }?.toInt() ?: 32 * 1024))
                connection.inputStream.use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        if (elapsedMs(startedAt) > callTimeoutMs) {
                            throw WebImportException(WebImportErrorCode.CONNECT_TIMEOUT, "Web call timed out")
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > maxBytes) throw WebImportException(WebImportErrorCode.TOO_LARGE, "Content exceeds $maxBytes bytes")
                        output.write(buffer, 0, count)
                    }
                }
                return WebFetchResult(requested.toString(), current.toString(), connection.contentType, output.toByteArray())
            } catch (error: WebImportException) {
                throw error
            } catch (error: Exception) {
                throw mapNetworkError(error)
            } finally {
                connection.disconnect()
            }
        }
        throw WebImportException(WebImportErrorCode.REDIRECT_LOOP, "More than $MAX_REDIRECTS redirects")
    }

    private fun mapNetworkError(error: Exception): WebImportException = when (error) {
        is UnknownHostException -> WebImportException(WebImportErrorCode.DNS_FAILED, "DNS lookup failed", error)
        is SocketTimeoutException -> WebImportException(WebImportErrorCode.CONNECT_TIMEOUT, "Connection timed out", error)
        is SSLException -> WebImportException(WebImportErrorCode.TLS_ERROR, "TLS failed", error)
        else -> WebImportException(WebImportErrorCode.HTTP_ERROR, error.message ?: "Network request failed", error)
    }

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000L

    companion object { private const val MAX_REDIRECTS = 5 }
}
