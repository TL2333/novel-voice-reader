package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.content.web.ArticleExtractor
import com.tl2333.novelvoicereader.content.web.WebSnapshotRepository
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebSnapshotTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun persistsHtmlAndCanonicalDocumentForOfflineReading() {
        val html = "<html><body><main><h1>离线快照</h1><p>${"这是一段可以离线保存并继续朗读的正文。".repeat(5)}</p><p>第二段正文用于建立稳定块。</p></main></body></html>"
        val article = ArticleExtractor().extract("https://example.com/offline", html.toByteArray(), "d".repeat(64))
        val repository = WebSnapshotRepository(temporaryFolder.root)
        val snapshot = repository.create("https://example.com/offline", "https://example.com/offline", 123L, article)
        assertThat(java.io.File(snapshot.htmlPath).isFile).isTrue()
        assertThat(repository.loadDocument(snapshot)).isEqualTo(article.document)
    }
}
