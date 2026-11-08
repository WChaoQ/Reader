package com.reader.core.data

import com.reader.core.datastore.SettingsRepository
import com.reader.core.model.ReaderSettings
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Feature-facing settings API; DataStore implementation details stay in the data layer. */
@Singleton
class ReaderPreferencesRepository @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    val settings: Flow<ReaderSettings>
        get() = settingsRepository.settings

    suspend fun update(transform: (ReaderSettings) -> ReaderSettings) {
        settingsRepository.update(transform)
    }
}
