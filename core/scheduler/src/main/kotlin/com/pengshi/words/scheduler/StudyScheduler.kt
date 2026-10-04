package com.pengshi.words.scheduler

import com.pengshi.words.model.CardStatus
import com.pengshi.words.model.Feedback
import com.pengshi.words.model.ReviewEventType

interface StudyScheduler {
    fun buildDailyPlan(input: DailyPlanInput): PlannedDay

    fun applyFeedback(input: FeedbackInput): SchedulingOutcome
}

class DefaultStudyScheduler(
    private val fsrsEngine: FsrsEngine = LocalFsrsEngine(),
    private val dailyPlanBuilder: DailyPlanBuilder = DailyPlanBuilder(),
    private val intradayPolicy: IntradayPolicy = IntradayPolicy(),
) : StudyScheduler {
    override fun buildDailyPlan(input: DailyPlanInput): PlannedDay = dailyPlanBuilder.build(input)

    override fun applyFeedback(input: FeedbackInput): SchedulingOutcome {
        val longTermCard = fsrsEngine.schedule(input.card, input.feedback, input.reviewedAt)
        val priorLearningStep = input.learningStepOverride ?: input.card.learningStep
        // Same-day points decide whether another short-term check is needed;
        // long-term FSRS scheduling remains an independent calculation.
        val events = intradayPolicy.eventsForProgress(
            feedback = input.feedback,
            completedOtherCardCount = input.completedOtherCardCount,
            memoryProgressBeforeFeedback = input.memoryProgressBeforeFeedback,
        )
        val updatedCard = longTermCard.copy(learningStep = priorLearningStep + events.size)
        val log = ReviewLogDraft(
            wordId = input.card.wordId,
            mode = input.card.mode,
            feedback = input.feedback,
            reviewedAt = input.reviewedAt,
            previousState = input.card,
            nextState = updatedCard,
            eventType = eventType(input.card.status),
            algorithmVersion = fsrsEngine.parameters.algorithmVersion,
        )
        return SchedulingOutcome(updatedCard, events, log)
    }

    private fun eventType(status: CardStatus): ReviewEventType = when (status) {
        CardStatus.NEW -> ReviewEventType.INITIAL_LEARNING
        CardStatus.LEARNING,
        CardStatus.RELEARNING,
        -> ReviewEventType.INTRADAY
        CardStatus.REVIEW -> ReviewEventType.LONG_TERM_REVIEW
    }
}
