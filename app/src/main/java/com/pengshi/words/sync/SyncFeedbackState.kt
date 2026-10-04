package com.pengshi.words.sync

/** Tracks visible progress for concurrent automatic and manual sync requests. */
internal data class SyncFeedbackState(
    val activeSyncs: Int = 0,
    val completionMessage: String? = null,
) {
    val isSyncing: Boolean get() = activeSyncs > 0

    fun started(): SyncFeedbackState = copy(
        activeSyncs = activeSyncs + 1,
        completionMessage = null,
    )

    fun finished(message: String?): SyncFeedbackState = copy(
        activeSyncs = (activeSyncs - 1).coerceAtLeast(0),
        completionMessage = message ?: completionMessage,
    )

    fun dismissed(): SyncFeedbackState = copy(completionMessage = null)
}
