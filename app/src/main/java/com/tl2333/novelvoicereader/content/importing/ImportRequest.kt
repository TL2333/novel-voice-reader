package com.tl2333.novelvoicereader.content.importing

enum class ImportOrigin { LOCAL_URI, ACTION_VIEW, ACTION_SEND, WEB_URL }

data class ImportRequest(
    val origin: ImportOrigin,
    val source: String,
    val declaredMimeType: String? = null,
) {
    init {
        require(source.isNotBlank())
        if (origin == ImportOrigin.WEB_URL) require(source.startsWith("http", ignoreCase = true))
    }
}

data class ExternalIntentEnvelope(
    val action: String?,
    val dataUri: String?,
    val streamUri: String?,
    val mimeType: String?,
)

sealed interface ExternalIntentRoute {
    data class Import(val request: ImportRequest, val deduplicationKey: String) : ExternalIntentRoute
    data class Unsupported(val reason: String) : ExternalIntentRoute
    data object Ignore : ExternalIntentRoute
}

object ExternalImportIntentRouter {
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_SEND = "android.intent.action.SEND"

    fun route(intent: ExternalIntentEnvelope): ExternalIntentRoute {
        val (origin, uri) = when (intent.action) {
            ACTION_VIEW -> ImportOrigin.ACTION_VIEW to intent.dataUri
            ACTION_SEND -> ImportOrigin.ACTION_SEND to (intent.streamUri ?: intent.dataUri)
            null -> return ExternalIntentRoute.Ignore
            else -> return ExternalIntentRoute.Ignore
        }
        if (uri.isNullOrBlank()) {
            return ExternalIntentRoute.Unsupported("外部请求没有可读取的文档地址。")
        }
        val scheme = uri.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme !in setOf("content", "file")) {
            return ExternalIntentRoute.Unsupported("只支持 content:// 或 file:// 文档。")
        }
        val request = ImportRequest(origin, uri, intent.mimeType)
        return ExternalIntentRoute.Import(
            request = request,
            deduplicationKey = listOf(intent.action, uri, intent.mimeType.orEmpty()).joinToString("\u0000"),
        )
    }
}

class ImportIntentDeduplicator(restoredKeys: Collection<String> = emptyList()) {
    private val processed: LinkedHashSet<String> = LinkedHashSet(restoredKeys.toList().takeLast(MAX_KEYS))

    fun accept(key: String): Boolean {
        if (!processed.add(key)) return false
        while (processed.size > MAX_KEYS) processed.remove(processed.first())
        return true
    }

    fun snapshot(): ArrayList<String> = ArrayList(processed)

    private companion object { const val MAX_KEYS = 16 }
}
