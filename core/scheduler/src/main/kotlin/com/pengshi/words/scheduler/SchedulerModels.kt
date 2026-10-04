package com.pengshi.words.scheduler

import com.pengshi.words.model.CardState
import com.pengshi.words.model.Feedback
import com.pengshi.words.model.PlanSource
import com.pengshi.words.model.ReviewEventType
import com.pengshi.words.model.StudyMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DailyPlanInput(
    val localDate: LocalDate,
    val now: Instant,
    val quota: Int,
    val mode: StudyMode,
    val candidates: List<PlanCandidate>,
    /** Normalized weights for selecting new cards; empty preserves legacy ordering. */
    val deckWeights: Map<Long, Int> = emptyMap(),
    /** Membership used to resolve overlapping new-card candidates. */
    val deckIdsByWordId: Map<Long, Set<Long>> = emptyMap(),
    /** Time zone used to decide which calendar day a review belongs to. */
    val zoneId: ZoneId = ZoneId.systemDefault(),
)

data class PlanCandidate(
    val wordId: Long,
    val isNew: Boolean,
    val dueAt: Instant?,
    val retrievability: Double?,
    val overdueSeconds: Long,
    val familyKey: String? = null,
    val initialKey: String? = null,
    val difficulty: Double? = null,
    val lapses: Int = 0,
    val lastReviewedAt: Instant? = null,
    /** Natural-day interval used by the explainable MoMo-like priority score. */
    val intervalDays: Long? = null,
    /** Lower values are more frequent; missing ranks are intentionally last. */
    val frequencyRank: Int? = null,
)

data class FeedbackInput(
    val card: CardState,
    val feedback: Feedback,
    val reviewedAt: Instant,
    val dailyItemId: Long,
    val completedOtherCardCount: Int,
    /** Same-day learning progress for the current plan; null keeps the card's stored step. */
    val learningStepOverride: Int? = null,
    /** Progress accumulated by completed feedback events on this daily item. */
    val memoryProgressBeforeFeedback: Int = 0,
)

data class IntradayEventDraft(
    val stepIndex: Int,
    val delayInOtherCards: Int,
)

data class ReviewLogDraft(
    val wordId: Long,
    val mode: StudyMode,
    val feedback: Feedback,
    val reviewedAt: Instant,
    val previousState: CardState,
    val nextState: CardState,
    val eventType: ReviewEventType,
    val algorithmVersion: String,
)

data class PlannedDay(
    val wordIds: List<Long>,
    val sourceByWordId: Map<Long, PlanSource>,
)

data class SchedulingOutcome(
    val updatedCard: CardState,
    val intradayEvents: List<IntradayEventDraft>,
    val log: ReviewLogDraft,
)
