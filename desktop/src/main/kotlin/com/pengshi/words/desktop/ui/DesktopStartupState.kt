package com.pengshi.words.desktop.ui

sealed interface DesktopStartupState {
    data object Loading : DesktopStartupState
    data object Syncing : DesktopStartupState
    data object Ready : DesktopStartupState
    data class Failed(val message: String, val canContinueOffline: Boolean = false) : DesktopStartupState
}
