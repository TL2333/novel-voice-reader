package com.tl2333.novelvoicereader.reader

import java.io.IOException
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.http.HttpClient
import org.readium.r2.shared.util.http.HttpError
import org.readium.r2.shared.util.http.HttpRequest
import org.readium.r2.shared.util.http.HttpStreamResponse
import org.readium.r2.shared.util.http.HttpTry

/**
 * An [HttpClient] which makes the reader's offline-only policy explicit.
 *
 * Readium still requires an HTTP client while building its parser and asset retriever. Returning a
 * deterministic failure here guarantees that an EPUB cannot silently fetch remote resources.
 */
class OfflineHttpClient : HttpClient {
    override suspend fun stream(request: HttpRequest): HttpTry<HttpStreamResponse> =
        Try.failure(
            HttpError.IO(
                IOException("Network access is disabled for EPUB resource ${request.url}"),
            ),
        )
}
