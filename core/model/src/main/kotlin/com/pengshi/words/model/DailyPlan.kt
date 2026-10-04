package com.pengshi.words.model

import java.time.Instant
import java.time.LocalDate

enum class DailyPlanStatus {
    IN_PROGRESS,
    COMPLETED,
}

enum class DailyItemStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
}

enum class PlanSource {
    DUE_REVIEW,
    NEW,
    /** Chosen explicitly by the user; reconciliation must preserve this choice. */
    MANUAL_NEW,
    /** Manually added from the dictionary; it never consumes the daily quota. */
    EXTRA,
}

val PlanSource.isNewWord: Boolean get() = this == PlanSource.NEW || this == PlanSource.MANUAL_NEW

enum class IntradayEventStatus {
    PENDING,
    COMPLETED,
    SKIPPED,
}

data class DailyPlan(
    val id: Long = 0,
    val localDate: LocalDate,
    val quota: Int = DailyQuota.DEFAULT,
    val plannedUniqueWordCount: Int,
    val completedUniqueWordCount: Int = 0,
    val status: DailyPlanStatus = DailyPlanStatus.IN_PROGRESS,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class DailyPlanItem(
    val id: Long = 0,
    val dailyPlanId: Long,
    val wordId: Long,
    val source: PlanSource,
    val selectionRank: Int,
    val status: DailyItemStatus = DailyItemStatus.PENDING,
)

data class IntradayReviewEvent(
    val id: Long = 0,
    val dailyPlanItemId: Long,
    val wordId: Long,
    val mode: StudyMode,
    val stepIndex: Int,
    val scheduledAt: Instant,
    val completedAt: Instant? = null,
    val status: IntradayEventStatus = IntradayEventStatus.PENDING,
    val feedback: Feedback? = null,
)
