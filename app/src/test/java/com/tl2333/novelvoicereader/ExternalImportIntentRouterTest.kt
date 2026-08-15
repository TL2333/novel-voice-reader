package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.content.importing.ExternalImportIntentRouter
import com.tl2333.novelvoicereader.content.importing.ExternalIntentEnvelope
import com.tl2333.novelvoicereader.content.importing.ExternalIntentRoute
import com.tl2333.novelvoicereader.content.importing.ImportIntentDeduplicator
import com.tl2333.novelvoicereader.content.importing.ImportOrigin
import org.junit.Test

class ExternalImportIntentRouterTest {
    @Test
    fun routesViewDataAndSendStream() {
        val view = ExternalImportIntentRouter.route(
            ExternalIntentEnvelope(ExternalImportIntentRouter.ACTION_VIEW, "content://docs/1", null, "application/pdf"),
        ) as ExternalIntentRoute.Import
        assertThat(view.request.origin).isEqualTo(ImportOrigin.ACTION_VIEW)
        assertThat(view.request.source).isEqualTo("content://docs/1")

        val send = ExternalImportIntentRouter.route(
            ExternalIntentEnvelope(ExternalImportIntentRouter.ACTION_SEND, null, "content://wechat/file/2", "application/octet-stream"),
        ) as ExternalIntentRoute.Import
        assertThat(send.request.origin).isEqualTo(ImportOrigin.ACTION_SEND)
        assertThat(send.request.source).isEqualTo("content://wechat/file/2")

        val sendDataFallback = ExternalImportIntentRouter.route(
            ExternalIntentEnvelope(ExternalImportIntentRouter.ACTION_SEND, "content://provider/data-only", null, "text/plain"),
        ) as ExternalIntentRoute.Import
        assertThat(sendDataFallback.request.source).isEqualTo("content://provider/data-only")
    }

    @Test
    fun rejectsMissingOrUnsafeSourcesAndIgnoresOtherActions() {
        assertThat(ExternalImportIntentRouter.route(
            ExternalIntentEnvelope(ExternalImportIntentRouter.ACTION_SEND, null, null, "application/pdf"),
        )).isInstanceOf(ExternalIntentRoute.Unsupported::class.java)
        assertThat(ExternalImportIntentRouter.route(
            ExternalIntentEnvelope(ExternalImportIntentRouter.ACTION_VIEW, "https://example.test/book.pdf", null, "application/pdf"),
        )).isInstanceOf(ExternalIntentRoute.Unsupported::class.java)
        assertThat(ExternalImportIntentRouter.route(
            ExternalIntentEnvelope("android.intent.action.MAIN", null, null, null),
        )).isEqualTo(ExternalIntentRoute.Ignore)
    }

    @Test
    fun duplicateKeySurvivesActivityStateSnapshot() {
        val key = (ExternalImportIntentRouter.route(
            ExternalIntentEnvelope(ExternalImportIntentRouter.ACTION_VIEW, "content://docs/1", null, "application/pdf"),
        ) as ExternalIntentRoute.Import).deduplicationKey
        val first = ImportIntentDeduplicator()
        assertThat(first.accept(key)).isTrue()
        assertThat(first.accept(key)).isFalse()
        assertThat(ImportIntentDeduplicator(first.snapshot()).accept(key)).isFalse()
    }
}
