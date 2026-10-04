package com.pengshi.words.navigation

internal object AppBackPolicy {
    fun shouldSyncAfterDestinationChange(previous: AppDestination?, current: AppDestination): Boolean =
        previous == AppDestination.STUDY && current != AppDestination.STUDY

    fun parentOf(destination: AppDestination): AppDestination? = when (destination) {
        AppDestination.HOME -> null
        AppDestination.SELECTION_DASHBOARD -> AppDestination.DECKS
        AppDestination.STUDY,
        AppDestination.DECKS,
        AppDestination.STATS,
        AppDestination.SETTINGS,
        -> AppDestination.HOME
    }

    fun shouldExitAtHome(nowMs: Long, lastPressMs: Long, windowMs: Long = 2_000L): Boolean =
        lastPressMs >= 0L && nowMs >= lastPressMs && nowMs - lastPressMs <= windowMs
}
