package com.tl2333.novelvoicereader.content.web

import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

object NetworkDestinationPolicy {
    fun requirePublic(uri: URI) {
        val addresses = try {
            InetAddress.getAllByName(uri.host)
        } catch (error: UnknownHostException) {
            throw WebImportException(WebImportErrorCode.DNS_FAILED, "DNS lookup failed", error)
        }
        if (addresses.isEmpty() || addresses.any(::isPrivate)) {
            throw WebImportException(WebImportErrorCode.UNSUPPORTED_CONTENT, "Local and private network URLs are not allowed")
        }
    }

    fun isPublic(uri: URI): Boolean = runCatching {
        uri.scheme?.lowercase() in setOf("http", "https") &&
            InetAddress.getAllByName(uri.host).let { it.isNotEmpty() && it.none(::isPrivate) }
    }.getOrDefault(false)

    private fun isPrivate(address: InetAddress): Boolean =
        address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
}
