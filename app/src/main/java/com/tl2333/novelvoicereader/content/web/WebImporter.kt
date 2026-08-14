package com.tl2333.novelvoicereader.content.web

import android.content.Context
import com.tl2333.novelvoicereader.filesystem.Sha256
import java.io.File

sealed interface WebImportResult {
    data class Article(val snapshot: WebSnapshotEntity, val usedDynamicFallback: Boolean) : WebImportResult

    data class DirectDocument(
        val requestedUrl: String,
        val finalUrl: String,
        val fetchedAt: Long,
        val kind: WebContentKind,
        val bytes: ByteArray,
    ) : WebImportResult
}

class WebImporter(
    context: Context,
    private val fetcher: WebFetcher = WebFetcher(),
    private val extractor: ArticleExtractor = ArticleExtractor(),
    private val snapshots: WebSnapshotRepository = WebSnapshotRepository(File(context.filesDir, "web-snapshots")),
    private val fallback: WebViewFallbackExtractor = WebViewFallbackExtractor(context),
) {
    suspend fun import(url: String): WebImportResult {
        val fetched = fetcher.fetch(UrlValidator.validate(url))
        val kind = ContentTypeDetector.detect(fetched.contentType, fetched.bytes, java.net.URI(fetched.finalUrl).path.orEmpty())
        val fetchedAt = System.currentTimeMillis()
        if (kind == WebContentKind.UNSUPPORTED) {
            throw WebImportException(WebImportErrorCode.UNSUPPORTED_CONTENT, "Unsupported URL content type")
        }
        if (kind != WebContentKind.HTML) {
            return WebImportResult.DirectDocument(fetched.requestedUrl, fetched.finalUrl, fetchedAt, kind, fetched.bytes)
        }
        val snapshotId = Sha256.hash("${fetched.finalUrl}\u0000$fetchedAt\u0000${Sha256.hash(fetched.bytes)}")
        val first = runCatching { extractor.extract(fetched.finalUrl, fetched.bytes, snapshotId) }
        val article: ExtractedArticle
        val usedFallback: Boolean
        if (first.isSuccess) {
            article = first.getOrThrow()
            usedFallback = false
        } else {
            val dynamic = fallback.extract(fetched.finalUrl)
            article = extractor.extract(fetched.finalUrl, dynamic, snapshotId)
            usedFallback = true
        }
        return WebImportResult.Article(
            snapshots.create(fetched.requestedUrl, fetched.finalUrl, fetchedAt, article),
            usedFallback,
        )
    }
}
