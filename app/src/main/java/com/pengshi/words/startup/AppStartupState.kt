package com.pengshi.words.startup

import com.pengshi.words.domain.StudySessionState
import com.pengshi.words.feature.home.HomeUiState
import com.pengshi.words.feature.settings.SettingsUiState

data class InitialAppData(
    val home: HomeUiState,
    val settings: SettingsUiState,
    val resumableSession: StudySessionState?,
)

sealed interface AppStartupState {
    data object Loading : AppStartupState
    data object Syncing : AppStartupState
    data class Ready(val initial: InitialAppData) : AppStartupState
    data class Failed(val message: String, val canContinueOffline: Boolean = false) : AppStartupState
}
