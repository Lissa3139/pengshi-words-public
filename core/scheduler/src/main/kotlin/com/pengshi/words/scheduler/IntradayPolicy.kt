package com.pengshi.words.scheduler

import com.pengshi.words.model.Feedback

class IntradayPolicy {
    @Deprecated("Use eventsForProgress so completion is based on weighted same-day feedback")
    fun eventsFor(
        feedback: Feedback,
        completedOtherCardCount: Int,
        priorLearningStep: Int,
    ): List<IntradayEventDraft> {
        require(completedOtherCardCount >= 0) { "completedOtherCardCount must be non-negative" }
        require(priorLearningStep >= 0) { "priorLearningStep must be non-negative" }
        val maximumEvents = if (feedback == Feedback.AGAIN) 2 else 1
        val eventCount = (maximumEvents - priorLearningStep).coerceAtLeast(0)
        return drafts(feedback, completedOtherCardCount, eventCount)
    }

    fun eventsForProgress(
        feedback: Feedback,
        completedOtherCardCount: Int,
        memoryProgressBeforeFeedback: Int,
    ): List<IntradayEventDraft> {
        require(completedOtherCardCount >= 0) { "completedOtherCardCount must be non-negative" }
        require(memoryProgressBeforeFeedback >= 0) { "memoryProgressBeforeFeedback must be non-negative" }

        val progressAfterFeedback = SameDayMemoryProgress.afterFeedback(memoryProgressBeforeFeedback, feedback)
        val eventCount = if (progressAfterFeedback < SameDayMemoryProgress.COMPLETION_TARGET) 1 else 0

        return drafts(feedback, completedOtherCardCount, eventCount)
    }

    private fun drafts(
        feedback: Feedback,
        completedOtherCardCount: Int,
        eventCount: Int,
    ): List<IntradayEventDraft> {
        val delay = delayFor(feedback)
        return (1..eventCount).map { ordinal ->
            IntradayEventDraft(
                stepIndex = completedOtherCardCount + delay * ordinal,
                delayInOtherCards = delay,
            )
        }
    }

    fun insertionIndex(
        event: IntradayEventDraft,
        completedOtherCardCount: Int,
        remainingQueueSize: Int,
    ): Int {
        require(completedOtherCardCount >= 0) { "completedOtherCardCount must be non-negative" }
        require(remainingQueueSize >= 0) { "remainingQueueSize must be non-negative" }
        return (event.stepIndex - completedOtherCardCount).coerceIn(0, remainingQueueSize)
    }

    private fun delayFor(feedback: Feedback): Int = when (feedback) {
        Feedback.AGAIN -> 3
        Feedback.HARD -> 5
        Feedback.GOOD -> 8
        Feedback.EASY -> 12
    }
}
