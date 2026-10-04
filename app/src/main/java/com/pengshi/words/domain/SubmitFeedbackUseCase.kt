package com.pengshi.words.domain

import com.pengshi.words.model.DailyItemStatus
import com.pengshi.words.model.DailyPlanRepository
import com.pengshi.words.model.Feedback
import com.pengshi.words.model.FeedbackContext
import com.pengshi.words.model.FeedbackSubmission
import com.pengshi.words.model.FeedbackTransaction
import com.pengshi.words.model.FeedbackUndoToken
import com.pengshi.words.model.IntradayEventStatus
import com.pengshi.words.model.IntradayReviewEvent
import com.pengshi.words.model.PlanSource
import com.pengshi.words.model.ReviewLog
import com.pengshi.words.model.ReviewEventType
import com.pengshi.words.scheduler.FeedbackInput
import com.pengshi.words.scheduler.SameDayMemoryProgress
import com.pengshi.words.scheduler.StudyScheduler
import java.time.Instant

class SubmitFeedbackUseCase(
    private val repository: DailyPlanRepository,
    private val scheduler: StudyScheduler,
    private val renderer: StartDailyStudyUseCase = StartDailyStudyUseCase(repository, scheduler),
) {
    data class FeedbackSessionResult(
        val session: StudySessionState,
        val undoToken: FeedbackUndoToken,
    )

    suspend fun submitFeedback(itemId: Long, feedback: Feedback, now: Instant): StudySessionState {
        return submitFeedbackWithUndo(itemId, feedback, now).session
    }

    suspend fun submitFeedbackWithUndo(
        itemId: Long,
        feedback: Feedback,
        now: Instant,
        responseTimeMs: Long = 0L,
        submission: FeedbackSubmission = FeedbackSubmission(),
    ): FeedbackSessionResult {
        val item = requireNotNull(repository.getItem(itemId)) { "Unknown daily plan item: $itemId" }
        val plan = requireNotNull(repository.getPlanById(item.dailyPlanId)) { "Unknown daily plan: ${item.dailyPlanId}" }
        val event = repository.getEvents(item.id)
            .filter { it.status == IntradayEventStatus.PENDING }
            .minWithOrNull(compareBy({ it.scheduledAt }, { it.stepIndex }))
            ?: error("No pending event for daily plan item: $itemId")
        val completedEventCount = repository.getItems(plan.id)
            .filter { it.source != PlanSource.EXTRA }
            .flatMap { repository.getEvents(it.id) }
            .count { it.status == IntradayEventStatus.COMPLETED }

        var previousCard: com.pengshi.words.model.CardState? = null
        var skippedEventSnapshots: List<IntradayReviewEvent> = emptyList()
        val transaction = repository.submitFeedback(event.id, transform = { context ->
            previousCard = context.card
            val progressBefore = SameDayMemoryProgress.fromCompletedEvents(context.itemEvents)
            val progressAfter = SameDayMemoryProgress.afterFeedback(progressBefore, feedback)
            skippedEventSnapshots = context.itemEvents.filter {
                it.id != context.event.id && it.status == IntradayEventStatus.PENDING
            }
            val isFirstEventOfReviewPlan = context.item.source == PlanSource.DUE_REVIEW &&
                context.itemEvents.none { it.status == IntradayEventStatus.COMPLETED }
            val isFirstEventOfItem = context.itemEvents.none { it.status == IntradayEventStatus.COMPLETED }
            val outcome = scheduler.applyFeedback(
                FeedbackInput(
                    card = context.card,
                    feedback = feedback,
                    reviewedAt = now,
                    dailyItemId = context.item.id,
                    completedOtherCardCount = completedEventCount,
                    learningStepOverride = if (isFirstEventOfReviewPlan) 0 else null,
                    memoryProgressBeforeFeedback = progressBefore,
                ),
            )
            val nextStep = (context.itemEvents.maxOfOrNull { it.stepIndex } ?: -1) + 1
            val followUps = outcome.intradayEvents.takeIf {
                progressAfter < SameDayMemoryProgress.COMPLETION_TARGET
            }.orEmpty().mapIndexed { index, draft ->
                IntradayReviewEvent(
                    dailyPlanItemId = context.item.id,
                    wordId = context.event.wordId,
                    mode = context.event.mode,
                    stepIndex = nextStep + index,
                    // A logical slot represents one opportunity to study another card;
                    // it is not a wall-clock alarm, so the queue stays interleaved.
                    scheduledAt = plan.createdAt.plusSeconds(draft.stepIndex.toLong()).plusNanos(index.toLong()),
                )
            }
            // A later same-day appearance decides whether this word returns to
            // today's queue. Keep its first-response card schedule and status;
            // repetitions still advances because sync uses it to order events.
            val persistedCard = if (isFirstEventOfItem) {
                outcome.updatedCard
            } else {
                context.card.copy(
                    repetitions = context.card.repetitions + 1,
                    updatedAt = now,
                )
            }
            val persistedLog = if (isFirstEventOfItem) outcome.log else outcome.log.copy(
                previousState = context.card,
                nextState = persistedCard,
                eventType = ReviewEventType.INTRADAY,
            )
            val completedEvent = context.event.copy(
                completedAt = now,
                status = IntradayEventStatus.COMPLETED,
                feedback = feedback,
            )
            val nextItemStatus = if (progressAfter >= SameDayMemoryProgress.COMPLETION_TARGET) {
                DailyItemStatus.COMPLETED
            } else {
                DailyItemStatus.IN_PROGRESS
            }
            val nextCompletedCount = context.plan.completedUniqueWordCount +
                if (context.item.source != PlanSource.EXTRA &&
                    nextItemStatus == DailyItemStatus.COMPLETED &&
                    context.item.status != DailyItemStatus.COMPLETED
                ) 1 else 0
            val updatedPlan = context.plan.copy(
                completedUniqueWordCount = nextCompletedCount,
                status = if (nextCompletedCount >= context.plan.plannedUniqueWordCount) com.pengshi.words.model.DailyPlanStatus.COMPLETED else com.pengshi.words.model.DailyPlanStatus.IN_PROGRESS,
                updatedAt = now,
            )
            FeedbackTransaction(
                updatedCard = persistedCard,
                reviewLog = ReviewLog(
                    wordId = persistedLog.wordId,
                    mode = persistedLog.mode,
                    reviewedAt = persistedLog.reviewedAt,
                    feedback = persistedLog.feedback,
                    responseTimeMs = responseTimeMs.coerceAtLeast(0L),
                    previousStateSnapshot = snapshot(persistedLog.previousState),
                    nextStateSnapshot = snapshot(persistedLog.nextState),
                    previousDueAt = persistedLog.previousState.dueAt,
                    nextDueAt = persistedLog.nextState.dueAt,
                    eventType = persistedLog.eventType,
                ),
                completedEvent = completedEvent,
                followUpEvents = followUps,
                updatedItem = context.item.copy(status = nextItemStatus),
                updatedPlan = updatedPlan,
                skippedEvents = skippedEventSnapshots.map { it.copy(status = IntradayEventStatus.SKIPPED) },
            )
        }, submission = submission)
        val finalPlan = requireNotNull(repository.getPlanById(plan.id))
        val session = renderer.render(finalPlan, event.mode)
        return FeedbackSessionResult(
            session = session,
            undoToken = FeedbackUndoToken(
                event = event,
                previousCard = requireNotNull(previousCard),
                previousItem = item,
                previousPlan = plan,
                reviewLogId = transaction.reviewLog.id,
                followUpEventIds = transaction.followUpEvents.map { it.id },
                autoAddedItemIds = emptyList(),
                syncEventId = transaction.syncEventId,
                skippedEventSnapshots = skippedEventSnapshots,
            ),
        )
    }

    suspend fun reviseFeedback(
        token: FeedbackUndoToken,
        feedback: Feedback,
        now: Instant,
        responseTimeMs: Long = 0L,
    ): FeedbackSessionResult {
        repository.undoFeedback(token)
        return submitFeedbackWithUndo(
            itemId = token.event.dailyPlanItemId,
            feedback = feedback,
            now = now,
            responseTimeMs = responseTimeMs,
            submission = FeedbackSubmission(token.syncEventId),
        )
    }

    private fun snapshot(card: com.pengshi.words.model.CardState): String =
        "CardState(id=${card.id},wordId=${card.wordId},mode=${card.mode},status=${card.status},difficulty=${card.difficulty},stability=${card.stability},retrievability=${card.retrievability},dueAt=${card.dueAt},lastReviewedAt=${card.lastReviewedAt},scheduledDays=${card.scheduledDays},repetitions=${card.repetitions},lapses=${card.lapses},learningStep=${card.learningStep},createdAt=${card.createdAt},updatedAt=${card.updatedAt})"
}
