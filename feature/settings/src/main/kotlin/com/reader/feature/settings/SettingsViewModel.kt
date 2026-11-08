package com.reader.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.reader.core.datastore.ReaderSettings
import com.reader.core.datastore.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val settings: StateFlow<ReaderSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReaderSettings())

    fun setFontSize(sp: Float) = update { it.copy(fontSizeSp = sp.coerceIn(12f, 32f)) }
    fun setLineSpacing(mult: Float) = update { it.copy(lineSpacingMult = mult.coerceIn(1.0f, 2.5f)) }
    fun setTheme(theme: String) = update { it.copy(theme = theme) }
    fun setJustify(justify: Boolean) = update { it.copy(justify = justify) }
    fun setKeepScreenOn(on: Boolean) = update { it.copy(keepScreenOn = on) }
    fun setPageTurnMode(mode: String) = update { it.copy(pageTurnMode = mode) }

    private fun update(transform: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }
}
