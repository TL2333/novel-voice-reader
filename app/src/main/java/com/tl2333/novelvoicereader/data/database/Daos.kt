package com.tl2333.novelvoicereader.data.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY COALESCE(lastReadAt, createdAt) DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: String): BookEntity?

    @Query("SELECT * FROM books WHERE epubSha256 = :sha256")
    suspend fun getBySha256(sha256: String): BookEntity?

    @Upsert
    suspend fun upsert(book: BookEntity)

    @Query("UPDATE books SET lastReadAt = :timestamp WHERE id = :bookId")
    suspend fun updateLastReadAt(bookId: String, timestamp: Long)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ReadingProgressDao {
    @Query("SELECT * FROM reading_progress")
    fun observeAll(): Flow<List<ReadingProgressEntity>>

    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    fun observe(bookId: String): Flow<ReadingProgressEntity?>

    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    suspend fun get(bookId: String): ReadingProgressEntity?

    @Upsert
    suspend fun upsert(progress: ReadingProgressEntity)

    @Query("DELETE FROM reading_progress WHERE bookId = :bookId")
    suspend fun delete(bookId: String)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun observeForBook(bookId: String): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY createdAt DESC")
    suspend fun listForBook(bookId: String): List<BookmarkEntity>

    @Query("SELECT * FROM bookmarks WHERE id = :id")
    suspend fun get(id: String): BookmarkEntity?

    @Upsert
    suspend fun upsert(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface NarrationCacheDao {
    @Query("SELECT * FROM narration_cache WHERE cacheKey = :cacheKey")
    suspend fun get(cacheKey: String): NarrationCacheEntity?

    @Query("SELECT * FROM narration_cache ORDER BY lastAccessAt ASC, cacheKey ASC")
    suspend fun listLeastRecentlyUsed(): List<NarrationCacheEntity>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM narration_cache")
    fun observeTotalSize(): Flow<Long>

    @Upsert
    suspend fun upsert(entry: NarrationCacheEntity)

    @Query("UPDATE narration_cache SET lastAccessAt = :timestamp WHERE cacheKey = :cacheKey")
    suspend fun touch(cacheKey: String, timestamp: Long)

    @Query("DELETE FROM narration_cache WHERE cacheKey IN (:keys)")
    suspend fun deleteKeys(keys: List<String>)

    @Query("DELETE FROM narration_cache WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)

    @Query("DELETE FROM narration_cache")
    suspend fun deleteAll()
}
