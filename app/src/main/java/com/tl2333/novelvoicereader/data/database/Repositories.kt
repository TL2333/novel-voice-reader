package com.tl2333.novelvoicereader.data.database

import com.tl2333.novelvoicereader.tts.cache.CacheEntry
import com.tl2333.novelvoicereader.tts.cache.LruCachePolicy
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class BookRepository(private val dao: BookDao) {
    fun observeAll(): Flow<List<BookEntity>> = dao.observeAll()
    suspend fun get(id: String): BookEntity? = dao.get(id)
    suspend fun getBySha256(sha256: String): BookEntity? = dao.getBySha256(sha256)
    suspend fun save(book: BookEntity) = dao.upsert(book)
    suspend fun markRead(bookId: String, timestamp: Long = System.currentTimeMillis()) =
        dao.updateLastReadAt(bookId, timestamp)
    suspend fun delete(id: String) = dao.delete(id)
}

class ReadingProgressRepository(
    private val dao: ReadingProgressDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun observeAll(): Flow<List<ReadingProgressEntity>> = dao.observeAll()
    fun observe(bookId: String): Flow<ReadingProgressEntity?> = dao.observe(bookId)
    suspend fun get(bookId: String): ReadingProgressEntity? = dao.get(bookId)

    suspend fun save(bookId: String, locatorJson: String, totalProgression: Double? = null) {
        val canonical = LocatorJson.canonicalize(locatorJson)
        dao.upsert(
            ReadingProgressEntity(
                bookId = bookId,
                locatorJson = canonical,
                totalProgression = (totalProgression ?: LocatorJson.totalProgression(canonical) ?: 0.0)
                    .coerceIn(0.0, 1.0),
                updatedAt = clock(),
            ),
        )
    }

    suspend fun delete(bookId: String) = dao.delete(bookId)
}

class BookmarkRepository(
    private val dao: BookmarkDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    fun observeForBook(bookId: String): Flow<List<BookmarkEntity>> = dao.observeForBook(bookId)
    suspend fun listForBook(bookId: String): List<BookmarkEntity> = dao.listForBook(bookId)
    suspend fun get(id: String): BookmarkEntity? = dao.get(id)

    suspend fun save(
        bookId: String,
        locatorJson: String,
        label: String? = null,
        id: String = idFactory(),
    ): BookmarkEntity {
        val bookmark = BookmarkEntity(
            id = id,
            bookId = bookId,
            locatorJson = LocatorJson.canonicalize(locatorJson),
            label = label?.trim()?.takeIf(String::isNotEmpty),
            createdAt = clock(),
        )
        dao.upsert(bookmark)
        return bookmark
    }

    suspend fun delete(id: String) = dao.delete(id)
}

class NarrationCacheRepository(
    private val dao: NarrationCacheDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun observeTotalSize(): Flow<Long> = dao.observeTotalSize()
    suspend fun get(cacheKey: String): NarrationCacheEntity? = dao.get(cacheKey)
    suspend fun save(entry: NarrationCacheEntity) = dao.upsert(entry)
    suspend fun touch(cacheKey: String) = dao.touch(cacheKey, clock())
    suspend fun clearBook(bookId: String) = dao.deleteForBook(bookId)
    suspend fun clearAll() = dao.deleteAll()

    suspend fun trimTo(maxBytes: Long, protectedAudioPaths: Set<String> = emptySet()): List<String> {
        val rows = dao.listLeastRecentlyUsed()
        val evicted = LruCachePolicy.selectEvictions(
            entries = rows.map {
                CacheEntry(it.cacheKey, it.audioPath, it.sizeBytes, it.lastAccessAt)
            },
            maxBytes = maxBytes,
            protectedPaths = protectedAudioPaths,
        )
        if (evicted.isNotEmpty()) dao.deleteKeys(evicted.map(CacheEntry::key))
        return evicted.map(CacheEntry::path)
    }
}
