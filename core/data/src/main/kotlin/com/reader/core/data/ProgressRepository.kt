package com.reader.core.data

import com.reader.core.common.ReaderDispatchers
import com.reader.core.database.ProgressDao
import com.reader.core.database.ProgressEntity
import com.reader.core.model.Locator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProgressRepository @Inject constructor(
    private val progressDao: ProgressDao,
    private val dispatchers: ReaderDispatchers,
) {
    fun observe(bookId: String): Flow<Locator?> =
        progressDao.observe(bookId).map { it?.toLocator() }

    suspend fun get(bookId: String): Locator? = withContext(dispatchers.io) {
        progressDao.observe(bookId).firstOrNull()?.toLocator()
    }

    suspend fun save(bookId: String, locator: Locator) = withContext(dispatchers.io) {
        progressDao.upsert(
            ProgressEntity(
                bookId = bookId,
                chapter = locator.chapter,
                offset = locator.offset,
                progression = locator.progression,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }
}
