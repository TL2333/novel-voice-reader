package com.tl2333.novelvoicereader.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        BookEntity::class,
        ReadingProgressEntity::class,
        BookmarkEntity::class,
        NarrationCacheEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class NovelVoiceDatabase : RoomDatabase() {
    abstract fun books(): BookDao
    abstract fun readingProgress(): ReadingProgressDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun narrationCache(): NarrationCacheDao

    companion object {
        const val FILE_NAME = "novel-voice-reader.db"

        /** Add explicit migrations here whenever [version] is incremented. */
        val MIGRATIONS: Array<Migration> = emptyArray()

        fun open(context: Context): NovelVoiceDatabase =
            Room.databaseBuilder(context, NovelVoiceDatabase::class.java, FILE_NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
