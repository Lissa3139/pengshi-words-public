package com.pengshi.words.feature.stats

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pengshi.words.model.Feedback
import com.pengshi.words.model.StudyDataSnapshotGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class StatsViewModel(
    private val gateway: StudyDataSnapshotGateway,
    private val scope: CoroutineScope,
) {
    var state by mutableStateOf(StatsUiState())
        private set

    fun refresh() {
        scope.launch {
            val snapshot = gateway.snapshot()
            val totalReviews = snapshot.reviewLogs.size
            val positive = snapshot.reviewLogs.count { it.feedback == Feedback.GOOD || it.feedback == Feedback.EASY }
            state = StatsUiState(
                totalWords = snapshot.words.size,
                newWords = snapshot.cardStates.count { it.status.name == "NEW" },
                reviewCount = totalReviews,
                completedDays = snapshot.dailyPlans.count { it.status.name == "COMPLETED" },
                estimatedRecallPercent = if (totalReviews == 0) 0 else positive * 100 / totalReviews,
            )
        }
    }
}
