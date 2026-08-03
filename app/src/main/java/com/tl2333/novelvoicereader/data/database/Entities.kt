package com.tl2333.novelvoicereader.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "books", indices = [Index(value = ["epubSha256"], unique = true)])
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String?,
    val coverPath: String?,
    val epubPath: String,
    val epubSha256: String,
    val mediaType: String,
    val createdAt: Long,
    val lastReadAt: Long?,
)

@Entity(
    tableName = "reading_progress",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ReadingProgressEntity(
    @PrimaryKey val bookId: String,
    /** Complete Readium Locator JSON, not a visual page number. */
    val locatorJson: String,
    val totalProgression: Double,
    val updatedAt: Long,
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["bookId"])],
)
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    /** Complete Readium Locator JSON. */
    val locatorJson: String,
    val label: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "narration_cache",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["bookId"]), Index(value = ["lastAccessAt"])],
)
data class NarrationCacheEntity(
    @PrimaryKey val cacheKey: String,
    val bookId: String,
    val href: String,
    val utteranceHash: String,
    val audioPath: String,
    val sizeBytes: Long,
    val lastAccessAt: Long,
)
