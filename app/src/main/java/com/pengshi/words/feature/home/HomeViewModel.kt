package com.pengshi.words.feature.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pengshi.words.model.StudyMode

class HomeViewModel(initialState: HomeUiState = HomeUiState()) {
    var state by mutableStateOf(initialState)
        private set

    var requestedMode by mutableStateOf<StudyMode?>(null)
        private set

    fun start(mode: StudyMode) {
        requestedMode = mode
    }
}
