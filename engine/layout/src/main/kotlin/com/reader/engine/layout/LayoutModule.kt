package com.reader.engine.layout

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LayoutModule {
    @Binds
    @Singleton
    abstract fun bindPaginator(impl: SimplePaginator): Paginator
}
