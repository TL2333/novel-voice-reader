package com.tl2333.novelvoicereader

import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.content.web.UrlValidator
import org.junit.Test

class UrlValidatorTest {
    @Test
    fun acceptsOnlyHttpAndHttpsWithoutCredentials() {
        assertThat(UrlValidator.validate("https://example.com/article").host).isEqualTo("example.com")
        listOf("file:///tmp/a", "content://a", "javascript:alert(1)", "data:text/plain,a", "https://user:pass@example.com/").forEach { value ->
            assertThat(runCatching { UrlValidator.validate(value) }.isFailure).isTrue()
        }
    }
}
