package com.reader.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "reader_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val fontSizeSp = floatPreferencesKey("font_size_sp")
        val lineSpacingMult = floatPreferencesKey("line_spacing_mult")
        val paragraphSpacingPx = floatPreferencesKey("paragraph_spacing_px")
        val justify = booleanPreferencesKey("justify")
        val theme = stringPreferencesKey("theme")
        val pageTurnMode = stringPreferencesKey("page_turn_mode")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
    }

    val settings: Flow<ReaderSettings> = context.settingsDataStore.data.map { prefs ->
        ReaderSettings(
            fontSizeSp = prefs[Keys.fontSizeSp] ?: 18f,
            lineSpacingMult = prefs[Keys.lineSpacingMult] ?: 1.4f,
            paragraphSpacingPx = prefs[Keys.paragraphSpacingPx] ?: 12f,
            justify = prefs[Keys.justify] ?: true,
            theme = prefs[Keys.theme] ?: "day",
            pageTurnMode = prefs[Keys.pageTurnMode] ?: "slide",
            keepScreenOn = prefs[Keys.keepScreenOn] ?: false,
        )
    }

    suspend fun update(transform: (ReaderSettings) -> ReaderSettings) {
        context.settingsDataStore.edit { prefs ->
            val current = ReaderSettings(
                fontSizeSp = prefs[Keys.fontSizeSp] ?: 18f,
                lineSpacingMult = prefs[Keys.lineSpacingMult] ?: 1.4f,
                paragraphSpacingPx = prefs[Keys.paragraphSpacingPx] ?: 12f,
                justify = prefs[Keys.justify] ?: true,
                theme = prefs[Keys.theme] ?: "day",
                pageTurnMode = prefs[Keys.pageTurnMode] ?: "slide",
                keepScreenOn = prefs[Keys.keepScreenOn] ?: false,
            )
            val next = transform(current)
            prefs[Keys.fontSizeSp] = next.fontSizeSp
            prefs[Keys.lineSpacingMult] = next.lineSpacingMult
            prefs[Keys.paragraphSpacingPx] = next.paragraphSpacingPx
            prefs[Keys.justify] = next.justify
            prefs[Keys.theme] = next.theme
            prefs[Keys.pageTurnMode] = next.pageTurnMode
            prefs[Keys.keepScreenOn] = next.keepScreenOn
        }
    }
}
