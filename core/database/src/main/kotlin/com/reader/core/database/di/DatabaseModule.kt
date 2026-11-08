package com.reader.core.database.di

import android.content.Context
import androidx.room.Room
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

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ReaderDatabase =
        Room.databaseBuilder(context, ReaderDatabase::class.java, "reader.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
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
