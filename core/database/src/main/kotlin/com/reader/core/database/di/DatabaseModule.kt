package com.reader.core.database.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.reader.core.database.BookDao
import com.reader.core.database.BookmarkDao
import com.reader.core.database.HighlightDao
import com.reader.core.database.ProgressDao
import com.reader.core.database.ReaderDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE book ADD COLUMN sourceUri TEXT")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ReaderDatabase =
        Room.databaseBuilder(context, ReaderDatabase::class.java, "reader.db")
            .addMigrations(MIGRATION_1_2)
            .build()

    @Provides
    fun provideBookDao(db: ReaderDatabase): BookDao = db.bookDao()

    @Provides
    fun provideProgressDao(db: ReaderDatabase): ProgressDao = db.progressDao()

    @Provides
    fun provideBookmarkDao(db: ReaderDatabase): BookmarkDao = db.bookmarkDao()

    @Provides
    fun provideHighlightDao(db: ReaderDatabase): HighlightDao = db.highlightDao()
}
