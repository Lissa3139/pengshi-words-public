package com.pengshi.words.model

import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

data class ReviewLog(
    val id: Long = 0,
    val wordId: Long,
    val mode: StudyMode,
    val reviewedAt: Instant,
    val feedback: Feedback,
    val responseTimeMs: Long,
    val previousStateSnapshot: String,
    val nextStateSnapshot: String,
    val previousDueAt: Instant?,
    val nextDueAt: Instant?,
    val eventType: ReviewEventType,
)

interface StudyLogRepository {
    suspend fun append(log: ReviewLog)

    fun observeForDate(date: LocalDate): Flow<List<ReviewLog>>
}
