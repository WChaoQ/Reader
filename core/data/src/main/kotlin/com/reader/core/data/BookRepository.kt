package com.reader.core.data

import com.reader.core.model.Book
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookRepository @Inject constructor() {
    fun observeBooks(): Flow<List<Book>> = flowOf(emptyList())
}
