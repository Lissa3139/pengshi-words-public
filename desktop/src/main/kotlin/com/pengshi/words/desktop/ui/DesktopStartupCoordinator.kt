package com.pengshi.words.desktop.ui

import com.pengshi.words.sync.SyncResult
import com.pengshi.words.sync.SyncStatus
import com.pengshi.words.sync.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

class DesktopStartupCoordinator(
    private val scope: CoroutineScope,
    private val hasSyncConfiguration: () -> Boolean,
    private val syncNow: suspend () -> SyncResult,
    private val prepareLocalData: suspend () -> Unit,
    private val loadLocalData: suspend () -> Unit,
    private val refreshAfterBackgroundSync: suspend () -> Unit = loadLocalData,
) {
    private val _state = MutableStateFlow<DesktopStartupState>(DesktopStartupState.Loading)
    val state: StateFlow<DesktopStartupState> = _state.asStateFlow()
    private val operationMutex = Mutex()
    private var localDataLoaded = false

    suspend fun start() {
        if (!operationMutex.tryLock()) return
        try {
            startInternal()
        } finally {
            operationMutex.unlock()
        }
    }

    private suspend fun startInternal() {
        _state.value = DesktopStartupState.Loading
        localDataLoaded = false
        try {
            prepareLocalData()
            loadLocalData()
            localDataLoaded = true
            if (hasSyncConfiguration()) {
                _state.value = DesktopStartupState.Syncing
                val syncTask = scope.async { runCatching { syncNow() }.getOrNull() }
                val result = withTimeoutOrNull(5_000) { syncTask.await() }
                if (result == null) {
                    _state.value = DesktopStartupState.Ready
                    scope.launch {
                        syncTask.await()
                        runCatching {
                            prepareLocalData()
                            refreshAfterBackgroundSync()
                        }
                    }
                    return
                }
                prepareLocalData()
                loadLocalData()
            }
            _state.value = DesktopStartupState.Ready
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            _state.value = DesktopStartupState.Failed(
                failure.message ?: "无法加载本地学习数据。",
                canContinueOffline = localDataLoaded,
            )
        }
    }

    fun retry() {
        scope.launch { start() }
    }

    fun continueOffline() {
        scope.launch {
            if (!operationMutex.tryLock()) return@launch
            try {
                if (localDataLoaded) {
                    _state.value = DesktopStartupState.Ready
                } else {
                    startInternal()
                }
            } finally {
                operationMutex.unlock()
            }
        }
    }
}
