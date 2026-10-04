package com.pengshi.words.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pengshi.words.model.StudyMode
import com.pengshi.words.speech.SpeechSettings
import com.pengshi.words.speech.SpeechVoiceOption

class SettingsViewModel(
    initialState: SettingsUiState = SettingsUiState(),
    private val persist: (SettingsUiState) -> Unit = {},
) {
    var state by mutableStateOf(initialState)
        private set

    fun replaceState(value: SettingsUiState) {
        state = value
    }

    fun onAction(action: SettingsAction) {
        state = when (action) {
            SettingsAction.ToggleAutoPlayWord -> state.copy(autoPlayWord = !state.autoPlayWord)
            SettingsAction.ToggleAutoPlaySentence -> state.copy(autoPlaySentence = !state.autoPlaySentence)
            SettingsAction.SwitchMode -> state.copy(defaultMode = if (state.defaultMode == StudyMode.EN_TO_CN) StudyMode.CN_TO_EN else StudyMode.EN_TO_CN)
            is SettingsAction.SetDailyQuota -> state.copy(dailyQuota = action.quota, isUpdatingDailyQuota = false)
            SettingsAction.IncreaseSpeechRate -> state.copy(speechRate = SpeechSettings.clampRate(state.speechRate + .1f))
            SettingsAction.DecreaseSpeechRate -> state.copy(speechRate = SpeechSettings.clampRate(state.speechRate - .1f))
            is SettingsAction.SetSpeechRate -> state.copy(speechRate = SpeechSettings.clampRate(action.rate))
            SettingsAction.RefreshVoices, is SettingsAction.DownloadSpeechModel,
            is SettingsAction.ImportSpeechModel,
            is SettingsAction.UninstallSpeechModel,
            is SettingsAction.SetVoiceEnabled, is SettingsAction.PreviewSpeechVoice -> state
            is SettingsAction.SelectSpeechVoice -> state.copy(selectedVoiceKey = action.option?.key)
            SettingsAction.Export, SettingsAction.Restore -> state
        }
        persist(state)
    }

    fun setVoices(voices: List<SpeechVoiceOption>) {
        state = state.copy(availableVoices = voices)
    }

    fun setVoiceCatalog(catalog: com.pengshi.words.speech.SpeechVoiceCatalog) {
        state = state.copy(
            availableVoices = catalog.enabledVoices,
            allVoices = catalog.voices,
            selectedVoiceKey = catalog.selectedVoiceKey,
            voiceScanInProgress = catalog.refreshing,
            speechMessage = catalog.message,
            downloadingModel = catalog.downloadingModel,
            downloadPercent = catalog.downloadPercent,
            removingModel = catalog.removingModel,
        )
    }

    fun setDailyQuotaUpdating(value: Boolean) {
        state = state.copy(isUpdatingDailyQuota = value)
    }
}
