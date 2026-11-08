package com.reader.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "book",
    indices = [Index("lastReadAt"), Index("title")],
)
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String?,
    val format: String,
    val fileSize: Long,
    val coverPath: String?,
    val importedAt: Long,
    val lastReadAt: Long?,
    val chapterCount: Int = 0,
    val state: String = "ready",
)

@Entity(
    tableName = "progress",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ProgressEntity(
    @PrimaryKey val bookId: String,
    val chapter: Int,
    val offset: Int,
    val progression: Float,
    val updatedAt: Long,
)

@Entity(
    tableName = "bookmark",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookId")],
)
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapter: Int,
    val offset: Int,
    val snippet: String,
    val createdAt: Long,
)

@Entity(
    tableName = "highlight",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookId", "chapter")],
)
data class HighlightEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapter: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val color: Int,
    val note: String?,
    val createdAt: Long,
)
