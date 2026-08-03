package com.tl2333.novelvoicereader

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.tl2333.novelvoicereader.data.database.BookEntity
import com.tl2333.novelvoicereader.data.database.BookmarkRepository
import com.tl2333.novelvoicereader.data.database.LocatorJson
import com.tl2333.novelvoicereader.data.database.NovelVoiceDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BookmarkRepositoryTest {
    private lateinit var database: NovelVoiceDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NovelVoiceDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun savesCanonicalLocatorAndDeletesBookmarkWithoutDeletingBook() = runBlocking {
        database.books().upsert(
            BookEntity(
                id = "book-1",
                title = "测试书",
                author = null,
                coverPath = null,
                epubPath = "/books/test.epub",
                epubSha256 = "a".repeat(64),
                mediaType = "application/epub+zip",
                createdAt = 10,
                lastReadAt = null,
            ),
        )
        val repository = BookmarkRepository(database.bookmarks(), clock = { 1234 }, idFactory = { "bookmark-1" })
        val locator = """{"href":"chapter.xhtml","locations":{"progression":0.3},"text":{"highlight":"一句话"}}"""

        val saved = repository.save("book-1", locator, "  第一处  ")
        assertThat(saved.id).isEqualTo("bookmark-1")
        assertThat(saved.label).isEqualTo("第一处")
        assertThat(LocatorJson.parse(database.bookmarks().get(saved.id)!!.locatorJson)["text"]).isNotNull()

        repository.delete(saved.id)
        assertThat(database.bookmarks().get(saved.id)).isNull()
        assertThat(database.books().get("book-1")).isNotNull()
    }
}
