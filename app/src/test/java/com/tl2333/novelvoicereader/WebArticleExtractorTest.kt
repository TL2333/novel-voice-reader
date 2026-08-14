package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.content.web.ArticleExtractor
import org.junit.Test

class WebArticleExtractorTest {
    @Test
    fun scoresArticleAndRemovesNavigationAndComments() {
        val html = """
            <html><head><title>站点标题</title><meta name="author" content="作者甲"></head><body>
            <nav>首页 登录 推荐</nav>
            <article class="article-content"><h1>真正的文章标题</h1>
            <p>这是第一段正文，包含足够多的中文内容，用于验证文章候选评分不会直接使用整个 body 的文字。</p>
            <p>这是第二段正文，继续描述一段完整内容，并保留标点、段落以及正确的朗读顺序。</p>
            <div class="comments"><p>评论噪声不应出现。</p></div></article>
            <footer>页脚广告</footer></body></html>
        """.trimIndent()
        val result = ArticleExtractor().extract("https://example.com/a", html.toByteArray(), "s".repeat(64))
        assertThat(result.document.title).isEqualTo("真正的文章标题")
        assertThat(result.document.author).isEqualTo("作者甲")
        assertThat(result.document.blocks.joinToString { it.text }).doesNotContain("评论噪声")
        assertThat(result.sanitizedHtml).doesNotContain("<nav")
    }
}
