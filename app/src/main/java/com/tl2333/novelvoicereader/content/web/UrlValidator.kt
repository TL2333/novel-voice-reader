package com.tl2333.novelvoicereader.content.web

import java.net.IDN
import java.net.URI

object UrlValidator {
    fun validate(raw: String): URI {
        val value = raw.trim()
        require(value.length in 8..4_096) { "URL length is invalid" }
        val uri = URI(value)
        require(uri.scheme?.lowercase() in setOf("http", "https")) { "Only HTTP(S) URLs are allowed" }
        require(uri.rawUserInfo == null) { "URL user information is not allowed" }
        val host = requireNotNull(uri.host) { "URL host is required" }
        require(host.isNotBlank() && IDN.toASCII(host).length <= 253)
        require(uri.fragment?.length.orZero() <= 1_024)
        return uri.normalize()
    }

    private fun Int?.orZero(): Int = this ?: 0
}
