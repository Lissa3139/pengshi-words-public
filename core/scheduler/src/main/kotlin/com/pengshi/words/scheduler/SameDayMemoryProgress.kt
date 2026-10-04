package com.pengshi.words.scheduler

import com.pengshi.words.model.Feedback
import com.pengshi.words.model.IntradayEventStatus
import com.pengshi.words.model.IntradayReviewEvent

/** Deterministic per-word progress for deciding whether today's learning is complete. */
object SameDayMemoryProgress {
    const val COMPLETION_TARGET = 100

    fun afterFeedback(progressBefore: Int, feedback: Feedback): Int {
        require(progressBefore >= 0) { "progressBefore must be non-negative" }
        val delta = when (feedback) {
            Feedback.AGAIN -> -50
            Feedback.HARD -> 0
            Feedback.GOOD -> 50
            Feedback.EASY -> 70
        }
        return (progressBefore + delta).coerceAtLeast(0)
    }

    fun fromFeedbacks(feedbacks: Iterable<Feedback>): Int = feedbacks.fold(0, ::afterFeedback)

    fun fromCompletedEvents(events: Iterable<IntradayReviewEvent>): Int = events.asSequence()
        .filter { it.status == IntradayEventStatus.COMPLETED }
        .sortedWith(compareBy<IntradayReviewEvent>({ it.completedAt }, { it.stepIndex }, { it.mode.name }, { it.feedback?.name }))
        .mapNotNull { it.feedback }
        .fold(0, ::afterFeedback)
}
