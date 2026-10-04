package com.pengshi.words.startup

import com.pengshi.words.domain.StudySessionState
import com.pengshi.words.feature.home.HomeUiState
import com.pengshi.words.feature.settings.SettingsUiState
import com.pengshi.words.model.StartupWordFieldData
import com.pengshi.words.sync.SyncResult
import com.pengshi.words.sync.SyncStatus
import com.pengshi.words.sync.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex

class AppStartupCoordinator(
    initialState: AppStartupState = AppStartupState.Loading,
    private val scope: CoroutineScope,
    private val hasLocalContent: suspend () -> Boolean = { true },
    private val seedIfNeeded: suspend () -> Unit,
    private val loadHome: suspend () -> HomeUiState,
    private val loadSettings: suspend () -> SettingsUiState,
    private val resumeSession: suspend () -> StudySessionState?,
    private val writeBackup: suspend () -> Unit,
    private val hasSyncConfiguration: suspend () -> Boolean = { false },
    private val syncNow: suspend () -> SyncResult,
    private val applySavedQuota: suspend () -> Unit = {},
    private val onSeedCompleted: suspend () -> Unit = {},
    private val loadStartupWordFieldData: suspend () -> StartupWordFieldData = { StartupWordFieldData.Empty },
    private val onStartupWordFieldData: (StartupWordFieldData) -> Unit = {},
) {
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<AppStartupState> = _state.asStateFlow()
    private val operationMutex = Mutex()
    private var offlineInitialData: InitialAppData? = null

    suspend fun start() {
        if (!operationMutex.tryLock()) return
        try {
            startInternal()
        } finally {
            operationMutex.unlock()
        }
    }

    private suspend fun startInternal() {
        _state.value = AppStartupState.Loading
        try {
            val hadLocalContent = hasLocalContent()
            if (!hadLocalContent) seedIfNeeded()
            applySavedQuota()
            publishStartupWordFieldData()

            // Keep a complete local view ready behind the startup gate. If GitHub
            // is unavailable, the offline action can use this exact pre-sync view.
            val localInitial = loadInitialData()
            offlineInitialData = localInitial

            if (hasSyncConfiguration()) {
                _state.value = AppStartupState.Syncing
                val syncTask = scope.async { runCatching { syncNow() }.getOrNull() }
                val result = withTimeoutOrNull(5_000) { syncTask.await() }
                if (result == null) {
                    _state.value = AppStartupState.Ready(localInitial)
                    scope.launch {
                        syncTask.await()
                        runCatching {
                            applySavedQuota()
                            publishStartupWordFieldData()
                            val refreshed = loadInitialData()
                            offlineInitialData = refreshed
                            _state.value = AppStartupState.Ready(refreshed)
                        }
                    }
                } else {
                    publishStartupWordFieldData()
                    applySavedQuota()
                    val refreshed = loadInitialData()
                    offlineInitialData = refreshed
                    _state.value = AppStartupState.Ready(refreshed)
                }
            } else {
                _state.value = AppStartupState.Ready(localInitial)
            }

            if (hadLocalContent) {
                scope.launch {
                    runCatching { seedIfNeeded() }
                        .onSuccess {
                            runCatching { onSeedCompleted() }
                            publishStartupWordFieldData()
                        }
                }
            }
            scope.launch { runCatching { writeBackup() } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            _state.value = AppStartupState.Failed(
                error.message ?: "本地数据加载失败",
                canContinueOffline = offlineInitialData != null,
            )
        }
    }

    private suspend fun loadInitialData(): InitialAppData {
        val home = scope.async { loadHome() }
        val settings = scope.async { loadSettings() }
        val session = scope.async { resumeSession() }
        return InitialAppData(home.await(), settings.await(), session.await())
    }

    private suspend fun publishStartupWordFieldData() {
        val data = runCatching { loadStartupWordFieldData() }.getOrNull() ?: return
        runCatching { onStartupWordFieldData(data) }
    }

    fun retry() {
        scope.launch { start() }
    }

    fun continueOffline() {
        scope.launch {
            if (!operationMutex.tryLock()) return@launch
            try {
                val local = offlineInitialData
                if (local != null) {
                    _state.value = AppStartupState.Ready(local)
                    scope.launch { runCatching { writeBackup() } }
                } else {
                    startInternal()
                }
            } finally {
                operationMutex.unlock()
            }
        }
    }
}
