package com.tl2333.novelvoicereader.tts.cache

data class CacheEntry(
    val key: String,
    val path: String,
    val sizeBytes: Long,
    val lastAccessAt: Long,
) {
    init {
        require(key.isNotBlank() && path.isNotBlank())
        require(sizeBytes >= 0)
    }
}

object LruCachePolicy {
    /** Selects oldest unprotected entries until total accounted size is at or below [maxBytes]. */
    fun selectEvictions(
        entries: Collection<CacheEntry>,
        maxBytes: Long,
        protectedPaths: Set<String> = emptySet(),
    ): List<CacheEntry> {
        require(maxBytes >= 0)
        var total = entries.fold(0L) { sum, entry -> Math.addExact(sum, entry.sizeBytes) }
        if (total <= maxBytes) return emptyList()
        val evictions = mutableListOf<CacheEntry>()
        for (entry in entries.sortedWith(compareBy(CacheEntry::lastAccessAt, CacheEntry::key))) {
            if (entry.path in protectedPaths) continue
            evictions += entry
            total -= entry.sizeBytes
            if (total <= maxBytes) break
        }
        return evictions
    }
}
