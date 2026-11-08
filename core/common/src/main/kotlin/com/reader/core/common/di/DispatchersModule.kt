package com.reader.core.common.di

import com.reader.core.common.DefaultReaderDispatchers
import com.reader.core.common.ReaderDispatchers
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DispatchersModule {
    @Binds
    @Singleton
    abstract fun bindDispatchers(impl: DefaultReaderDispatchers): ReaderDispatchers
}
